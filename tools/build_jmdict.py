"""Build var/assets/jmdict.sqlite from the jmdict-simplified release.

Plan section 6: JMdict lives in a separate, read-only, pre-built database; do not
parse JMdict XML at runtime. jmdict-simplified publishes the same data as JSON, so
the build step is a straight transform. The resulting schema is deliberately close
to what a Room @Entity set would look like so the Android build can reuse it.

Source: https://github.com/scriptin/jmdict-simplified  (JMdict itself is EDRDG,
CC BY-SA 4.0 -- attribution is required in-app, see plan section 11.)
"""
from __future__ import annotations

import io
import json
import sqlite3
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "var" / "assets" / "jmdict.sqlite"
CACHE = ROOT / "var" / "assets" / "_jmdict_source.json"

RELEASES = "https://api.github.com/repos/scriptin/jmdict-simplified/releases/latest"

# Windows consoles here default to a non-UTF-8 codepage; the probes below print Japanese.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SCHEMA = """
PRAGMA journal_mode = OFF;
PRAGMA synchronous = OFF;

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);

CREATE TABLE entries (
  id      INTEGER PRIMARY KEY,
  common  INTEGER NOT NULL          -- JMdict priority flag, used to rank lookups
);

-- Every writable form of an entry: kanji headwords and kana readings alike.
-- Stage 6 looks up a token's base form here, falling back to its reading.
CREATE TABLE forms (
  entry_id INTEGER NOT NULL,
  text     TEXT NOT NULL,
  kind     TEXT NOT NULL,           -- 'kanji' | 'kana'
  common   INTEGER NOT NULL,
  applies_to TEXT NOT NULL DEFAULT ''
);

CREATE TABLE senses (
  entry_id INTEGER NOT NULL,
  position INTEGER NOT NULL,
  pos      TEXT NOT NULL,           -- comma-separated JMdict pos tags, e.g. 'n,vs'
  misc     TEXT NOT NULL,
  gloss    TEXT NOT NULL,           -- '; '-joined English glosses
  kanji_restrictions TEXT NOT NULL DEFAULT '',
  reading_restrictions TEXT NOT NULL DEFAULT ''
);
"""

INDEXES = """
CREATE INDEX idx_forms_text ON forms(text);
CREATE INDEX idx_forms_entry ON forms(entry_id);
CREATE INDEX idx_senses_entry ON senses(entry_id, position);

-- English -> Japanese search, for the saved-words screen and manual lookup.
CREATE VIRTUAL TABLE gloss_fts USING fts5(gloss, entry_id UNINDEXED);
INSERT INTO gloss_fts(gloss, entry_id) SELECT gloss, entry_id FROM senses;
"""


def download_resumable(url: str, size: int, attempts: int = 6) -> bytes:
    """GitHub's release CDN truncates this download fairly often, so resume by range."""
    buf = bytearray()
    for attempt in range(1, attempts + 1):
        req = urllib.request.Request(url)
        if buf:
            req.add_header("Range", f"bytes={len(buf)}-")
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                while chunk := r.read(1 << 18):
                    buf.extend(chunk)
                    print(f"\r  {len(buf)/1e6:5.1f} / {size/1e6:.1f} MB", end="", flush=True)
        except Exception as e:  # IncompleteRead, timeouts, reset connections
            print(f"\n[warn] attempt {attempt} interrupted at {len(buf)/1e6:.1f} MB: "
                  f"{type(e).__name__}; resuming")
            continue
        if len(buf) >= size:
            print()
            return bytes(buf)
        print(f"\n[warn] attempt {attempt} short by {(size-len(buf))/1e6:.1f} MB; resuming")
    raise RuntimeError(f"could not download {url} after {attempts} attempts")


