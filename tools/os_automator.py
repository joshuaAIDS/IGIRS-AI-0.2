"""
J.A.R.V.I.S. Windows OS & File Automator.
Provides hands-free desktop control: file searching, folder creation, reading files,
process management, drive storage telemetry, and recycle bin maintenance.
"""
import os
import sys
import glob
import ctypes
import logging
from pathlib import Path
from typing import Dict, Any, List, Optional
import psutil

import config

logger = logging.getLogger("IGIRS.OSAutomator")

# Protected Windows System Processes that must NEVER be terminated
PROTECTED_PROCESSES = {
    "explorer.exe", "svchost.exe", "csrss.exe", "lsass.exe", "winlogon.exe",
    "dwm.exe", "smss.exe", "services.exe", "system", "system idle process",
    "wininit.exe", "spoolsv.exe", "fontdrvhost.exe"
}

def get_user_common_dirs() -> List[Path]:
    """Returns accessible standard user directories."""
    home = Path.home()
    dirs = [
        home / "Desktop",
        home / "OneDrive" / "Desktop",
        home / "Downloads",
        home / "Documents",
        home / "OneDrive" / "Documents",
        home / "Music",
        home / "Videos",
        config.BASE_DIR
    ]
    valid_dirs = []
    seen = set()
    for d in dirs:
        resolved = str(d.resolve()).lower() if d.exists() else None
        if resolved and resolved not in seen:
            seen.add(resolved)
            valid_dirs.append(d)
    return valid_dirs


