"""
NVIDIA NIM LLM Client with Auto-Key Rotation, Model Fallback, and Function Calling.
"""
import json
import logging
import urllib.request
import urllib.error
from typing import List, Dict, Any, Generator, Optional
import config

logger = logging.getLogger("IGIRS.LLM")

class NvidiaLLMClient:
    def __init__(
        self,
        api_keys: Optional[List[str]] = None,
        groq_api_keys: Optional[List[str]] = None,
        base_url: str = config.NVIDIA_BASE_URL
    ):
        self.api_keys = api_keys or config.NVIDIA_API_KEYS
        self.groq_api_keys = groq_api_keys or list(getattr(config, "GROQ_API_KEYS", []))
        self.base_url = base_url.rstrip("/")
        self.current_key_index = 0
        self.current_groq_key_index = 0
        self.primary_model = config.PRIMARY_LLM_MODEL
        self.fallback_models = config.FALLBACK_LLM_MODELS

    @property
    def current_api_key(self) -> str:
        if not self.api_keys:
            raise ValueError("No NVIDIA API keys configured.")
        return self.api_keys[self.current_key_index % len(self.api_keys)]

    def rotate_key(self):
        """Rotate to next available NVIDIA API key."""
        prev = self.current_key_index
        self.current_key_index = (self.current_key_index + 1) % len(self.api_keys)
        logger.warning(f"Rotating NVIDIA API key index from {prev} to {self.current_key_index}")

    @property
    def current_groq_api_key(self) -> Optional[str]:
        if not self.groq_api_keys:
            return None
        return self.groq_api_keys[self.current_groq_key_index % len(self.groq_api_keys)]

    def rotate_groq_key(self):
        """Rotate to next available Groq API key when hitting rate limits."""
        if not self.groq_api_keys:
            return
        prev = self.current_groq_key_index
        self.current_groq_key_index = (self.current_groq_key_index + 1) % len(self.groq_api_keys)
        logger.warning(
            f"⚡ Groq Rate Limit / Error: Rotating Groq API key from #{prev + 1} to #{self.current_groq_key_index + 1} "
            f"(Total pool: {len(self.groq_api_keys)} keys)"
        )

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
        Executes a chat completion with automatic 10-key Groq pool rotation,
        error failover, and secondary fallback to NVIDIA NIM.
        """
        provider = getattr(config, "LLM_PROVIDER", "auto").lower()

        # Check if messages contain multimodal / vision content
        has_vision = any(
            isinstance(m.get("content"), list) and any(
                isinstance(c, dict) and c.get("type") in ["image_url", "image"] for c in m.get("content")
            ) for m in messages if isinstance(m, dict)
        )

        # 1. Primary Engine: Groq Ultra-Fast (Iterates through all keys in rotation pool)
        if provider in ["auto", "groq"] and self.groq_api_keys and not has_vision:
            import groq
            target_model = model or getattr(config, "GROQ_PRIMARY_MODEL", "qwen/qwen3.8-27b")
            groq_pool_size = len(self.groq_api_keys)

            for attempt in range(groq_pool_size):
                active_key = self.current_groq_api_key
                try:
                    client = groq.Groq(api_key=active_key)
                    groq_payload = {
                        "model": target_model,
                        "messages": messages,
                        "temperature": temperature,
                        "max_tokens": max_tokens,
                    }
                    if tools:
                        groq_payload["tools"] = tools
                        groq_payload["tool_choice"] = tool_choice

                    resp = client.chat.completions.create(**groq_payload)
                    data = resp.model_dump()
                    # Clean up thinking tags if any
                    for choice in data.get("choices", []):
                        msg = choice.get("message", {})
                        content = msg.get("content")
                        if content and "<think>" in content and "</think>" in content:
                            import re
                            msg["content"] = re.sub(r"<think>[\s\S]*?</think>", "", content).strip()
                    return data
                except Exception as groq_err:
                    err_str = str(groq_err)
                    logger.warning(
                        f"Groq error on key #{self.current_groq_key_index + 1}/{groq_pool_size} ({err_str[:120]}...). "
                        f"Auto-switching to next Groq key..."
                    )
                    self.rotate_groq_key()
                    continue

            logger.error("All Groq API keys in pool were exhausted. Falling back to NVIDIA NIM...")
            if provider == "groq":
                raise RuntimeError("All Groq API keys in rotation pool exhausted.")

        # 2. Secondary Engine: NVIDIA NIM with Key Rotation
        models_to_try = [model or self.primary_model] + [m for m in self.fallback_models if m != (model or self.primary_model)]
        
        last_error = None
        for attempt_model in models_to_try:
            for _ in range(len(self.api_keys)):
                payload = {
                    "model": attempt_model,
                    "messages": messages,
                    "temperature": temperature,
                    "max_tokens": max_tokens,
                    "stream": False
                }
                if tools:
                    payload["tools"] = tools
                    payload["tool_choice"] = tool_choice

                headers = {
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {self.current_api_key}",
                    "User-Agent": "IGIRS-AI/1.0"
                }

                url = f"{self.base_url}/chat/completions"
                req = urllib.request.Request(
                    url,
                    data=json.dumps(payload).encode("utf-8"),
                    headers=headers,
                    method="POST"
                )

                try:
                    with urllib.request.urlopen(req, timeout=30) as resp:
                        res_data = resp.read().decode("utf-8")
                        return json.loads(res_data)
                except urllib.error.HTTPError as e:
                    error_body = e.read().decode("utf-8")
                    last_error = f"HTTP {e.code} on model {attempt_model}: {error_body}"
                    logger.error(last_error)
                    if e.code in (401, 403, 429):
                        self.rotate_key()
                        continue
                    else:
                        break
                except Exception as ex:
                    last_error = str(ex)
                    logger.error(f"Network error on model {attempt_model}: {ex}")
                    self.rotate_key()
                    continue

        raise RuntimeError(f"All NVIDIA NIM completion attempts failed. Last error: {last_error}")

    def stream_chat_completion(
        self,
        messages: List[Dict[str, Any]],
        temperature: float = config.LLM_TEMPERATURE,
        max_tokens: int = config.LLM_MAX_TOKENS,
        model: Optional[str] = None
    ) -> Generator[str, None, None]:
        """
        Streams response chunks from Groq or NVIDIA NIM API.
        """
        provider = getattr(config, "LLM_PROVIDER", "auto").lower()
        groq_key = getattr(config, "GROQ_API_KEY", "") or os.environ.get("GROQ_API_KEY", "")

        has_vision = any(
            isinstance(m.get("content"), list) and any(
                isinstance(c, dict) and c.get("type") in ["image_url", "image"] for c in m.get("content")
            ) for m in messages if isinstance(m, dict)
        )

        # 1. Primary Streaming Engine: Groq Ultra-Fast (Multi-Key Pool)
        if provider in ["auto", "groq"] and self.groq_api_keys and not has_vision:
            import groq
            target_model = model or getattr(config, "GROQ_PRIMARY_MODEL", "qwen/qwen3.8-27b")
            groq_pool_size = len(self.groq_api_keys)

            for attempt in range(groq_pool_size):
                active_key = self.current_groq_api_key
                try:
                    client = groq.Groq(api_key=active_key)
                    stream = client.chat.completions.create(
                        model=target_model,
                        messages=messages,
                        temperature=temperature,
                        max_tokens=max_tokens,
                        stream=True
                    )
                    for chunk in stream:
                        delta = chunk.choices[0].delta if chunk.choices else None
                        if delta and delta.content:
                            yield delta.content
                    return
                except Exception as groq_err:
                    logger.warning(f"Groq stream error on key #{self.current_groq_key_index + 1}/{groq_pool_size}: {groq_err}. Auto-switching...")
                    self.rotate_groq_key()
                    continue

            logger.warning("All Groq keys in pool failed during stream, falling back to NVIDIA NIM...")
            if provider == "groq":
                raise RuntimeError("All Groq API keys in rotation pool exhausted during stream.")

        models_to_try = [model or self.primary_model] + [m for m in self.fallback_models if m != (model or self.primary_model)]

        for attempt_model in models_to_try:
            for _ in range(len(self.api_keys)):
                payload = {
                    "model": attempt_model,
                    "messages": messages,
                    "temperature": temperature,
                    "max_tokens": max_tokens,
                    "stream": True
                }

                headers = {
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {self.current_api_key}",
                    "User-Agent": "IGIRS-AI/1.0"
                }

                url = f"{self.base_url}/chat/completions"
                req = urllib.request.Request(
                    url,
                    data=json.dumps(payload).encode("utf-8"),
                    headers=headers,
                    method="POST"
                )

                try:
                    with urllib.request.urlopen(req, timeout=45) as resp:
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
                                        delta = choices[0].get("delta", {})
                                        content = delta.get("content", "")
                                        if content:
                                            yield content
                                except json.JSONDecodeError:
                                    continue
                    return
                except urllib.error.HTTPError as e:
                    if e.code in (401, 403, 429):
                        self.rotate_key()
                        continue
                    else:
                        break
                except Exception:
                    self.rotate_key()
                    continue

    def vision_chat_completion(
        self,
        prompt: str,
        image_base64: str,
        system_prompt: Optional[str] = None,
        max_tokens: int = 350
    ) -> str:
        """
        Processes a multimodal vision query with an image using meta/llama-3.2-11b-vision-instruct.
        """
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

        for _ in range(len(self.api_keys)):
            payload = {
                "model": vision_model,
                "messages": messages,
                "temperature": 0.3,
                "max_tokens": max_tokens
            }

            headers = {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self.current_api_key}",
                "User-Agent": "IGIRS-AI/1.0"
            }

            url = f"{self.base_url}/chat/completions"
            req = urllib.request.Request(
                url,
                data=json.dumps(payload).encode("utf-8"),
                headers=headers,
                method="POST"
            )

            try:
                with urllib.request.urlopen(req, timeout=35) as resp:
                    data = json.loads(resp.read().decode("utf-8"))
                    choices = data.get("choices", [])
                    if choices:
                        return choices[0].get("message", {}).get("content", "").strip()
                    return "No visual description returned."
            except urllib.error.HTTPError as e:
                logger.error(f"Vision API HTTP error {e.code}: {e}")
                self.rotate_key()
                continue
            except Exception as ex:
                logger.error(f"Vision network error: {ex}")
                self.rotate_key()
                continue

        return "Could not process image due to network connectivity issues."
