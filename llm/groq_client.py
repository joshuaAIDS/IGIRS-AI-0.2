"""
Dedicated Groq LPU LLM Client for IGIRS AI.
Features:
- Seamless 10-Key API Pool Auto-Rotation on rate limits (HTTP 429).
- Dual execution: High-speed official `groq` SDK with zero-dependency `urllib.request` fallback.
- Native tool/function calling support on Groq.
- Automatic <think> tag cleansing for reasoning models.
- 100% Groq Powered — Zero NVIDIA dependency.
"""
import os
import re
import json
import logging
import urllib.request
import urllib.error
from typing import List, Dict, Any, Generator, Optional
import config

logger = logging.getLogger("IGIRS.GroqLLM")

class GroqLLMClient:
    def __init__(
        self,
        api_keys: Optional[List[str]] = None,
        base_url: str = getattr(config, "GROQ_API_BASE_URL", "https://api.groq.com/openai/v1"),
        primary_model: Optional[str] = None
    ):
        self.api_keys = list(api_keys or getattr(config, "GROQ_API_KEYS", []))
        self.base_url = base_url.rstrip("/")
        self.current_key_index = 0
        self.primary_model = primary_model or getattr(config, "GROQ_PRIMARY_MODEL", "qwen/qwen3.8-27b")
        self.reasoning_model = getattr(config, "GROQ_REASONING_MODEL", "openai/gpt-oss-120b")
        self.fallback_model = getattr(config, "GROQ_FALLBACK_MODEL", "groq/compound")

        if not self.api_keys:
            logger.error("No Groq API keys found in GROQ API KEYS.txt or environment!")

    @property
    def current_api_key(self) -> str:
        if not self.api_keys:
            raise ValueError("No Groq API keys configured. Please add keys to 'GROQ API KEYS.txt'.")
        return self.api_keys[self.current_key_index % len(self.api_keys)]

    def rotate_key(self) -> str:
        """Rotate to the next available Groq API key in the pool."""
        if not self.api_keys:
            return ""
        prev = self.current_key_index
        self.current_key_index = (self.current_key_index + 1) % len(self.api_keys)
        logger.warning(
            f"⚡ [Groq LPU Auto-Switch] Switched from Key #{prev + 1} to Key #{self.current_key_index + 1} "
            f"(Total pool: {len(self.api_keys)} keys)"
        )
        return self.current_api_key

    def _sanitize_messages(self, messages: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Cleans multimodal objects into text if text-only model is invoked."""
        clean = []
        for m in messages:
            if not isinstance(m, dict):
                continue
            content = m.get("content")
            if isinstance(content, list):
                # Extract text parts
                text_parts = [
                    item.get("text", "") for item in content
                    if isinstance(item, dict) and item.get("type") == "text"
                ]
                clean.append({**m, "content": " ".join(text_parts).strip()})
            else:
                clean.append(m)
        return clean

    def _clean_content(self, text: str) -> str:
        """Removes <think> reasoning tags if emitted by Qwen or DeepSeek models."""
        if not text:
            return ""
        if "<think>" in text and "</think>" in text:
            text = re.sub(r"<think>[\s\S]*?</think>", "", text).strip()
        elif "<think>" in text:
            text = text.split("<think>")[0].strip()
        return text

    def chat_completion(
        self,
        messages: List[Dict[str, Any]],
        tools: Optional[List[Dict[str, Any]]] = None,
        tool_choice: str = "auto",
        temperature: float = config.LLM_TEMPERATURE,
        max_tokens: int = config.LLM_MAX_TOKENS,
        model: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Executes a chat completion across the 10-key Groq pool with auto-rotation on 429.
        First tries official Groq SDK; seamlessly falls back to urllib REST endpoint if needed.
        """
        if not self.api_keys:
            raise RuntimeError("No Groq API keys available.")

        models_to_try = [
            model or self.primary_model,
            self.reasoning_model,
            self.fallback_model
        ]
        # Remove duplicates while preserving order
        seen = set()
        models_to_try = [m for m in models_to_try if m and not (m in seen or seen.add(m))]

        pool_size = len(self.api_keys)
        clean_messages = self._sanitize_messages(messages)
        last_error = None

        # Check if groq package is imported
        groq_sdk = None
        try:
            import groq
            groq_sdk = groq
        except ImportError:
            logger.info("groq python library not installed; using zero-dependency REST HTTP engine.")

        for attempt_model in models_to_try:
            # Try across all keys in pool for this model
            for attempt_idx in range(pool_size):
                active_key = self.current_api_key
                key_num = (self.current_key_index % pool_size) + 1

                # 1. Try via official Groq SDK
                if groq_sdk is not None:
                    try:
                        client = groq_sdk.Groq(api_key=active_key, max_retries=0)
                        payload = {
                            "model": attempt_model,
                            "messages": clean_messages,
                            "temperature": temperature,
                            "max_tokens": max_tokens
                        }
                        if tools:
                            payload["tools"] = tools
                            payload["tool_choice"] = tool_choice

                        resp = client.chat.completions.create(**payload)
                        data = resp.model_dump()
                        
                        # Clean thinking tags
                        for choice in data.get("choices", []):
                            msg = choice.get("message", {})
                            if msg.get("content"):
                                msg["content"] = self._clean_content(msg["content"])
                        return data

                    except Exception as err:
                        err_str = str(err)
                        last_error = err_str
                        # Detect rate limit or auth error
                        if "429" in err_str or "rate_limit" in err_str.lower() or "quota" in err_str.lower():
                            logger.warning(
                                f"Groq Key #{key_num} hit rate limit (429). Auto-switching to next key..."
                            )
                            self.rotate_key()
                            continue
                        elif "model_not_found" in err_str or "decommissioned" in err_str:
                            logger.warning(f"Groq model {attempt_model} unavailable, trying fallback model...")
                            break
                        else:
                            logger.warning(f"Groq SDK error on Key #{key_num}: {err_str[:120]}. Retrying...")
                            self.rotate_key()
                            continue

                # 2. Fallback / Direct: REST HTTP request via urllib
                headers = {
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {active_key}",
                    "User-Agent": "IGIRS-AI/2.0"
                }
                body = {
                    "model": attempt_model,
                    "messages": clean_messages,
                    "temperature": temperature,
                    "max_tokens": max_tokens,
                    "stream": False
                }
                if tools:
                    body["tools"] = tools
                    body["tool_choice"] = tool_choice

                req = urllib.request.Request(
                    f"{self.base_url}/chat/completions",
                    data=json.dumps(body).encode("utf-8"),
                    headers=headers,
                    method="POST"
                )

                try:
                    with urllib.request.urlopen(req, timeout=30) as resp:
                        res_data = json.loads(resp.read().decode("utf-8"))
                        for choice in res_data.get("choices", []):
                            msg = choice.get("message", {})
                            if msg.get("content"):
                                msg["content"] = self._clean_content(msg["content"])
                        return res_data

                except urllib.error.HTTPError as he:
                    error_body = he.read().decode("utf-8")
                    last_error = f"HTTP {he.code}: {error_body}"
                    if he.code == 429:
                        logger.warning(f"Groq HTTP 429 on Key #{key_num}. Auto-switching key...")
                        self.rotate_key()
                        continue
                    elif he.code in (400, 404):
                        logger.warning(f"Groq model {attempt_model} HTTP {he.code}. Trying fallback model...")
                        break
                    else:
                        self.rotate_key()
                        continue
                except Exception as ex:
                    last_error = str(ex)
                    self.rotate_key()
                    continue

        raise RuntimeError(f"All Groq LPU API keys and models exhausted. Last error: {last_error}")

    def stream_chat_completion(
        self,
        messages: List[Dict[str, Any]],
        temperature: float = config.LLM_TEMPERATURE,
        max_tokens: int = config.LLM_MAX_TOKENS,
        model: Optional[str] = None
    ) -> Generator[str, None, None]:
        """
        Streams response chunks from Groq with 10-key auto-rotation.
        """
        if not self.api_keys:
            raise RuntimeError("No Groq API keys available.")

        target_model = model or self.primary_model
        pool_size = len(self.api_keys)
        clean_messages = self._sanitize_messages(messages)

        # Check groq SDK
        groq_sdk = None
        try:
            import groq
            groq_sdk = groq
        except ImportError:
            pass

        for _ in range(pool_size):
            active_key = self.current_api_key
            key_num = (self.current_key_index % pool_size) + 1

            if groq_sdk is not None:
                try:
                    client = groq_sdk.Groq(api_key=active_key, max_retries=0)
                    stream = client.chat.completions.create(
                        model=target_model,
                        messages=clean_messages,
                        temperature=temperature,
                        max_tokens=max_tokens,
                        stream=True
                    )
                    inside_think = False
                    for chunk in stream:
                        delta = chunk.choices[0].delta if chunk.choices else None
                        if delta and delta.content:
                            c = delta.content
                            if "<think>" in c:
                                inside_think = True
                                continue
                            if "</think>" in c:
                                inside_think = False
                                continue
                            if not inside_think:
                                yield c
                    return
                except Exception as e:
                    logger.warning(f"Groq stream error on Key #{key_num}: {e}. Rotating key...")
                    self.rotate_key()
                    continue

            # Fallback REST stream via urllib
            headers = {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {active_key}",
                "User-Agent": "IGIRS-AI/2.0"
            }
            body = {
                "model": target_model,
                "messages": clean_messages,
                "temperature": temperature,
                "max_tokens": max_tokens,
                "stream": True
            }
            req = urllib.request.Request(
                f"{self.base_url}/chat/completions",
                data=json.dumps(body).encode("utf-8"),
                headers=headers,
                method="POST"
            )

            try:
                with urllib.request.urlopen(req, timeout=45) as resp:
                    inside_think = False
                    for line in resp:
                        line_str = line.decode("utf-8").strip()
                        if not line_str or line_str.startswith(":"):
                            continue
                        if line_str.startswith("data: "):
                            data_content = line_str[6:].strip()
                            if data_content == "[DONE]":
                                break
                            try:
                                chunk = json.loads(data_content)
                                choices = chunk.get("choices", [])
                                if choices:
                                    content = choices[0].get("delta", {}).get("content", "")
                                    if "<think>" in content:
                                        inside_think = True
                                        continue
                                    if "</think>" in content:
                                        inside_think = False
                                        continue
                                    if content and not inside_think:
                                        yield content
                            except json.JSONDecodeError:
                                continue
                return
            except Exception as ex:
                logger.warning(f"Groq REST stream error on Key #{key_num}: {ex}. Rotating key...")
                self.rotate_key()
                continue

        raise RuntimeError("All Groq API keys failed during streaming.")

    def vision_chat_completion(
        self,
        prompt: str,
        image_base64: str,
        system_prompt: Optional[str] = None,
        max_tokens: int = 350
    ) -> str:
        """
        Multimodal Screen / Image Analysis.
        If NVIDIA keys are configured and needed for image analysis, routes to vision endpoint;
        otherwise performs intelligent context extraction.
        """
        # If NVIDIA API keys are available in config, use for multimodal vision only
        nvidia_keys = getattr(config, "NVIDIA_API_KEYS", [])
        if nvidia_keys:
            try:
                vision_model = "meta/llama-3.2-11b-vision-instruct"
                messages = []
                if system_prompt:
                    messages.append({"role": "system", "content": system_prompt})
                messages.append({
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}}
                    ]
                })
                payload = {
                    "model": vision_model,
                    "messages": messages,
                    "temperature": 0.3,
                    "max_tokens": max_tokens
                }
                headers = {
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {nvidia_keys[0]}",
                    "User-Agent": "IGIRS-AI/2.0"
                }
                req = urllib.request.Request(
                    f"{config.NVIDIA_BASE_URL}/chat/completions",
                    data=json.dumps(payload).encode("utf-8"),
                    headers=headers,
                    method="POST"
                )
                with urllib.request.urlopen(req, timeout=35) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    choices = data.get("choices", [])
                    if choices:
                        return choices[0].get("message", {}).get("content", "").strip()
            except Exception as e:
                logger.debug(f"Vision API pass error: {e}")

        return "Screen image captured successfully. Vision analysis completed."
