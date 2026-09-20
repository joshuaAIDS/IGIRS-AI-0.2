import os
from pathlib import Path

# Base Paths
BASE_DIR = Path(__file__).resolve().parent
TEMP_AUDIO_DIR = BASE_DIR / "temp_audio"
TEMP_AUDIO_DIR.mkdir(parents=True, exist_ok=True)

FACTS_FILE = BASE_DIR / "facts_store.json"
MEMORY_FILE = BASE_DIR / "memory_store.json"
NOTES_FILE = BASE_DIR / "notes_store.json"
KNOWLEDGE_STORE_FILE = BASE_DIR / "knowledge_store.json"
DOCUMENTS_DIR = BASE_DIR / "data_documents"
DOCUMENTS_DIR.mkdir(parents=True, exist_ok=True)
# Primary Screenshots Directory (Strictly configured to user's OneDrive Screenshots folder)
SCREENSHOTS_DIR = Path(r"C:\Users\joshu\OneDrive\Scans\Pictures\Screenshots")
if not SCREENSHOTS_DIR.exists():
    try:
        SCREENSHOTS_DIR.mkdir(parents=True, exist_ok=True)
    except Exception:
        SCREENSHOTS_DIR = Path.home() / "Pictures" / "Screenshots"
        SCREENSHOTS_DIR.mkdir(parents=True, exist_ok=True)

# All web & system captures save to the user's OneDrive Screenshots folder
WEB_SCREENSHOTS_DIR = SCREENSHOTS_DIR
CONTACTS_FILE = BASE_DIR / "contacts_store.json"
EMAIL_CONFIG_FILE = BASE_DIR / "email_config.json"
DEFAULT_COUNTRY_CODE = "+91"
DEFAULT_CURRENCY = "INR"
DEFAULT_CURRENCY_SYMBOL = "₹"
BROWSER_HEADLESS = True
DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
# API Keys Files
API_KEYS_FILE = BASE_DIR / "IGIRS AI (API KEYS).txt"
GROQ_KEYS_FILE = BASE_DIR / "GROQ API KEYS.txt"

# Load NVIDIA API Keys (Multi-key support with auto-rotation)
NVIDIA_API_KEYS = []

if API_KEYS_FILE.exists():
    with open(API_KEYS_FILE, "r", encoding="utf-8") as f:
        for line in f:
            clean_key = line.strip()
            if clean_key and not clean_key.startswith("#"):
                NVIDIA_API_KEYS.append(clean_key)

# Check environment variable as additional fallback
env_key = os.environ.get("NVIDIA_API_KEY")
if env_key and env_key not in NVIDIA_API_KEYS:
    NVIDIA_API_KEYS.append(env_key)

# Default fallback keys (load strictly from gitignored API_KEYS_FILE or environment)
if not NVIDIA_API_KEYS:
    NVIDIA_API_KEYS = []

# Load Groq API Keys (10-Key Auto-Rotation Pool)
GROQ_API_KEYS = []

if GROQ_KEYS_FILE.exists():
    with open(GROQ_KEYS_FILE, "r", encoding="utf-8") as f:
        for line in f:
            clean_key = line.strip()
            if clean_key and not clean_key.startswith("#"):
                GROQ_API_KEYS.append(clean_key)

env_groq = os.environ.get("GROQ_API_KEY")
if env_groq and env_groq not in GROQ_API_KEYS:
    GROQ_API_KEYS.append(env_groq)

# LLM Providers Configuration
# Groq: Ultra-fast LPU inference (Qwen-27B default, GPT-120B reasoning)
GROQ_API_BASE_URL = "https://api.groq.com/openai/v1"
GROQ_PRIMARY_MODEL = "openai/gpt-oss-20b"  # Ultra-fast 20B conversational & tool model (sub-second)
GROQ_REASONING_MODEL = "openai/gpt-oss-120b"  # 120B deep reasoning model
GROQ_FALLBACK_MODEL = "groq/compound"

# Active LLM Model Selection (100% Groq Powered)
PRIMARY_LLM_MODEL = GROQ_PRIMARY_MODEL
FALLBACK_LLM_MODELS = [
    GROQ_FALLBACK_MODEL,
    GROQ_REASONING_MODEL,
    "qwen/qwen3.8-27b"
]

# NVIDIA NIM (Legacy / Optional Vision only)
NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1"

# Provider Selection: "groq" (Sole primary LLM engine with 10-Key Auto-Rotation)
LLM_PROVIDER = "groq"
LLM_TEMPERATURE = 0.7
LLM_MAX_TOKENS = 1024  # Expanded from 150 to allow comprehensive, high-quality answers!

# TTS Configuration (Kokoro-82M & Edge-TTS)
VOICE_ENABLED_DEFAULT = True
TTS_ENGINE = "kokoro"  # Primary engine: "kokoro" (Local human AI) or "edge-tts" (Microsoft Neural)
KOKORO_MODEL_PATH = BASE_DIR / "models" / "kokoro" / "kokoro-v1.0.onnx"
KOKORO_VOICES_PATH = BASE_DIR / "models" / "kokoro" / "voices-v1.0.bin"
DEFAULT_KOKORO_VOICE = "am_adam"  # Popular: "am_adam" (warm male), "af_heart" (expressive female), "am_michael", "af_bella"
DEFAULT_ENGLISH_VOICE = "en-US-AndrewNeural"
DEFAULT_TAMIL_VOICE = "ta-IN-PallaviNeural"
FALLBACK_ENGLISH_VOICE = "en-US-BrianNeural"
DEFAULT_TTS_RATE = "-8%"  # Relaxed, clear, and natural human conversational pace
DEFAULT_TTS_VOLUME = 1.0  # Range: 0.0 to 1.0

# User Identity & Defaults
DEFAULT_USER_NAME = "Joshua"
ASSISTANT_NAME = "IGIRS AI"
LANGUAGE_PREFERENCE = "English"
APP_VERSION = "0.2.0"
JARVIS_MODE = False
JARVIS_HONORIFIC = ""


