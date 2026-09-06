"""
Text-to-Speech Synthesizer using Edge-TTS and Offline SAPI5 Fallback.
Handles text cleaning, language detection, and audio file generation.
"""
import re
import uuid
import asyncio
import logging
from pathlib import Path
from typing import Optional
import config

logger = logging.getLogger("IGIRS.Synthesizer")

def clean_text_for_speech(text: str) -> str:
    """Removes markdown symbols, URLs, code blocks, and emojis for natural speech."""
    if not text:
        return ""

    # Remove code blocks
    text = re.sub(r"```[\s\S]*?```", " [code block omitted] ", text)
    text = re.sub(r"`[^`]*`", "", text)

    # Remove URLs
    text = re.sub(r"https?://\S+|www\.\S+", " link ", text)

    # Remove markdown headers, bold, italics, quotes
    text = re.sub(r"[#*_~>]+", "", text)

    # Remove list bullet markers like "* " or "- "
    text = re.sub(r"^\s*[-*•]\s+", "", text, flags=re.MULTILINE)

    # Replace common symbol clutter
    text = re.sub(r"[\[\]\(\)\{\}]", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text

def detect_language(text: str) -> str:
    """Detects if the text contains Tamil characters."""
    for char in text:
        if "\u0b80" <= char <= "\u0bff":
            return "ta"
    return "en"

class TTSSynthesizer:
    def __init__(self, output_dir: Path = config.TEMP_AUDIO_DIR):
        self.output_dir = output_dir
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self._offline_engine = None
        self._kokoro = None

    def _get_kokoro(self):
        """Lazily initializes and caches the Kokoro-82M ONNX model."""
        if self._kokoro is None:
            try:
                from kokoro_onnx import Kokoro
                model_path = Path(getattr(config, "KOKORO_MODEL_PATH", "models/kokoro/kokoro-v1.0.onnx"))
                voices_path = Path(getattr(config, "KOKORO_VOICES_PATH", "models/kokoro/voices-v1.0.bin"))
                if not model_path.is_absolute():
                    model_path = Path(getattr(config, "BASE_DIR", ".")) / model_path
                if not voices_path.is_absolute():
                    voices_path = Path(getattr(config, "BASE_DIR", ".")) / voices_path

                if model_path.exists() and voices_path.exists():
                    logger.info(f"Loading Kokoro-82M ONNX model from {model_path.name}...")
                    self._kokoro = Kokoro(str(model_path.resolve()), str(voices_path.resolve()))
                    logger.info("Kokoro-82M model loaded successfully.")
                else:
                    logger.warning(f"Kokoro model files not found: {model_path} or {voices_path}")
            except Exception as e:
                logger.error(f"Failed to load Kokoro-82M engine: {e}")
                self._kokoro = None
        return self._kokoro

    def _synthesize_kokoro(self, text: str, voice: str = None, rate: str = config.DEFAULT_TTS_RATE) -> Optional[Path]:
        """Synthesizes text using local Kokoro-82M ONNX model into a WAV file."""
        kokoro = self._get_kokoro()
        if not kokoro:
            return None

        # Parse speed from rate string (e.g. "+10%" -> 1.10)
        speed = 1.05
        try:
            clean_rate = rate.replace("%", "").strip()
            pct = float(clean_rate)
            speed = max(0.6, min(1.8, 1.0 + (pct / 100.0)))
        except Exception:
            speed = 1.05

        target_voice = voice or getattr(config, "DEFAULT_KOKORO_VOICE", "am_adam")
        # Validate voice exists in Kokoro
        try:
            available = kokoro.get_voices()
            if target_voice not in available:
                target_voice = "am_adam" if "am_adam" in available else available[0]
        except Exception:
            target_voice = "am_adam"

        import soundfile as sf
        unique_id = uuid.uuid4().hex[:8]
        out_file = self.output_dir / f"speech_kokoro_{unique_id}.wav"

        samples, sr = kokoro.create(text, voice=target_voice, speed=speed, lang="en-us")
        sf.write(str(out_file), samples, sr)

        if out_file.exists() and out_file.stat().st_size > 0:
            return out_file
        return None

    async def _async_edge_tts(self, text: str, voice: str, rate: str, out_file: Path):
        import edge_tts
        communicate = edge_tts.Communicate(text, voice, rate=rate)
        await communicate.save(str(out_file))

    def synthesize(
        self,
        text: str,
        english_voice: str = config.DEFAULT_ENGLISH_VOICE,
        tamil_voice: str = config.DEFAULT_TAMIL_VOICE,
        rate: str = config.DEFAULT_TTS_RATE
    ) -> Optional[Path]:
        """
        Synthesizes text into an audio file (WAV/MP3) and returns the file path.
        Priority: Kokoro-82M (if enabled & English) -> Edge-TTS -> pyttsx3 (SAPI5).
        """
        cleaned = clean_text_for_speech(text)
        if not cleaned:
            return None

        lang = detect_language(cleaned)
        tts_engine = getattr(config, "TTS_ENGINE", "kokoro").lower().strip()

        # 1. Primary Method: Kokoro-82M Local Neural AI (for English)
        if tts_engine == "kokoro" and lang == "en":
            try:
                # If english_voice is a Kokoro voice ID (e.g. "am_adam", "af_heart"), use it
                k_voice = english_voice if (english_voice.startswith(("af_", "am_", "bf_", "bm_", "hf_"))) else getattr(config, "DEFAULT_KOKORO_VOICE", "am_adam")
                kokoro_file = self._synthesize_kokoro(cleaned, voice=k_voice, rate=rate)
                if kokoro_file and kokoro_file.exists():
                    return kokoro_file
            except Exception as e:
                logger.warning(f"Kokoro synthesis failed ({e}), falling back to Edge-TTS...")

        # 2. Secondary Method: Edge-TTS (Default for Tamil or fallback for English)
        voice = tamil_voice if lang == "ta" else (
            english_voice if not english_voice.startswith(("af_", "am_", "bf_", "bm_", "hf_")) else getattr(config, "DEFAULT_ENGLISH_VOICE", "en-US-AndrewNeural")
        )

        unique_id = uuid.uuid4().hex[:8]
        out_file = self.output_dir / f"speech_{unique_id}_{lang}.mp3"

        try:
            asyncio.run(self._async_edge_tts(cleaned, voice, rate, out_file))
            if out_file.exists() and out_file.stat().st_size > 0:
                return out_file
        except Exception as e:
            logger.warning(f"Edge-TTS synthesis failed ({e}), attempting offline fallback...")

        # 3. Fallback Method: pyttsx3 (SAPI5)
        try:
            import pyttsx3
            fallback_wav = self.output_dir / f"speech_{unique_id}_fallback.wav"
            engine = pyttsx3.init()
            engine.save_to_file(cleaned, str(fallback_wav))
            engine.runAndWait()
            if fallback_wav.exists() and fallback_wav.stat().st_size > 0:
                return fallback_wav
        except Exception as fallback_err:
            logger.error(f"Offline pyttsx3 fallback failed: {fallback_err}")

        return None
