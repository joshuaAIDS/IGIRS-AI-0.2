"""
IGIRS Assistant Controller.
Glues together the LLM Client, Memory Manager, Tool Engine, TTS Voice Engine, STT Listener, and Prompting.
Features smart Intent Routing to prevent hallucinated/unwanted tool calls.
"""
import sys
import re
import json
import logging
from typing import Dict, Any, List, Optional, Callable, Tuple

# Ensure UTF-8 on Windows
if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

import config
from llm.groq_client import GroqLLMClient
from llm.prompts import build_system_prompt
from memory.manager import MemoryManager
from tools.registry import ToolRegistry
from tts import TTSEngine
from stt import VoiceListener, WakeWordDetector

logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(asctime)s - %(name)s: %(message)s")
logger = logging.getLogger("IGIRS.Assistant")

class IGIRSAssistant:
    def __init__(self):
        self.memory = MemoryManager()
        self.llm = GroqLLMClient()
        self.tts = TTSEngine(memory_manager=self.memory)
        self.tools = ToolRegistry(memory_manager=self.memory, llm_client=self.llm, tts_engine=self.tts)
        self.stt = VoiceListener()
        self.wake_word = WakeWordDetector()
        logger.info(f"Initialized {config.ASSISTANT_NAME} for {self.memory.user_name}")

    def get_system_prompt(self) -> str:
        """Constructs current dynamic system prompt."""
        return build_system_prompt(
            user_name=self.memory.user_name,
            facts=self.memory.get_facts()
        )

    def is_stop_listening_intent(self, text: str) -> bool:
        """Detects if user is commanding to stop listening or stop voice mode."""
        t = (text or "").lower().strip()
        stop_patterns = [
            r"\b(stop\s+(listen|listening|voice|always\s+listening|mic|microphone))\b",
            r"\b(turn\s+off\s+(listening|voice|mic))\b",
            r"\b(disable\s+(listening|voice))\b",
            r"\b(mute\s+(mic|microphone))\b",
            r"\b(hands\s*free\s+off)\b"
        ]
        return any(re.search(p, t) for p in stop_patterns) or t in [
            "stop listen", "stop listening", "stop voice", "mute mic", "stop mic", "cancel listening"
        ]

    def route_tools_for_input(self, user_input: str) -> Optional[List[Dict[str, Any]]]:
        """
        Determines if the user input requires tools.
        Returns filtered tool definitions or None if purely conversational.
        """
        text = user_input.lower().strip()

        # Purely conversational patterns that must NEVER trigger tools
        pure_chat_keywords = [
            "hi", "hello", "hey", "how are you", "who are you", "what are you",
            "speak in english", "speck in english", "talk in english",
            "what are you telling", "what are you saying", "what do you mean",
            "thanks", "thank you", "ok", "okay", "cool", "nice", "awesome",
            "good evening", "good night", "bye", "goodbye"
        ]

        # Exact match or simple greeting check
        if text in pure_chat_keywords or any(text == k for k in pure_chat_keywords):
            return None

        # Check for specific tool trigger intents
        selected_tool_names = set()

        # 1. System Telemetry
        if any(w in text for w in ["telemetry", "cpu", "ram usage", "battery", "system status", "computer specs", "system health", "பலகாரம்"]):
            selected_tool_names.add("get_system_telemetry")

        # 2. Time & Date
        if any(w in text for w in ["what time", "current time", "what is the time", "what's the time", "what date", "what day is today", "today's date", "நேரம்", "மணி என்ன"]):
            selected_tool_names.add("get_time_date")

        # 3. App Launch (Requires explicit command verbs like open/launch/start)
        if re.search(r"\b(open|launch|start)\s+(chrome|browser|vscode|code|notepad|calc|calculator|spotify|explorer|cmd|terminal)\b", text):
            selected_tool_names.add("open_application")

        # 4. Manage Notes
        if re.search(r"\b(take a note|take note|save note|add note|write down|my notes|show notes|list notes|clear notes)\b", text):
            selected_tool_names.add("manage_notes")

        # 5. Screen Vision & Multimodal Inspection
        screen_vision_triggers = [
            "screen", "on my screen", "look at my screen", "read my screen", "see my screen",
            "what am i looking at", "debug my screen", "what's on my screen", "what is on my screen",
            "read this error", "debug this error", "what error is this", "inspect screen",
            "inspect window", "read this window", "explain my screen", "read screen", "vision"
        ]
        if any(w in text for w in screen_vision_triggers) or (
            re.search(r"\b(look at|read|debug|inspect|explain|what is|what's)\b", text)
            and any(w in text for w in ["screen", "window", "error", "dialog", "terminal", "page"])
        ):
            selected_tool_names.add("analyze_screen")

        # 6. Media Playback & Live Streaming (YouTube / Spotify / Live Streams)
        media_patterns = [
            r"\b(play|listen to|watch|stream|tune into)\b",
            r"\b(see|show|watch|open|start)\s+(the\s+)?(live|stream|video|broadcast|session)\b",
            r"\blive\s+(stream|broadcast|feed|proceedings|session)\b",
            r"\b(see|watch)\s+the\s+live\b",
        ]
        media_keywords = ["youtube", "spotify", "music", "song", "track", "playlist", "lofi", "lo-fi", "beats", "video", "soundtrack", "live stream", "live session", "assembly live"]
        if any(re.search(p, text) for p in media_patterns) or any(k in text for k in media_keywords) or text.startswith("play "):
            selected_tool_names.add("play_media")
            selected_tool_names.add("play_youtube")
            if any(w in text for w in ["live", "assembly", "parliament", "news", "today"]):
                selected_tool_names.add("get_live_news")
                selected_tool_names.add("web_search")

        # 7. Daily Briefing / Morning Routine
        if any(w in text for w in ["briefing", "daily briefing", "morning briefing", "status report", "brief me", "good morning"]):
            selected_tool_names.add("get_daily_briefing")

        # 8. Remember Fact
        if re.search(r"\b(remember that|my favorite|i live in|my name is)\b", text):
            selected_tool_names.add("remember_user_fact")

        # 9. Web Search (Explicit search commands + smart auto-detection for current events)
        # Use negative lookahead to avoid triggering on "search for file(s)" which is a local OS search
        if re.search(r"\b(search for(?!\s+(any\s+)?(file|files|folder|folders))|search the web|search web|google|look up online)\b", text):
            selected_tool_names.add("web_search")

        # 9b. Live News & Current Events (auto-detect when user needs real-time knowledge)
        news_triggers = [
            "news", "headlines", "latest", "trending", "current events",
            "what's happening", "what is happening", "what happened",
            "breaking news", "today's news", "recent", "update on",
            "score", "match result", "election result", "stock price",
            "who won", "did india win"
        ]
        if any(w in text for w in news_triggers):
            selected_tool_names.add("get_live_news")
            selected_tool_names.add("web_search")

        # 9c. Smart real-world knowledge detection (auto-search for questions about
        #     current people, events, or facts the LLM's training data might not cover)
        real_world_patterns = [
            r"\b(who is the|who is|who won|who became)\b.*\b(president|prime minister|cm|chief minister|ceo|governor|captain|leader)\b",
            r"\b(current|new|latest|recent)\b.*\b(president|pm|cm|ceo|law|policy|update|version|release)\b",
            r"\b(ipl|world cup|olympics|cricket|football|tennis)\b.*\b(score|result|winner|match|schedule)\b",
        ]
        if any(re.search(p, text) for p in real_world_patterns):
            selected_tool_names.add("web_search")

        # --- Phase 1: Hardware & System Controls ---

        # 10. Volume & Sound
        if re.search(r"\b(volume|sound|louder|quieter|turn it up|turn it down|mute|unmute)\b", text):
            if "mute" in text or "unmute" in text:
                selected_tool_names.add("mute_volume")
            elif any(w in text for w in ["what", "check", "how high", "level"]) and "volume" in text:
                selected_tool_names.add("get_volume")
            else:
                selected_tool_names.add("set_volume")
                selected_tool_names.add("change_volume_relative")

        # 11. Screen Brightness
        if re.search(r"\b(brightness|dim the screen|dim screen|brighten screen)\b", text):
            if any(w in text for w in ["what", "check", "level"]) and "brightness" in text:
                selected_tool_names.add("get_brightness")
            else:
                selected_tool_names.add("set_brightness")
                selected_tool_names.add("get_brightness")

        # 12. Screenshot
        if re.search(r"\b(screenshot|capture screen|screen capture|take a snap|snap the screen)\b", text):
            selected_tool_names.add("take_screenshot")

        # 13. Lock Workstation
        if re.search(r"\b(lock (the )?(pc|computer|workstation|laptop|screen)|lock my (pc|computer|laptop))\b", text):
            selected_tool_names.add("lock_workstation")

        # 14. Minimize Windows / Show Desktop
        if re.search(r"\b(minimize\b|show desktop|go to desktop|toggle desktop|clear screen)\b", text):
            selected_tool_names.add("minimize_all_windows")

        # --- Phase 1: Productivity ---

        # 15. Voice Timers & Alarms
        if re.search(r"\b(timer|alarm|countdown|remind me in)\b", text):
            selected_tool_names.add("set_timer")

        # 16. Live Weather & Forecast
        if re.search(r"\b(weather|temperature|forecast|is it raining|will it rain|how hot|how cold)\b", text):
            selected_tool_names.add("get_live_weather")

        # --- Phase 5: Document Intelligence & Knowledge Vault ---
        doc_triggers = [
            "resume", "cv", "curriculum vitae", "lecture notes", "course notes", "study notes",
            "syllabus", "in the pdf", "pdf document", "in the document", "document says",
            "read document", "my files", "knowledge vault", "code file", "source code",
            "in the code", "explain this file", "what does the file say", "summarize the document",
            "summarize my resume", "summarize notes"
        ]
        if any(w in text for w in doc_triggers) or (
            re.search(r"\b(pdf|document|resume|lecture|notes|chapter)\b", text)
            and any(w in text for w in ["what", "how", "summarize", "read", "explain", "find", "who", "tell me"])
        ):
            selected_tool_names.add("query_documents")
            if "summarize" in text or "overview" in text:
                selected_tool_names.add("summarize_document")

        if any(w in text for w in ["list documents", "my documents", "show files", "what documents", "show documents"]):
            selected_tool_names.add("list_indexed_documents")

        # --- Phase 6: WhatsApp, Email & Contacts Triggers ---

        # 16. WhatsApp Messaging
        if any(w in text for w in ["whatsapp", "whats app"]) or re.search(r"\b(send|text|message)\s+(a\s+)?(message|text|whatsapp)\b", text) or "send whatsapp" in text or "on whatsapp" in text:
            selected_tool_names.add("send_whatsapp")

        # 17. Email & AI Drafting
        email_triggers = ["email", "mail", "inbox", "gmail", "outlook"]
        if any(w in text for w in email_triggers):
            if any(w in text for w in ["check", "unread", "new mail", "any mail", "read my mail", "check inbox"]):
                selected_tool_names.add("check_unread_emails")
            elif any(w in text for w in ["draft", "compose", "write an email", "prepare email"]):
                selected_tool_names.add("draft_email")
                selected_tool_names.add("send_email")
            else:
                selected_tool_names.add("send_email")
                selected_tool_names.add("draft_email")

        # 18. Contacts Management
        if any(w in text for w in ["contact", "contacts", "address book", "phone number", "phone no"]) and any(w in text for w in ["add", "save", "list", "show", "get", "find", "who", "delete", "remove", "what is"]):
            selected_tool_names.add("manage_contacts")

        # --- Phase 8: Web Automation, Live Price Intelligence & Scraping Triggers ---

        # 19. Price Checks & Comparison
        if (any(w in text for w in ["price", "cost", "how much is", "how much does", "rate of", "best deal on", "cheapest"]) or 
            (any(w in text for w in ["amazon", "flipkart"]) and any(w in text for w in ["check", "find", "search", "show", "get", "price", "buy"]))):
            selected_tool_names.add("check_product_prices")

        # 20. Web Scraping & Reading
        if any(w in text for w in ["scrape", "extract from url", "read webpage", "read website", "read link", "summarize article", "webpage content"]) or (
            re.search(r"https?://", text) and any(w in text for w in ["read", "scrape", "extract", "what is on", "summarize", "view"])
        ):
            selected_tool_names.add("scrape_webpage")

        # 21. Webpage Screenshot
        has_url = bool(re.search(r"https?://|\.com\b|\.org\b|\.net\b|\.io\b|\.edu\b|wikipedia", text))
        if (any(w in text for w in [
                "screenshot of website", "webpage screenshot", "screenshot of url",
                "capture website", "capture webpage", "screenshot of the website",
                "screenshot of the url", "capture a screenshot of the website",
                "capture a screenshot of the url"
            ]) or
            ("screenshot" in text and has_url) or
            ("capture" in text and "screenshot" in text and has_url)):
            selected_tool_names.add("capture_webpage_screenshot")
            # When user clearly wants a website capture, remove conflicting desktop tools
            if has_url:
                selected_tool_names.discard("take_screenshot")
                selected_tool_names.discard("analyze_screen")

        # --- J.A.R.V.I.S. Executive Protocols & OS Automator Triggers ---

        # 22. Executive Protocols
        if any(w in text for w in ["protocol", "clean slate", "lockdown", "sentry", "diagnostics", "system scan", "hardware scan", "health check"]):
            selected_tool_names.add("execute_protocol")

        # 23. File & Folder Operations
        os_file_context = any(w in text for w in [
            "on my computer", "on my pc", "on my laptop", "on my desktop",
            "in my folders", "in my downloads", "in my documents", "on disk",
            "on my drive", "local file", "local files", "files containing"
        ])
        if (any(w in text for w in [
                "find file", "find files", "search for file", "search for files",
                "search file", "search files", "find my", "where is file",
                "where is the file", "where are the files", "locate file", "locate files",
                "search for any file", "search for any files"
            ]) or
            re.search(r"\b(search|find|look)\b.*\b(file|files)\b", text) or
            re.search(r"\b(create|make|new)\s+(folder|directory)\b", text) or
            re.search(r"\b(create|write|new)\s+(file|document|script)\b", text) or
            re.search(r"\b(read|view|show|display)\s+(file|document)\b", text) or
            re.search(r"\b(open|launch)\s+(file|document|pdf|image)\b", text) or
            os_file_context):
            selected_tool_names.add("manage_files")
            # If user clearly means OS file search (not document RAG or web search), remove conflicting tools
            if os_file_context or re.search(r"\b(search|find|look)\b.*\b(file|files)\b", text):
                selected_tool_names.discard("query_documents")
                selected_tool_names.discard("summarize_document")
                selected_tool_names.discard("web_search")

        # 24. Process & Task Manager
        if (any(w in text for w in ["heavy process", "running process", "task manager", "what apps are running", "resource usage", "heavy tasks"]) or
            re.search(r"\b(kill|terminate|force close|force stop)\s+(process|app|application|task|chrome|notepad|code|spotify|browser)\b", text) or
            re.search(r"\b(kill|terminate)\s+[a-zA-Z0-9_-]+\b", text)):
            selected_tool_names.add("manage_processes")

        # 25. Disk & Storage Telemetry
        if any(w in text for w in ["storage", "disk space", "free space", "drive space", "how much storage", "hard drive", "ssd space", "disk usage"]):
            selected_tool_names.add("get_storage_status")

        # 26. Recycle Bin Maintenance
        if any(w in text for w in ["empty recycle bin", "clean recycle bin", "clear recycle bin", "purge recycle bin", "empty trash", "clean trash"]):
            selected_tool_names.add("empty_recycle_bin")

        # 27. Memory & Learning Facts
        if any(w in text for w in ["remember that", "remember this", "keep in mind", "learn that", "note that", "save fact", "store fact", "record fact", "correct answer is"]):
            selected_tool_names.add("remember_fact")

        if not selected_tool_names:
            return None

        # Return only the relevant tool schemas
        all_tools = self.tools.get_tool_definitions()
        filtered = [t for t in all_tools if t["function"]["name"] in selected_tool_names]
        return filtered if filtered else None

    def _parse_tool_calls_from_content(self, content: str) -> Optional[List[Dict[str, Any]]]:
        """
        Detects and extracts tool calls if the LLM outputted them as content text
        (e.g. Markdown code blocks, XML tags, or raw JSON) instead of native tool_calls.
        """
        if not content:
            return None

        alias_map = {
            "analyze_screenshot": "analyze_screen",
            "screen_vision": "analyze_screen",
            "search_files": "manage_files",
            "find_files": "manage_files",
            "play_music": "play_media",
            "open_app": "open_application",
            "web_screenshot": "capture_webpage_screenshot",
            "screenshot_website": "capture_webpage_screenshot",
        }

        # 1. XML parameter style: <tool_call> <function=name> <parameter=k>v</parameter> ... </function> </tool_call>
        xml_match = re.search(r"<tool_call>[\s\S]*?<function[=\s]+(\w+)>([\s\S]*?)</function>[\s\S]*?</tool_call>", content)
        if xml_match:
            fn_name = xml_match.group(1)
            params_str = xml_match.group(2)
            args = {}
            for pm in re.finditer(r"<parameter[=\s]+(\w+)>(.*?)</parameter>", params_str, re.DOTALL):
                val = pm.group(2).strip()
                if val.lower() == "true":
                    args[pm.group(1)] = True
                elif val.lower() == "false":
                    args[pm.group(1)] = False
                elif val.isdigit():
                    args[pm.group(1)] = int(val)
                else:
                    args[pm.group(1)] = val
            real_name = alias_map.get(fn_name, fn_name)
            return [{"id": "call_parsed_xml", "type": "function", "function": {"name": real_name, "arguments": json.dumps(args)}}]

        # 2. XML JSON style: <tool_call> { ... } </tool_call>
        xml_json = re.search(r"<tool_call>\s*(\{[\s\S]*?\})\s*</tool_call>", content)
        if xml_json:
            try:
                data = json.loads(xml_json.group(1))
                if isinstance(data, dict) and ("name" in data or "function" in data):
                    fn_name = data.get("name") or data.get("function")
                    args = data.get("arguments") or data.get("parameters") or {}
                    real_name = alias_map.get(fn_name, fn_name)
                    return [{"id": "call_parsed_xml_json", "type": "function", "function": {"name": real_name, "arguments": json.dumps(args) if isinstance(args, dict) else str(args)}}]
            except Exception:
                pass

        # 3. Markdown JSON code block: ```json { ... } ```
        md_json = re.search(r"```(?:json)?\s*(\{[\s\S]*?\})\s*```", content)
        if md_json:
            try:
                data = json.loads(md_json.group(1))
                if isinstance(data, dict) and ("name" in data or "function" in data or "action" in data):
                    fn_name = data.get("name") or data.get("function") or data.get("action")
                    args = data.get("arguments") or data.get("parameters") or {}
                    real_name = alias_map.get(fn_name, fn_name)
                    return [{"id": "call_parsed_md", "type": "function", "function": {"name": real_name, "arguments": json.dumps(args) if isinstance(args, dict) else str(args)}}]
            except Exception:
                pass

        # 4. Bare JSON: { "name": ..., "arguments": ... }
        stripped = content.strip()
        if stripped.startswith("{") and stripped.endswith("}"):
            try:
                data = json.loads(stripped)
                if isinstance(data, dict) and ("name" in data or "function" in data or "action" in data):
                    fn_name = data.get("name") or data.get("function") or data.get("action")
                    args = data.get("arguments") or data.get("parameters") or {}
                    real_name = alias_map.get(fn_name, fn_name)
                    return [{"id": "call_parsed_bare", "type": "function", "function": {"name": real_name, "arguments": json.dumps(args) if isinstance(args, dict) else str(args)}}]
            except Exception:
                pass

        return None

    def process_message(
        self,
        user_input: str,
        on_tool_call: Optional[Callable[[str, Dict[str, Any]], None]] = None,
        on_tool_result: Optional[Callable[[str, str], None]] = None,
        speak_response: bool = True
    ) -> str:
        """
        Processes a user message through the LLM, tool loop, and TTS engine.
        """
        user_input = user_input.strip()
        if not user_input:
            return ""

        # Stop prior speech upon new user input
        self.tts.stop()

        # Handle direct "stop listening" commands immediately
        if self.is_stop_listening_intent(user_input):
            if getattr(config, "JARVIS_MODE", False):
                honorific = getattr(config, "JARVIS_HONORIFIC", "Sir")
                stop_reply = f"Standing by, {honorific}. Call me whenever you require assistance."
            else:
                user_first = self.memory.user_name.split()[0] if self.memory.user_name else ""
                name_clause = f", {user_first}" if user_first else ""
                stop_reply = f"Got it{name_clause}, I'll stop listening. Tap the mic or say hey whenever you want to talk!"
            self.memory.add_user_message(user_input)
            self.memory.add_assistant_message(stop_reply)
            if speak_response:
                self.tts.speak(stop_reply)
            return stop_reply

        # 1. Add User message to memory
        self.memory.add_user_message(user_input)

        # 2. Prepare messages and tools for LLM
        system_prompt = self.get_system_prompt()
        messages = self.memory.get_messages_for_llm(system_prompt)
        
        # Route tools intelligently
        active_tools = self.route_tools_for_input(user_input)

        # Contextual follow-up detection: If no tools routed but user is confirming
        # a prior suggestion (e.g. "yes do it", "go ahead", "sure", "do it"),
        # scan recent history for tool context and re-route appropriate tools.
        if active_tools is None:
            confirm_phrases = [
                "yes", "yes do it", "do it", "go ahead", "sure", "proceed",
                "yeah", "yep", "yup", "yes please", "ok do it", "okay do it",
                "go for it", "make it happen", "yes go ahead", "do that",
                "yeah do it", "sure do it", "yes sure", "absolutely"
            ]
            text_lower = user_input.lower().strip()
            if text_lower in confirm_phrases or re.match(r"^(yes|yeah|yep|sure|ok|okay|go ahead|do it|proceed)\b", text_lower):
                # Scan the last few assistant messages for tool/action context
                context_tools = set()
                for msg in reversed(self.memory.history[-6:]):
                    if msg.get("role") != "assistant":
                        continue
                    prev_content = (msg.get("content") or "").lower()
                    # Detect if assistant previously mentioned analyzing/screenshot/screen
                    if any(w in prev_content for w in ["analyze", "analyse", "screen", "screenshot", "inspect", "vision"]):
                        context_tools.add("analyze_screen")
                    if any(w in prev_content for w in ["play", "youtube", "spotify", "music", "song"]):
                        context_tools.add("play_media")
                        context_tools.add("play_youtube")
                    if any(w in prev_content for w in ["search", "find", "look up", "google"]):
                        context_tools.add("web_search")
                    if any(w in prev_content for w in ["file", "folder", "document", "resume"]):
                        context_tools.add("manage_files")
                    if any(w in prev_content for w in ["whatsapp", "message"]):
                        context_tools.add("send_whatsapp")
                    if any(w in prev_content for w in ["email", "mail", "draft"]):
                        context_tools.add("send_email")
                        context_tools.add("draft_email")
                    if any(w in prev_content for w in ["timer", "remind", "alarm"]):
                        context_tools.add("set_timer")
                    if any(w in prev_content for w in ["capture", "webpage screenshot", "website screenshot"]):
                        context_tools.add("capture_webpage_screenshot")
                    if context_tools:
                        break
                if context_tools:
                    all_tools = self.tools.get_tool_definitions()
                    active_tools = [t for t in all_tools if t["function"]["name"] in context_tools]
                    if not active_tools:
                        active_tools = None

        # 3. Call LLM
        response_data = self.llm.chat_completion(
            messages=messages,
            tools=active_tools
        )

        choices = response_data.get("choices", [])
        if not choices:
            fallback_msg = "I'm sorry, I couldn't generate a response."
            self.memory.add_assistant_message(fallback_msg)
            if speak_response:
                self.tts.speak(fallback_msg)
            return fallback_msg

        message = choices[0].get("message", {})
        tool_calls = message.get("tool_calls")
        content = message.get("content") or ""

        # Auto-recover tool call if LLM outputted it in content instead of API tool_calls
        if not tool_calls:
            parsed_calls = self._parse_tool_calls_from_content(content)
            if parsed_calls:
                tool_calls = parsed_calls
                content = ""

        # 4. Handle Tool Calling if invoked
        if tool_calls:
            self.memory.add_assistant_message(content, tool_calls=tool_calls)

            tool_output = ""
            last_fn_name = ""
            for tool_call in tool_calls:
                call_id = tool_call.get("id", "call_default")
                function = tool_call.get("function", {})
                fn_name = function.get("name", "")
                raw_args = function.get("arguments", "{}")
                
                try:
                    args = json.loads(raw_args) if isinstance(raw_args, str) else raw_args
                except Exception:
                    args = {}

                if on_tool_call:
                    on_tool_call(fn_name, args)

                # Execute tool
                tool_output = self.tools.execute_tool(fn_name, args)
                last_fn_name = fn_name

                if on_tool_result:
                    on_tool_result(fn_name, tool_output)

                # Add tool result to conversation history
                self.memory.add_tool_response(call_id, fn_name, tool_output)

            # If single-turn complete tools were called, output is already formatted
            single_turn_tools = [
                "analyze_screen", "analyze_screenshot", "query_documents", "summarize_document",
                "send_whatsapp", "send_email", "draft_email", "check_unread_emails", "manage_contacts",
                "check_product_prices", "scrape_webpage", "capture_webpage_screenshot",
                "manage_files", "execute_protocol", "manage_processes", "get_storage_status",
                "empty_recycle_bin", "manage_notes", "get_time_date", "get_system_telemetry",
                "set_volume", "change_volume_relative", "set_brightness", "change_brightness_relative",
                "take_screenshot", "open_application"
            ]
            if len(tool_calls) == 1 and tool_calls[0].get("function", {}).get("name") in single_turn_tools:
                final_content = tool_output
                try:
                    parsed_res = json.loads(tool_output)
                    if isinstance(parsed_res, dict):
                        # Format file search results cleanly
                        if last_fn_name == "manage_files" and parsed_res.get("action") == "search":
                            results = parsed_res.get("results", [])
                            if results:
                                lines = [parsed_res.get("spoken_summary", f"Found {len(results)} matching files:")]
                                for r in results[:8]:
                                    lines.append(f"- **{r.get('name')}** (`{r.get('path')}`)")
                                final_content = "\n".join(lines)
                            else:
                                final_content = parsed_res.get("spoken_summary", "No matching files found.")
                        else:
                            final_content = (
                                parsed_res.get("summary")
                                or parsed_res.get("spoken_summary")
                                or parsed_res.get("message")
                                or parsed_res.get("answer")
                                or tool_output
                            )
                except Exception:
                    pass

                self.memory.add_assistant_message(final_content)
                if speak_response:
                    spoken = self.get_spoken_summary(final_content)
                    self.tts.speak(spoken)
                return final_content

            # 5. Call LLM again to get final answer incorporating tool outputs
            second_messages = self.memory.get_messages_for_llm(system_prompt)
            second_response = self.llm.chat_completion(
                messages=second_messages,
                tools=None
            )

            second_choices = second_response.get("choices", [])
            if second_choices:
                final_content = second_choices[0].get("message", {}).get("content", "")
            else:
                final_content = "Tool executed successfully."

            self.memory.add_assistant_message(final_content)
            if speak_response:
                spoken = self.get_spoken_summary(final_content)
                self.tts.speak(spoken)
            return final_content

        else:
            # Sanitize any hallucinated raw JSON tool call from LLM content
            content_cleaned = content.strip()
            if content_cleaned.startswith("{") and content_cleaned.endswith("}"):
                try:
                    parsed = json.loads(content_cleaned)
                    if isinstance(parsed, dict) and ("name" in parsed or "function" in parsed or "action" in parsed or "tool" in parsed):
                        tool_fn = parsed.get("name") or parsed.get("function") or parsed.get("action")
                        if tool_fn in ["stop_listening", "stop_listen", "stop_voice", "mute"]:
                            content = f"I've stopped listening, {self.memory.user_name}. Tap the mic whenever you need me!"
                        elif tool_fn == "play_media":
                            args = parsed.get("parameters") or parsed.get("arguments") or {}
                            content = self.tools.execute_tool("play_media", args)
                        elif tool_fn == "analyze_screen":
                            args = parsed.get("parameters") or parsed.get("arguments") or {}
                            content = self.tools.execute_tool("analyze_screen", args)
                        else:
                            content = f"All set, {self.memory.user_name}!"
                except Exception:
                    pass

            # Sanitize hallucinated XML/tag-based tool calls (e.g. <tool_call>, <function=...>)
            if re.search(r"<tool_call>|<function[=\s]|</tool_call>|</function>", content_cleaned):
                # Extract function name and parameters from XML-style hallucination
                xml_fn_match = re.search(r"<function[=\s]+(\w+)>|function=\"?(\w+)\"?", content_cleaned)
                xml_params = {}
                if xml_fn_match:
                    xml_fn_name = xml_fn_match.group(1) or xml_fn_match.group(2)
                    # Extract parameters from <parameter=key>value</parameter> patterns
                    for pm in re.finditer(r"<parameter[=\s]+(\w+)>(.*?)</parameter>", content_cleaned, re.DOTALL):
                        xml_params[pm.group(1)] = pm.group(2).strip()
                    # Map hallucinated names to real tool names
                    fn_map = {
                        "analyze_screenshot": "analyze_screen",
                        "analyze_screen": "analyze_screen",
                        "screen_vision": "analyze_screen",
                        "play_media": "play_media",
                        "play_youtube": "play_youtube",
                        "web_search": "web_search",
                        "manage_files": "manage_files",
                        "search_files": "manage_files",
                        "take_screenshot": "take_screenshot",
                    }
                    real_fn = fn_map.get(xml_fn_name, xml_fn_name)
                    if real_fn in self.tools.handlers:
                        try:
                            tool_output = self.tools.execute_tool(real_fn, xml_params)
                            content = tool_output
                            # Try extracting a summary from JSON output
                            try:
                                parsed_res = json.loads(tool_output)
                                if isinstance(parsed_res, dict):
                                    content = parsed_res.get("summary") or parsed_res.get("message") or parsed_res.get("answer") or tool_output
                            except Exception:
                                pass
                        except Exception:
                            content = f"I tried to run that for you but hit an issue. Could you rephrase your request, {self.memory.user_name}?"
                    else:
                        content = f"I wasn't sure how to handle that. Could you rephrase, {self.memory.user_name}?"
                else:
                    content = f"I wasn't sure how to handle that. Could you rephrase, {self.memory.user_name}?"

            # Direct text response
            self.memory.add_assistant_message(content)
            if speak_response:
                spoken = self.get_spoken_summary(content)
                self.tts.speak(spoken)
            return content

    def get_spoken_summary(self, text: str) -> str:
        """
        Extracts a clean, natural conversational spoken summary for voice playback.
        Ensures the UI displays the complete, rich response while voice output stays brisk and engaging.
        """
        if not text:
            return ""
        import re
        clean = re.sub(r"```[\s\S]*?```", " [code displayed on screen] ", text)
        clean = re.sub(r"\|[^\n]+\|", "", clean)
        clean = re.sub(r"[#*`_~]", "", clean).strip()

        paragraphs = [p.strip() for p in clean.split("\n\n") if p.strip()]
        if not paragraphs:
            paragraphs = [p.strip() for p in clean.split("\n") if p.strip()]

        first = paragraphs[0] if paragraphs else clean
        if len(first) > 300:
            sentences = re.split(r'(?<=[.!?])\s+', first)
            first = " ".join(sentences[:2]) if len(sentences) > 1 else first[:300]
        return first.strip()

    def listen_and_respond(
        self,
        timeout: int = 6,
        phrase_time_limit: int = 15,
        on_listening: Optional[Callable[[], None]] = None,
        on_transcribing: Optional[Callable[[], None]] = None,
        on_tool_call: Optional[Callable[[str, Dict[str, Any]], None]] = None,
        on_tool_result: Optional[Callable[[str, str], None]] = None,
        speak_response: bool = True
    ) -> Tuple[Optional[str], Optional[str]]:
        """
        Listens to the microphone, transcribes speech, and processes the response.
        Returns (transcribed_user_text, assistant_reply_text).
        """
        transcribed_text = self.stt.listen_and_transcribe(
            timeout=timeout,
            phrase_time_limit=phrase_time_limit,
            on_listening=on_listening,
            on_transcribing=on_transcribing
        )

        if not transcribed_text:
            return None, None

        # Process message through AI assistant
        reply = self.process_message(
            user_input=transcribed_text,
            on_tool_call=on_tool_call,
            on_tool_result=on_tool_result,
            speak_response=speak_response
        )

        return transcribed_text, reply
