"""Shrink var/assets/jmdict.sqlite into the copy the APK ships.

Plan section 6 allows the dictionary to be "shipped as an asset (or downloaded on
first run alongside the OCR model)". M3 ships it as an asset, because section 2.4
calls the dictionary layer the core feature and it should not need a USB cable the
way the sideload-only OCR export does.

The harness database is 74.0 MB, of which 20.8 MB is `gloss_fts` -- an FTS5 index
over the English glosses that only `Dictionary.search_english` uses, and only for the
saved-words screen's English->Japanese search, which is M6. Dropping it leaves the
lookup path stage 6 actually walks (forms -> entries -> senses) byte-for-byte
identical, so parity with the harness is unaffected.

Also drops the `tags` meta row: 30 KB of JSON describing JMdict's tag vocabulary that
nothing on the phone reads.

    python tools/build_jmdict_mobile.py
"""
from __future__ import annotations

import hashlib
import shutil
import sqlite3
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "var" / "assets" / "jmdict.sqlite"
OUT = ROOT / "var" / "assets" / "jmdict-mobile.sqlite"

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def main() -> int:
    if not SRC.exists():
        print(f"[fail] {SRC} not found -- run tools/build_jmdict.py first")
        return 1

    print(f"[copy] {SRC.name} ({SRC.stat().st_size / 1e6:.1f} MB)")
    if OUT.exists():
        OUT.unlink()
    shutil.copy(SRC, OUT)

    db = sqlite3.connect(OUT)
    db.executescript("""
        DROP TABLE IF EXISTS gloss_fts;
        DELETE FROM meta WHERE key = 'tags';
        INSERT OR REPLACE INTO meta VALUES ('built_by', 'tools/build_jmdict_mobile.py');
        INSERT OR REPLACE INTO meta VALUES ('variant', 'mobile: no gloss_fts');
        PRAGMA journal_mode = DELETE;
    """)
    db.commit()
    db.execute("VACUUM")
    db.close()

    # The app opens this read-only and must never find a stray -wal/-shm beside it.
    for suffix in ("-wal", "-shm"):
        stray = OUT.with_name(OUT.name + suffix)
        if stray.exists():
            stray.unlink()

    check = sqlite3.connect(f"file:{OUT}?mode=ro", uri=True)
    counts = {t: check.execute(f"SELECT count(*) FROM {t}").fetchone()[0]
              for t in ("entries", "forms", "senses")}
    meta = dict(check.execute("SELECT key, value FROM meta"))
    # The same three probes tools/build_jmdict.py runs, so a regression here is loud.
    for probe in ("化け物", "見る", "ばけもの"):
        row = check.execute(
            "SELECT s.pos, s.gloss FROM forms f JOIN senses s ON s.entry_id = f.entry_id "
            "WHERE f.text = ? ORDER BY s.position LIMIT 1", (probe,)).fetchone()
        print(f"[test] {probe}: {row if row else 'NOT FOUND'}")
    check.close()

    size = OUT.stat().st_size
    sha = hashlib.sha256(OUT.read_bytes()).hexdigest()
    print(f"[ok  ] {OUT} {size / 1e6:.1f} MB ({size} bytes)")
    print(f"       {counts}")
    print(f"       jmdict {meta.get('jmdict_version')} dated {meta.get('dict_date')}")
    print(f"       sha256 {sha}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
