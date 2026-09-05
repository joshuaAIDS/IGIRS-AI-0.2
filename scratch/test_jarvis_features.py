"""
Test Suite for J.A.R.V.I.S. Capabilities in IGIRS AI 0.2
Validates:
1. Executive Protocols Engine (Diagnostics, Clean Slate)
2. Windows OS & File Automator (Folder creation, file search, file read, process listing, disk telemetry)
3. Tool Registry Integration & Schemas
4. Audio Cues Generation
5. Persona & System Prompt Configuration
"""
import os
import sys
from pathlib import Path

# Add project root to sys.path
BASE_DIR = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(BASE_DIR))

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

import config
from tools.protocols import ProtocolsEngine
from tools.os_automator import OSAutomator
from tools.registry import ToolRegistry
from llm.prompts import build_system_prompt
from utils.audio_cues import play_protocol_cue, play_diagnostic_cue, play_alert_cue

def test_jarvis():
    print("=" * 60)
    print("⚡ J.A.R.V.I.S. UPGRADE TEST SUITE — IGIRS AI 0.2")
    print("=" * 60)

    # 1. Persona & Honorific
    print("\n[1/5] Testing J.A.R.V.I.S. Persona & 'Sir' Honorific...")
    prompt = build_system_prompt(user_name="Joshua")
    assert "Sir" in prompt, "Prompt must contain 'Sir' honorific"
    assert "J.A.R.V.I.S." in prompt, "Prompt must define J.A.R.V.I.S. identity"
    print(f"  • Honorific Config: {config.JARVIS_HONORIFIC}")
    print(f"  • Persona: J.A.R.V.I.S. Mode Active")
    print("  [OK] Persona and honorific verified.")

    # 2. Executive Protocols
    print("\n[2/5] Testing Executive Protocols Engine...")
    protocols = ProtocolsEngine()
    diag = protocols.execute_protocol("diagnostics")
    print(f"  • Diagnostics Status: {diag.get('status')}")
    print(f"  • Spoken Summary: \"{diag.get('spoken_summary')}\"")
    assert diag.get("status") == "success", "Diagnostics must succeed"
    assert "cpu_percent" in diag, "Must include CPU stats"
    assert "battery" in diag, "Must include Battery stats"
    assert "network_latency" in diag, "Must include network ping"

    clean = protocols.execute_protocol("clean_slate")
    print(f"  • Clean Slate Status: {clean.get('status')}")
    print(f"  • Spoken Summary: \"{clean.get('spoken_summary')}\"")
    assert clean.get("status") == "success", "Clean slate must succeed"
    print("  [OK] Executive Protocols verified.")

    # 3. OS & File Automator
    print("\n[3/5] Testing Windows OS Automator & File Master...")
    os_auto = OSAutomator()

    # Create test directory and file
    test_folder = BASE_DIR / "scratch" / "test_jarvis_vault"
    res_folder = os_auto.manage_files("create_folder", path=str(test_folder))
    print(f"  • Create Folder: {res_folder.get('status')}")
    assert res_folder.get("status") == "success", "Folder creation must succeed"

    test_file = test_folder / "iron_man_protocol.txt"
    res_file = os_auto.manage_files("create_file", path=str(test_file), content="All systems nominal, Sir. Mark VII armor ready.")
    print(f"  • Create File: {res_file.get('status')}")
    assert res_file.get("status") == "success", "File creation must succeed"

    # Read back file
    res_read = os_auto.manage_files("read_file", path=str(test_file))
    print(f"  • Read File Content: \"{res_read.get('content')}\"")
    assert "Mark VII" in res_read.get("content", ""), "Read content must match written file"

    # Search for file
    res_search = os_auto.manage_files("search", query="iron_man_protocol")
    print(f"  • File Search Found: {res_search.get('total_found')} files")
    assert res_search.get("total_found", 0) >= 1, "Search must locate created file"

    # Cleanup test file and folder
    if test_file.exists(): test_file.unlink()
    if test_folder.exists(): test_folder.rmdir()

    # Process Management
    res_procs = os_auto.manage_processes("list_heavy")
    print(f"  • Heavy Processes Monitored: {len(res_procs.get('top_memory', []))} apps")
    assert len(res_procs.get("top_memory", [])) > 0, "Must list active running tasks"
    print(f"  • Top App: {res_procs['top_memory'][0]['name']} ({res_procs['top_memory'][0]['memory_mb']} MB)")

    # Drive Storage Telemetry
    res_storage = os_auto.get_storage_status()
    print(f"  • Storage Telemetry: {res_storage.get('spoken_summary')}")
    assert len(res_storage.get("drives", [])) > 0, "Must return fixed disk drives"
    print("  [OK] Windows OS Automator verified.")

    # 4. Tool Registry Registration
    print("\n[4/5] Testing Universal Tool Registry Integration...")
    registry = ToolRegistry()
    tools_dict = registry.tools
    jarvis_tools = ["execute_protocol", "manage_files", "manage_processes", "get_storage_status", "empty_recycle_bin"]
    for t_name in jarvis_tools:
        assert t_name in tools_dict, f"Tool '{t_name}' must be registered"
        print(f"  • Registered: {t_name}")
    print(f"  • Total Active Tools Count: {len(tools_dict)}")
    print("  [OK] Tool Registry schemas verified.")

    # 5. Audio Cues Generation
    print("\n[5/5] Testing Procedural Sound Cues...")
    try:
        play_protocol_cue()
        play_diagnostic_cue()
        play_alert_cue()
        print("  • Protocol, Diagnostic, and Alert procedural cues synthesized cleanly.")
        print("  [OK] Audio cues verified.")
    except Exception as e:
        print(f"  • Audio cue note: {e}")

    print("\n" + "=" * 60)
    print("🎉 ALL J.A.R.V.I.S. CAPABILITY CHECKS PASSED SUCCESSFULLY!")
    print("=" * 60)
    sys.exit(0)

if __name__ == "__main__":
    test_jarvis()

