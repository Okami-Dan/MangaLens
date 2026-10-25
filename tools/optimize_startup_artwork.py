# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow==12.3.0"]
# ///
"""Encode the four original startup/background illustrations as full-resolution WebP.

Run: python tools/optimize_startup_artwork.py
The design PNG originals remain untouched. Android resource names stay unchanged.
"""
import json
from pathlib import Path

from PIL import Image, features

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "Design mockups"
TARGET = ROOT / "android/app/src/main/res/drawable-nodpi"


def main():
    if not features.check("webp"):
        raise RuntimeError("Pillow requires WebP codec support")
    rows = []
    for index in range(1, 5):
        source = SOURCE / f"Startup_screen_{index}.png"
        target = TARGET / f"startup_screen_{index}.webp"
        with Image.open(source) as original:
            # Keep transparency where present; avoid an unused alpha channel otherwise.
            alpha = original.getchannel("A").getextrema() if "A" in original.getbands() else (255, 255)
            image = original.convert("RGB" if alpha == (255, 255) else "RGBA")
            image.save(target, "WEBP", quality=90, method=6, exact=True)
            with Image.open(target) as encoded:
                encoded.load()
                if encoded.size != original.size:
                    raise RuntimeError(f"Dimensions changed for {source.name}")
                if alpha != (255, 255) and encoded.getchannel("A").tobytes() != original.getchannel("A").tobytes():
                    raise RuntimeError(f"Transparency changed for {source.name}")
            rows.append({"resource": target.name, "width": original.width, "height": original.height,
                         "alpha_range": alpha, "original_bytes": source.stat().st_size,
                         "optimized_bytes": target.stat().st_size})
        legacy = TARGET / f"startup_screen_{index}.png"
        if legacy.exists():
            if legacy.read_bytes() != source.read_bytes():
                raise RuntimeError(f"Refusing to remove {legacy}: it differs from the original design PNG")
            legacy.unlink()
    print(json.dumps({"quality": 90, "method": 6, "images": rows,
                      "original_total_bytes": sum(row["original_bytes"] for row in rows),
                      "optimized_total_bytes": sum(row["optimized_bytes"] for row in rows)}, indent=2))


if __name__ == "__main__":
    main()