def fetch_source() -> dict:
    if CACHE.exists():
        print(f"[skip] using cached {CACHE.name} ({CACHE.stat().st_size/1e6:.1f} MB)")
        return json.loads(CACHE.read_text(encoding="utf-8"))

    print("[get ] locating latest jmdict-simplified release")
    with urllib.request.urlopen(RELEASES, timeout=60) as r:
        release = json.load(r)

    asset = next(
        a for a in release["assets"]
        if a["name"].startswith("jmdict-eng-") and "common" not in a["name"]
        and a["name"].endswith(".json.zip")
    )
    print(f"[get ] {asset['name']} ({asset['size']/1e6:.1f} MB)")
    blob = download_resumable(asset["browser_download_url"], asset["size"])

    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        name = z.namelist()[0]
        raw = z.read(name).decode("utf-8")
    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(raw, encoding="utf-8")
    print(f"[ok  ] extracted {name} ({len(raw)/1e6:.1f} MB)")
    return json.loads(raw)


def main() -> int:
    data = fetch_source()
    words = data["words"]
    print(f"[info] {len(words)} entries, JMdict {data.get('version')} "
          f"dated {data.get('dictDate')}")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    if OUT.exists():
        OUT.unlink()
    db = sqlite3.connect(OUT)
    db.executescript(SCHEMA)

    entries, forms, senses = [], [], []
    for w in words:
        eid = int(w["id"])
        common = any(k.get("common") for k in w.get("kanji", [])) or \
                 any(k.get("common") for k in w.get("kana", []))
        entries.append((eid, int(common)))

        for k in w.get("kanji", []):
            forms.append((eid, k["text"], "kanji", int(bool(k.get("common"))), ""))
        for k in w.get("kana", []):
            restrictions = [v for v in k.get("appliesToKanji", []) if v != "*"]
            forms.append((eid, k["text"], "kana", int(bool(k.get("common"))),
                          "\x1f".join(restrictions)))

        inherited_pos = []
        for i, s in enumerate(w.get("sense", [])):
            if s.get("partOfSpeech"):
                inherited_pos = s["partOfSpeech"]
            gloss = "; ".join(g["text"] for g in s.get("gloss", []) if g.get("text"))
            if not gloss:
                continue
            senses.append((eid, i, ",".join(inherited_pos),
                           ",".join(s.get("misc", [])), gloss,
                           "\x1f".join(v for v in s.get("appliesToKanji", []) if v != "*"),
                           "\x1f".join(v for v in s.get("appliesToKana", []) if v != "*")))

    db.executemany("INSERT INTO entries VALUES (?,?)", entries)
    db.executemany("INSERT INTO forms VALUES (?,?,?,?,?)", forms)
    db.executemany("INSERT INTO senses VALUES (?,?,?,?,?,?,?)", senses)
    db.executemany("INSERT INTO meta VALUES (?,?)", [
        ("source", "scriptin/jmdict-simplified"),
        ("jmdict_version", str(data.get("version"))),
        ("dict_date", str(data.get("dictDate"))),
        ("tags", json.dumps(data.get("tags", {}), ensure_ascii=False)),
        ("license", "JMdict/EDICT, EDRDG, CC BY-SA 4.0 -- attribution required"),
        ("built_by", "tools/build_jmdict.py"),
        ("schema_version", "2"),
    ])
    print("[info] indexing")
    db.executescript(INDEXES)
    db.commit()

    n_fts, = db.execute("SELECT count(*) FROM gloss_fts").fetchone()
    print(f"[ok  ] {len(entries)} entries / {len(forms)} forms / {len(senses)} senses "
          f"/ {n_fts} fts rows -> {OUT} ({OUT.stat().st_size/1e6:.1f} MB)")

    # Spot-check the lookups stage 6 actually performs.
    for probe in ("化け物", "見る", "ばけもの"):
        rows = db.execute(
            "SELECT s.pos, s.gloss FROM forms f JOIN senses s ON s.entry_id = f.entry_id "
            "WHERE f.text = ? ORDER BY s.position LIMIT 1", (probe,)).fetchall()
        print(f"[test] {probe}: {rows[0] if rows else 'NOT FOUND'}")
    db.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
