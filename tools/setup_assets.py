#!/usr/bin/env python3
"""Install the checksum-pinned APK build inputs; Python 3.10+, no packages required."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path, PurePosixPath

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = Path(__file__).with_name("build-assets.json")


def verified(path: Path, asset: dict) -> bool:
    if not path.is_file() or path.stat().st_size != asset["bytes"]:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest() == asset["sha256"]


def destination(root: Path, asset: dict) -> Path:
    name = asset["path"]
    rel = PurePosixPath(name)
    if not name or not rel.parts or "\\" in name or ":" in name or rel.is_absolute() or ".." in rel.parts or rel.parts[0] != "var":
        raise ValueError(f"Unsafe asset destination: {name}")
    target = (root / name).resolve()
    if not target.is_relative_to(root.resolve()):
        raise ValueError(f"Asset destination escapes repository: {name}")
    return target


def read_manifest(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("version") != 1 or not data.get("assets"):
        raise ValueError("Unsupported or empty build asset manifest")
    paths, names = set(), set()
    for asset in data["assets"]:
        destination(ROOT, asset)
        name = asset["file"]
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", name):
            raise ValueError(f"Unsafe asset filename: {name}")
        if type(asset["bytes"]) is not int or asset["bytes"] <= 0:
            raise ValueError(f"Invalid byte count: {name}")
        if not re.fullmatch(r"[0-9a-f]{64}", asset["sha256"]):
            raise ValueError(f"Invalid SHA-256: {name}")
        key = asset["path"].lower()
        if key in paths or name.lower() in names:
            raise ValueError(f"Duplicate asset: {name}")
        paths.add(key)
        names.add(name.lower())
    return data


def install_asset(root: Path, asset: dict, *, local_dir: Path | None, base_url: str, check: bool = False) -> None:
    target = destination(root, asset)
    if verified(target, asset):
        print(f"[ok] {asset['path']}")
        return
    if check:
        raise ValueError(f"Missing or invalid {asset['path']}; run python tools/setup_assets.py")
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + ".part")
    try:
        if temporary.is_symlink():
            raise ValueError(f"Refusing symlink: {temporary}")
        if local_dir is not None:
            candidates = [local_dir / asset["file"], local_dir / asset["path"]]
            source = next((p for p in candidates if p.is_file()), None)
            if source is None:
                raise FileNotFoundError(f"{asset['file']} not found in {local_dir}")
            if not verified(source, asset):
                raise ValueError(f"Size or SHA-256 mismatch: {source}")
            shutil.copyfile(source, temporary)
        else:
            parsed = urllib.parse.urlparse(base_url)
            if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost", "::1"}):
                raise ValueError("Asset base URL must use HTTPS (HTTP allowed on localhost for tests)")
            url = base_url.rstrip("/") + "/" + urllib.parse.quote(asset["file"])
            print(f"[download] {asset['file']} ({asset['bytes'] / 1e6:.2f} MB)", flush=True)
            request = urllib.request.Request(url, headers={"User-Agent": "MangaLens-build-assets/1"})
            with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
                count = 0
                while block := response.read(1024 * 1024):
                    count += len(block)
                    if count > asset["bytes"]:
                        raise ValueError(f"Download exceeds expected size: {asset['file']}")
                    output.write(block)
        if not verified(temporary, asset):
            raise ValueError(f"Size or SHA-256 mismatch: {asset['file']}")
        temporary.replace(target)
        print(f"[installed] {asset['path']}")
    finally:
        if not temporary.is_symlink():
            temporary.unlink(missing_ok=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local-dir", type=Path, help="Import staged release files or an existing var/ tree without network")
    parser.add_argument("--base-url", help="Override the manifest's release URL (e.g. a mirror)")
    parser.add_argument("--check", action="store_true", help="Verify installed files without changing or downloading anything")
    args = parser.parse_args(argv)
    try:
        manifest = read_manifest(MANIFEST)
        for asset in manifest["assets"]:
            install_asset(ROOT, asset, local_dir=args.local_dir, base_url=args.base_url or manifest["base_url"], check=args.check)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Asset setup failed: {error}", file=sys.stderr)
        print("The build-assets-v1 release must be hosted before network setup works. "
              "Use --local-dir for verified offline import. Retry restarts incomplete files.", file=sys.stderr)
        return 1
    print("All build assets verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
