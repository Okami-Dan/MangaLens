# Contributing

Discuss substantial behavior changes in a GitHub issue, then submit a focused pull
request with the problem, changed behavior, and verification results. Contributions
to original code are accepted under GPL-3.0-only. Preserve third-party attributions
and licenses when modifying or adding data, assets, or dependencies.

Follow the build and setup steps in README.md. Keep work scoped to the relevant
module. Preserve normalized page coordinates, stable IDs, translation cache keys,
and the separation between OCR/analysis and explicitly requested translation.
When changing persisted data, add a Room migration and schema snapshot; keep old
schemas and test upgrades. Do not introduce destructive migrations for user libraries.

Run these checks before submitting:

```sh
python -m unittest discover -s tools/tests -v
python tools/setup_assets.py --check
python tools/build_grammar_coverage.py
cd android
sh gradlew testDebugUnitTest :core:model:test :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Use `gradlew.bat` on Windows. Run relevant instrumentation tests on a disposable
device, report its API level and ABI, and state when device tests were not run.
Tests involving storage or the real application may alter its data. Backups and
test-only devices are required for development with meaningful library content.

Do not commit API keys, endpoints with embedded credentials, signing keys, local
paths, SDK configuration, model weights, SQLite build assets, captures, or APKs.
Use generated or appropriately licensed images for new tests; never add private
manga photographs. Small text fixtures are checked in to keep regression tests usable.

Some parity-test comments describe the former desktop harness and its fixture
exporters. That historical harness is intentionally excluded. The checked-in fixtures
run without it or any sample photos; restore missing fixture files from Git. When
changing expected results, document how they were independently verified in the PR.

For grammar and dictionary work, see [build-assets.md](docs/build-assets.md). The
coverage ledger records reviewed constructions and pinned sources. Do not merely
rewrite it to silence a failure: review new entries and exercise positive and
negative cases. The existing Kotlin tests are the public regression reference.

To regenerate launcher/startup artwork, install
`python -m pip install -r tools/requirements-artwork.txt`, edit the sources under
`Design mockups/`, then run `python tools/build_app_icon.py` and/or
`python tools/optimize_startup_artwork.py`. Review the packaged results.
