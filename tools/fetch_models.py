"""Fetch the detection and OCR models into var/models/.

Mirrors plan section 8 steps 1-2, and section 4 stage 5's "do not ship the model,
fetch on first launch with a progress screen, verify checksum" rule -- here the
checksum comes from the Hugging Face API rather than a hardcoded constant, since
the harness always pulls from the same source of truth the app will.
"""
from __future__ import annotations

import hashlib
import json
import shutil
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "var" / "models"

DETECTOR_REPO = "ogkalu/comic-text-and-bubble-detector"
OCR_REPO = "kha-white/manga-ocr-base"

# (repo, filename, destination subdirectory)
FILES = [
    (DETECTOR_REPO, "detector.onnx", "detector"),
    (DETECTOR_REPO, "config.json", "detector"),
    (DETECTOR_REPO, "preprocessor_config.json", "detector"),
    (OCR_REPO, "pytorch_model.bin", "manga-ocr"),
    (OCR_REPO, "config.json", "manga-ocr"),
    (OCR_REPO, "preprocessor_config.json", "manga-ocr"),
    (OCR_REPO, "tokenizer_config.json", "manga-ocr"),
    (OCR_REPO, "special_tokens_map.json", "manga-ocr"),
    (OCR_REPO, "vocab.txt", "manga-ocr"),
]


def repo_index(repo: str) -> dict[str, dict]:
    """filename -> sibling metadata (lfs sha256 + size when present)."""
    url = f"https://huggingface.co/api/models/{repo}?blobs=true"
    with urllib.request.urlopen(url, timeout=60) as r:
        data = json.load(r)
    return {s["rfilename"]: s for s in data.get("siblings", [])}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(repo: str, name: str, dest: Path) -> None:
    url = f"https://huggingface.co/{repo}/resolve/main/{name}"
    tmp = dest.with_suffix(dest.suffix + ".part")
    with urllib.request.urlopen(url, timeout=120) as r:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        with tmp.open("wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
                done += len(chunk)
                if total:
                    pct = 100 * done / total
                    print(f"\r  {name}: {done/1e6:6.1f} / {total/1e6:.1f} MB ({pct:5.1f}%)",
                          end="", flush=True)
    print()
    tmp.replace(dest)


def main() -> int:
    MODELS.mkdir(parents=True, exist_ok=True)
    indexes: dict[str, dict] = {}
    ok = True

    for repo, name, sub in FILES:
        if repo not in indexes:
            indexes[repo] = repo_index(repo)
        meta = indexes[repo].get(name, {})
        expected = (meta.get("lfs") or {}).get("sha256")
        expected_size = (meta.get("lfs") or {}).get("size") or meta.get("size")

        outdir = MODELS / sub
        outdir.mkdir(parents=True, exist_ok=True)
        dest = outdir / name

        if dest.exists() and expected_size and dest.stat().st_size == expected_size:
            print(f"[skip] {sub}/{name} already present ({dest.stat().st_size/1e6:.1f} MB)")
        else:
            print(f"[get ] {repo}/{name}")
            download(repo, name, dest)

        if expected:
            actual = sha256(dest)
            if actual == expected:
                print(f"[ok  ] {sub}/{name} sha256 verified")
            else:
                print(f"[FAIL] {sub}/{name} sha256 mismatch\n       expected {expected}\n"
                      f"       actual   {actual}")
                ok = False


    free = shutil.disk_usage(MODELS).free / 1e9
    print(f"\nmodels in {MODELS} (disk free {free:.1f} GB)")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
