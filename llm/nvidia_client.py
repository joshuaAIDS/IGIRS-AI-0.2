"""
LLM Client Gateway for IGIRS AI.
Redirects to 100% Groq LPU engine with 10-Key Auto-Rotation.
Provides backward-compatibility aliases for existing imports.
"""
import logging
from llm.groq_client import GroqLLMClient

logger = logging.getLogger("IGIRS.LLM")

# Alias NvidiaLLMClient to GroqLLMClient so all existing callers use Groq exclusively
class NvidiaLLMClient(GroqLLMClient):
    def __init__(self, *args, **kwargs):
        super().__init__()
        logger.info("NvidiaLLMClient initialized -> routed 100% to Groq LPU Engine.")

__all__ = ["GroqLLMClient", "NvidiaLLMClient"]
