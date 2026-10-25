# Build assets

`tools/build-assets.json` pins the four large inputs used by the current APK:

| Build input | Provenance | Use |
| --- | --- | --- |
| `jmdict-mobile.sqlite` | scriptin/jmdict-simplified 3.6.2, dictionary date 2026-08-17 | Mobile dictionary and grammar verification |
| `encoder.onnx`, `decoder.onnx` | kha-white/manga-ocr-base, local ONNX export and dynamic QUInt8 quantization | Bundled OCR models |
| `mecab-ipadic-2.7.0-20070801.tar.gz` | Atilika's IPADIC source, matching Kuromoji 0.9.0 | Offline grammar inventory verification |

The upstream IPADIC source is
<https://atilika.com/releases/mecab-ipadic/mecab-ipadic-2.7.0-20070801.tar.gz>.
Model and data licenses are described in [THIRD_PARTY.md](../THIRD_PARTY.md).

Run `python tools/setup_assets.py` at the source root. Setup obtains exact files from
the `build-assets-v1` GitHub release, verifies both byte counts and SHA-256, and
atomically promotes each verified `.part` file. Valid installed files are reused.
Failed and interrupted files are never installed; a retry starts that file again.
The script does not require pip packages or uv.

`--local-dir DIR` imports either flat release attachments or paths below an existing
repository root. `--check` verifies without downloading or writing. `--base-url URL`
can select an HTTPS mirror serving the same filenames and bytes. Never edit expected
hashes merely to accept a failed download.

## Maintaining a new asset version

Normal contributors should use setup. The regeneration tools are for deliberate
model/dictionary updates, not a guaranteed byte-identical rebuild of these historical
exports. New library versions, upstream models, dictionary releases, or SQLite
versions may change bytes, grammar entries, and runtime results.

For regeneration, create a dedicated Python environment and install
`tools/requirements-models.txt`. `fetch_models.py` fetches the upstream detector and
OCR checkpoint; `export_ocr_onnx.py` exports two FP32 graphs, and
`quantize_models.py --skip-detector` produces the INT8 pair. The optional detector
quantization experiments are not used by the Android app.

`build_jmdict.py` obtains the latest jmdict-simplified English JSON (or reuses an
existing `var/assets/_jmdict_source.json`). `build_jmdict_mobile.py` removes the FTS
index. Reusing a precisely identified source is required when comparing historical
dictionary results. Record the upstream revision, source date, and tool versions
when producing new assets.

For an intentional update:

1. Validate model outputs and accuracy with appropriately licensed material. Export
   and quantization scripts alone do not establish OCR quality.
2. Update the OCR sizes/hashes in `ModelStore.ARTIFACTS`, keeping runtime installation
   verification enabled. Keep the tokenizer vocabulary consistent with the checkpoint.
3. For dictionary/IPADIC changes, run `python tools/build_grammar_coverage.py --write`,
   review every new inventory disposition and construction, then run the checker
   without `--write` and the linguistic tests. Update text fixtures intentionally.
4. Create a new immutable asset release tag and update `tools/build-assets.json`
   with the new URL, paths, byte counts and hashes. Preserve the preceding release.
5. Build and test the APK with only the new assets and publish corresponding
   license notices and provenance alongside the attachments.

The detector and vocabulary downloaded on device and optional translation GGUFs are
separate runtime downloads; setup does not prefetch them. JNI uses a checksum-pinned
llama.cpp source tarball fetched by Gradle. Those download paths remain unchanged.