class OSAutomator:
    def __init__(self):
        self.user_dirs = get_user_common_dirs()

    def manage_files(self, action: str, query: str = "", path: str = "", content: str = "", max_results: int = 15) -> Dict[str, Any]:
        """
        Manages local files and directories.
        Actions: search, create_folder, create_file, read_file, open_file
        """
        action = (action or "").lower().strip()

        # 1. Search Files
        if action in ["search", "find"]:
            if not query:
                return {"status": "error", "message": "Search query is required, Sir."}
            
            clean_q = query.strip().lower()
            found_files = []

            for root_dir in self.user_dirs:
                if not root_dir.exists():
                    continue
                try:
                    for entry in root_dir.rglob("*"):
                        if clean_q in entry.name.lower():
                            try:
                                is_dir = entry.is_dir()
                                size_kb = round(entry.stat().st_size / 1024, 1) if not is_dir else 0
                                found_files.append({
                                    "name": entry.name,
                                    "path": str(entry.resolve()),
                                    "is_directory": is_dir,
                                    "size_kb": size_kb,
                                    "parent": entry.parent.name
                                })
                                if len(found_files) >= max_results:
                                    break
                            except (PermissionError, FileNotFoundError):
                                continue
                except Exception as e:
                    logger.debug(f"Search error in {root_dir}: {e}")

                if len(found_files) >= max_results:
                    break

            if found_files:
                spoken = f"I found {len(found_files)} item{'s' if len(found_files) > 1 else ''} matching '{query}', Sir. The first is {found_files[0]['name']}."
            else:
                spoken = f"I scanned your user folders but could not find any files matching '{query}', Sir."

            return {
                "status": "success",
                "action": "search",
                "query": query,
                "total_found": len(found_files),
                "files": found_files,
                "spoken_summary": spoken
            }

        # 2. Create Folder
        elif action in ["create_folder", "mkdir", "new_folder"]:
            target_path = Path(path) if path else (Path.home() / "Desktop" / query)
            if not target_path.is_absolute():
                target_path = Path.home() / "Desktop" / target_path

            try:
                target_path.mkdir(parents=True, exist_ok=True)
                return {
                    "status": "success",
                    "action": "create_folder",
                    "path": str(target_path.resolve()),
                    "spoken_summary": f"Folder '{target_path.name}' created on your Desktop, Sir."
                }
            except Exception as e:
                return {"status": "error", "message": f"Could not create folder: {str(e)}"}

        # 3. Create / Write File
        elif action in ["create_file", "write_file", "new_file"]:
            target_path = Path(path) if path else (Path.home() / "Desktop" / query)
            if not target_path.is_absolute():
                target_path = Path.home() / "Desktop" / target_path

            try:
                target_path.parent.mkdir(parents=True, exist_ok=True)
                with open(target_path, "w", encoding="utf-8") as f:
                    f.write(content or "")
                return {
                    "status": "success",
                    "action": "create_file",
                    "path": str(target_path.resolve()),
                    "spoken_summary": f"File '{target_path.name}' saved successfully, Sir."
                }
            except Exception as e:
                return {"status": "error", "message": f"Failed to create file: {str(e)}"}

        # 4. Read File
        elif action in ["read_file", "read", "view_file"]:
            target_path = Path(path) if path else Path(query)
            if not target_path.is_absolute():
                # Try finding in search roots
                found = None
                for r in self.user_dirs:
                    candidate = r / target_path
                    if candidate.exists():
                        found = candidate
                        break
                if found:
                    target_path = found

            if not target_path.exists() or not target_path.is_file():
                return {"status": "error", "message": f"File '{path or query}' was not found, Sir."}

            try:
                with open(target_path, "r", encoding="utf-8", errors="replace") as f:
                    lines = [f.readline() for _ in range(50)]
                text_content = "".join(lines).strip()
                preview = text_content[:600] + ("..." if len(text_content) > 600 else "")
                return {
                    "status": "success",
                    "action": "read_file",
                    "filename": target_path.name,
                    "path": str(target_path.resolve()),
                    "content": text_content,
                    "preview": preview,
                    "spoken_summary": f"Here is the content of {target_path.name}, Sir."
                }
            except Exception as e:
                return {"status": "error", "message": f"Could not read file: {str(e)}"}

        # 5. Open File in Default App
        elif action in ["open", "open_file", "launch_file"]:
            target_path = Path(path) if path else Path(query)
            if not target_path.is_absolute():
                for r in self.user_dirs:
                    candidate = r / target_path
                    if candidate.exists():
                        target_path = candidate
                        break

            if not target_path.exists():
                return {"status": "error", "message": f"File '{path or query}' does not exist, Sir."}

            try:
                os.startfile(str(target_path.resolve()))
                return {
                    "status": "success",
                    "action": "open_file",
                    "path": str(target_path.resolve()),
                    "spoken_summary": f"Opened {target_path.name} in its default application, Sir."
                }
            except Exception as e:
                return {"status": "error", "message": f"Failed to open file: {str(e)}"}

        return {"status": "error", "message": f"Unrecognized file action: '{action}', Sir."}

    def manage_processes(self, action: str, process_name: str = "") -> Dict[str, Any]:
        """
        Monitors and manages active Windows processes.
        Actions: list_heavy, kill
        """
        action = (action or "").lower().strip()

        # 1. List Heavy Processes
        if action in ["list_heavy", "list", "top", "monitor"]:
            procs = []
            for p in psutil.process_iter(['pid', 'name', 'cpu_percent', 'memory_info']):
                try:
                    mem_mb = round(p.info['memory_info'].rss / (1024 * 1024), 1) if p.info.get('memory_info') else 0
                    procs.append({
                        "pid": p.info['pid'],
                        "name": p.info['name'],
                        "cpu_percent": p.info.get('cpu_percent') or 0.0,
                        "memory_mb": mem_mb
                    })
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    continue

            # Sort by memory descending
            top_memory = sorted(procs, key=lambda x: x['memory_mb'], reverse=True)[:6]

            spoken_apps = ", ".join([f"{p['name']} consuming {int(p['memory_mb'])} megabytes" for p in top_memory[:3]])
            return {
                "status": "success",
                "action": "list_heavy",
                "total_processes": len(procs),
                "top_memory": top_memory,
                "spoken_summary": f"The heaviest active processes are {spoken_apps}, Sir."
            }

        # 2. Kill Process
        elif action in ["kill", "terminate", "close", "stop"]:
            if not process_name:
                return {"status": "error", "message": "Process name is required, Sir."}

            target = process_name.lower().strip()
            if not target.endswith(".exe") and not target.isdigit():
                target_exe = f"{target}.exe"
            else:
                target_exe = target

            # Guard critical Windows system processes
            if target_exe.lower() in PROTECTED_PROCESSES or target.lower() in PROTECTED_PROCESSES:
                return {
                    "status": "blocked",
                    "message": f"I cannot terminate '{target_exe}', Sir. That is a protected Windows system component."
                }

            # Guard current python process
            my_pid = os.getpid()

            killed_count = 0
            killed_names = []

            for p in psutil.process_iter(['pid', 'name']):
                try:
                    if p.info['pid'] == my_pid:
                        continue
                    
                    p_name = p.info['name'].lower()
                    if (target.isdigit() and p.info['pid'] == int(target)) or (p_name == target.lower()) or (p_name == target_exe.lower()):
                        p.terminate()
                        killed_count += 1
                        killed_names.append(p.info['name'])
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    continue

            if killed_count > 0:
                return {
                    "status": "success",
                    "action": "kill",
                    "target": process_name,
                    "killed_count": killed_count,
                    "spoken_summary": f"Terminated {killed_count} instance{'s' if killed_count > 1 else ''} of {killed_names[0]}, Sir."
                }
            else:
                return {
                    "status": "not_found",
                    "message": f"No running processes matching '{process_name}' were found, Sir."
                }

        return {"status": "error", "message": f"Unknown process action: '{action}', Sir."}

    def get_storage_status(self) -> Dict[str, Any]:
        """Scans all fixed disk drives and returns capacity & usage telemetry."""
        drives = []
        total_all_gb = 0
        free_all_gb = 0

        for part in psutil.disk_partitions(all=False):
            if 'fixed' in part.opts or sys.platform == 'win32':
                try:
                    usage = psutil.disk_usage(part.mountpoint)
                    total_gb = round(usage.total / (1024 ** 3), 1)
                    used_gb = round(usage.used / (1024 ** 3), 1)
                    free_gb = round(usage.free / (1024 ** 3), 1)
                    percent = usage.percent

                    total_all_gb += total_gb
                    free_all_gb += free_gb

                    drives.append({
                        "device": part.device,
                        "mountpoint": part.mountpoint,
                        "fstype": part.fstype,
                        "total_gb": total_gb,
                        "used_gb": used_gb,
                        "free_gb": free_gb,
                        "percent_used": percent
                    })
                except Exception as e:
                    logger.debug(f"Disk check error for {part.mountpoint}: {e}")

        primary = drives[0] if drives else None
        if primary:
            spoken = f"Drive {primary['device']} has {primary['free_gb']} GB free out of {primary['total_gb']} GB ({primary['percent_used']}% utilized), Sir."
        else:
            spoken = "Drive telemetry is unavailable, Sir."

        return {
            "status": "success",
            "drives": drives,
            "total_capacity_gb": round(total_all_gb, 1),
            "total_free_gb": round(free_all_gb, 1),
            "spoken_summary": spoken
        }

    def empty_recycle_bin(self) -> Dict[str, Any]:
        """Empties the Windows Recycle Bin silently using the Windows Shell API."""
        if sys.platform != "win32":
            return {"status": "error", "message": "Recycle Bin is only supported on Windows, Sir."}

        try:
            # SHEmptyRecycleBinW flags:
            # SHERB_NOCONFIRMATION = 0x00000001
            # SHERB_NOPROGRESSUI   = 0x00000002
            # SHERB_NOSOUND        = 0x00000004
            flags = 0x00000001 | 0x00000002 | 0x00000004
            result = ctypes.windll.shell32.SHEmptyRecycleBinW(None, None, flags)
            
            # S_OK = 0, or E_UNEXPECTED (often when already empty)
            return {
                "status": "success",
                "code": result,
                "spoken_summary": "Windows Recycle Bin has been emptied and storage reclaimed, Sir."
            }
        except Exception as e:
            logger.error(f"Empty Recycle Bin error: {e}")
            return {
                "status": "error",
                "message": f"Could not empty recycle bin: {str(e)}"
            }
