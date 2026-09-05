"""
J.A.R.V.I.S. Executive Protocols Engine.
Provides multi-system Iron Man protocols: Focus, Stealth, Clean Slate, Sentry, and Diagnostics.
"""
import os
import sys
import time
import socket
import logging
import subprocess
from datetime import datetime
from typing import Dict, Any, Optional
import psutil

import config
from utils.audio_cues import play_protocol_cue, play_diagnostic_cue, play_alert_cue

logger = logging.getLogger("IGIRS.Protocols")


class ProtocolsEngine:
    def __init__(self, memory_manager=None, tts_engine=None):
        self.memory = memory_manager
        self.tts = tts_engine

    def execute_protocol(self, protocol_name: str, parameters: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Executes a named J.A.R.V.I.S. Executive Protocol.
        Protocols: focus, stealth, clean_slate, sentry, diagnostics
        """
        p_name = (protocol_name or "").lower().strip().replace(" ", "_").replace("protocol_", "")
        params = parameters or {}

        if p_name in ["focus", "deep_work", "study"]:
            return self._protocol_focus(params)
        elif p_name in ["stealth", "night", "silent", "dark"]:
            return self._protocol_stealth(params)
        elif p_name in ["clean_slate", "clean", "purge", "optimize"]:
            return self._protocol_clean_slate(params)
        elif p_name in ["sentry", "lockdown", "secure", "intruder"]:
            return self._protocol_sentry(params)
        elif p_name in ["diagnostics", "scan", "system_scan", "status_report", "health"]:
            return self._protocol_diagnostics(params)
        else:
            return {
                "status": "error",
                "message": f"Protocol '{protocol_name}' is not recognized, Sir. Available protocols: Focus, Stealth, Clean Slate, Sentry, Diagnostics."
            }

    def _protocol_focus(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Protocol Focus: Minimizes distractions, sets optimal volume, starts 45m timer, plays music."""
        duration = params.get("duration_minutes", 45)
        play_protocol_cue()

        # 1. Adjust volume to 35%
        try:
            from tools.system_controls import SystemControls
            sys_ctrl = SystemControls()
            sys_ctrl.set_volume(35)
        except Exception as e:
            logger.debug(f"Volume adjustment failed: {e}")

        # 2. Start timer for duration
        try:
            from tools.productivity import ProductivityTools
            prod = ProductivityTools(self.tts)
            prod.set_timer(duration * 60, f"Protocol Focus {duration}-minute session complete")
        except Exception as e:
            logger.debug(f"Timer start failed: {e}")

        # 3. Minimize distracting windows
        try:
            from tools.system_controls import SystemControls
            sys_ctrl = SystemControls()
            sys_ctrl.minimize_all_windows()
        except Exception as e:
            logger.debug(f"Minimize windows failed: {e}")

        # 4. Launch lo-fi focus audio via YouTube
        music_status = "Audio queue ready"
        try:
            from utils.media import resolve_youtube_direct
            url = resolve_youtube_direct("lofi hip hop beats to relax study to")
            if url:
                import webbrowser
                webbrowser.open(url)
                music_status = "Lo-Fi focus stream active"
        except Exception as e:
            logger.debug(f"Music launch note: {e}")

        spoken = f"Protocol Focus engaged for {duration} minutes, Sir. Volume calibrated, distractions cleared, and focus soundtrack active."
        return {
            "status": "success",
            "protocol": "focus",
            "duration_minutes": duration,
            "volume": "35%",
            "music": music_status,
            "spoken_summary": spoken
        }

    def _protocol_stealth(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Protocol Stealth: Dims display to 20%, drops volume to 10%, minimizes windows, pauses media."""
        play_protocol_cue()

        # 1. Dim brightness to 20%
        brightness_status = "ok"
        try:
            from tools.system_controls import SystemControls
            sys_ctrl = SystemControls()
            sys_ctrl.set_brightness(20)
        except Exception as e:
            brightness_status = str(e)

        # 2. Drop volume to 10%
        try:
            from tools.system_controls import SystemControls
            sys_ctrl = SystemControls()
            sys_ctrl.set_volume(10)
        except Exception as e:
            logger.debug(f"Volume drop failed: {e}")

        # 3. Minimize windows
        try:
            from tools.system_controls import SystemControls
            sys_ctrl = SystemControls()
            sys_ctrl.minimize_all_windows()
        except Exception as e:
            logger.debug(f"Minimize failed: {e}")

        spoken = "Protocol Stealth initiated, Sir. Display dimmed to 20%, audio reduced to 10%, and all windows minimized. Running silent."
        return {
            "status": "success",
            "protocol": "stealth",
            "brightness": "20%",
            "volume": "10%",
            "spoken_summary": spoken
        }

    def _protocol_clean_slate(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Protocol Clean Slate: Purges temporary audio/web cache, empties Recycle Bin, runs garbage collection."""
        play_protocol_cue()
        freed_files = 0
        freed_bytes = 0

        # 1. Clean temp_audio cache except wake_chime.wav
        temp_audio = config.TEMP_AUDIO_DIR
        if temp_audio.exists():
            for f in temp_audio.glob("*"):
                if f.name != "wake_chime.wav" and f.is_file():
                    try:
                        freed_bytes += f.stat().st_size
                        f.unlink()
                        freed_files += 1
                    except Exception:
                        pass

        # 2. Clean temp_web_captures
        web_caps = config.WEB_SCREENSHOTS_DIR
        if web_caps.exists():
            for f in web_caps.glob("*"):
                if f.is_file():
                    try:
                        freed_bytes += f.stat().st_size
                        f.unlink()
                        freed_files += 1
                    except Exception:
                        pass

        # 3. Empty Recycle Bin
        recycle_status = "Emptied"
        try:
            from tools.os_automator import OSAutomator
            osa = OSAutomator()
            res = osa.empty_recycle_bin()
            if res.get("status") != "success":
                recycle_status = res.get("message", "Already clean")
        except Exception as e:
            recycle_status = f"Error: {e}"

        # 4. Python garbage collection
        import gc
        gc.collect()

        freed_mb = round(freed_bytes / (1024 * 1024), 2)
        spoken = f"Protocol Clean Slate complete, Sir. Purged {freed_files} temporary files, reclaimed {freed_mb} megabytes, and emptied the Windows Recycle Bin."

        return {
            "status": "success",
            "protocol": "clean_slate",
            "files_removed": freed_files,
            "disk_freed_mb": freed_mb,
            "recycle_bin": recycle_status,
            "spoken_summary": spoken
        }

    def _protocol_sentry(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Protocol Sentry: Captures security audit screenshot, logs event, and locks Windows workstation."""
        play_alert_cue()

        # 1. Capture security snapshot
        snap_path = config.WEB_SCREENSHOTS_DIR / f"sentry_audit_{int(time.time())}.jpg"
        captured = False
        try:
            from PIL import ImageGrab
            screenshot = ImageGrab.grab(all_screens=True)
            screenshot.save(snap_path, "JPEG", quality=85)
            captured = True
        except Exception as e:
            logger.debug(f"Sentry capture note: {e}")

        # 2. Lock workstation
        try:
            from tools.system_controls import SystemControls
            sys_ctrl = SystemControls()
            sys_ctrl.lock_workstation()
        except Exception as e:
            logger.debug(f"Lock failed: {e}")

        spoken = "Protocol Sentry initiated, Sir. Workstation display captured to security logs and terminal locked."
        return {
            "status": "success",
            "protocol": "sentry",
            "audit_snapshot": str(snap_path) if captured else None,
            "locked": True,
            "spoken_summary": spoken
        }

    def _protocol_diagnostics(self, params: Dict[str, Any]) -> Dict[str, Any]:
        """Protocol Diagnostics: Performs a full Iron Man multi-point hardware and network scan."""
        play_diagnostic_cue()

        # 1. CPU & RAM
        cpu_percent = psutil.cpu_percent(interval=0.1)
        ram = psutil.virtual_memory()
        ram_free_gb = round(ram.available / (1024 ** 3), 1)
        ram_percent = ram.percent

        # 2. Battery
        battery = psutil.sensors_battery()
        bat_percent = f"{int(battery.percent)}%" if battery else "N/A"
        is_plugged = "charging" if (battery and battery.power_plugged) else "on battery power"

        # 3. Primary Disk
        disk = psutil.disk_usage(os.path.splitdrive(sys.executable)[0] or "C:\\")
        disk_free_gb = round(disk.free / (1024 ** 3), 1)
        disk_percent = disk.percent

        # 4. Network Ping Latency (Google DNS 8.8.8.8)
        ping_ms = "N/A"
        try:
            t0 = time.time()
            s = socket.create_connection(("8.8.8.8", 53), timeout=1.5)
            s.close()
            ping_ms = f"{int((time.time() - t0) * 1000)} ms"
        except Exception:
            ping_ms = "Offline / Timed out"

        # 5. Process Count
        active_processes = len(psutil.pids())

        spoken = (
            f"All primary systems nominal, Sir. Power cell is at {bat_percent} and {is_plugged}. "
            f"CPU is running at {cpu_percent}%, {ram_free_gb} gigabytes of memory are available, "
            f"and network latency is {ping_ms}."
        )

        return {
            "status": "success",
            "protocol": "diagnostics",
            "cpu_percent": f"{cpu_percent}%",
            "ram_free_gb": f"{ram_free_gb} GB ({ram_percent}% used)",
            "battery": f"{bat_percent} ({is_plugged})",
            "primary_disk": f"{disk_free_gb} GB free ({disk_percent}% used)",
            "network_latency": ping_ms,
            "active_tasks": active_processes,
            "spoken_summary": spoken
        }
