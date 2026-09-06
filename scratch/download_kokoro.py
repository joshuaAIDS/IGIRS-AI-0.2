import os
import sys
import urllib.request
from pathlib import Path

MODELS_DIR = Path(__file__).resolve().parent.parent / "models" / "kokoro"
MODELS_DIR.mkdir(parents=True, exist_ok=True)

FILES = [
    (
        "voices-v1.0.bin",
        "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin"
    ),
    (
        "kokoro-v1.0.onnx",
        "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx"
    )
]

def download_file(filename: str, url: str):
    target = MODELS_DIR / filename
    if target.exists() and target.stat().st_size > 1024 * 1024:
        print(f"File {filename} already exists ({round(target.stat().st_size / (1024*1024), 1)} MB), skipping.")
        return True

    print(f"Downloading {filename} from {url}...")
    headers = {"User-Agent": "Mozilla/5.0"}
    req = urllib.request.Request(url, headers=headers)
    
    with urllib.request.urlopen(req) as resp, open(target, "wb") as f:
        total_size = int(resp.headers.get("content-length", 0))
        downloaded = 0
        chunk_size = 1024 * 1024  # 1MB
        last_logged = 0

        while True:
            chunk = resp.read(chunk_size)
            if not chunk:
                break
            f.write(chunk)
            downloaded += len(chunk)
            
            if downloaded - last_logged >= 20 * 1024 * 1024 or downloaded == total_size:
                pct = int((downloaded / total_size) * 100) if total_size else 0
                mb = round(downloaded / (1024 * 1024), 1)
                total_mb = round(total_size / (1024 * 1024), 1) if total_size else "?"
                print(f"[{filename}] {mb} MB / {total_mb} MB ({pct}%)")
                last_logged = downloaded

    print(f"Successfully downloaded {filename} ({round(target.stat().st_size / (1024*1024), 1)} MB).")
    return True

if __name__ == "__main__":
    for fname, url in FILES:
        download_file(fname, url)
    print("All Kokoro model assets ready in", MODELS_DIR)
