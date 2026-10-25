# Manga Lens

Manga Lens is an Android reader for studying Japanese through physical manga.
Photograph or import a page, recognize its text, tap a speech bubble for readings,
dictionary meanings and grammar, and bookmark vocabulary in your personal library.
Translation is optional: choose an on-device model or configure your own remote API.
Remote translation sends recognized text; page artwork stays on the device.

## Install

Download a signed APK from [GitHub Releases](https://github.com/Okami-Dan/MangaLens/releases).
On Android, allow installation from the browser or file manager used to open it.
First launch prepares OCR models and downloads the detector and vocabulary, requiring
about 169 MB of network data. Optional translation models require additional downloads.
OCR, dictionary lookup and grammar then work offline.

The current app targets Android 8.0+ (API 26), with ARM64 builds only. The native local
translator is compiled for ARMv8.2-A with dot-product instructions; older ARM64 CPUs
are not supported by that translator. The primary tested device is a Galaxy A73 5G.
This is not a universal Android build. An x86_64 debug override is available for emulator
development; release builds remain ARM64.

Use **Options → Back up to a file** before uninstalling or doing device tests.
Uninstalling deletes the library, including its Android/media directory. Reinstalling
over an existing installation requires the same signing key.

## Build from source

Install these prerequisites:

- JDK 17 or 21; set `JAVA_HOME` to it (the wrapper must not pick an older Java).
- Android SDK platform 35, build-tools 34.0.0, NDK 27.0.12077973, and CMake 3.31.6.
- Python 3.10+ on `PATH`. Normal asset setup and grammar verification use only its standard library.
- `tar` on `PATH`, used to unpack the checksum-pinned llama.cpp source.

Open `android/` in Android Studio and configure the SDK location, or set `ANDROID_HOME`.
Your `android/local.properties` belongs only on your machine and is ignored by Git.
Gradle 8.11.1 is provided by the wrapper. The first build downloads Gradle, Maven
dependencies, and a pinned llama.cpp source archive; no existing build cache is required.

From the repository root:

```sh
python tools/setup_assets.py
python tools/setup_assets.py --check
python tools/build_grammar_coverage.py
cd android
sh gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat` instead of `sh gradlew`. If Python is not named `python`,
pass `-PpythonExecutable=/absolute/path/to/python` to Gradle.

The debug APK is `android/app/build/outputs/apk/debug/app-debug.apk`.
For an unsigned optimized release, run `sh gradlew :app:assembleRelease`.
See [release instructions](docs/releases.md) for signing and distribution.

**Maintainer prerequisite:** the separate `build-assets-v1` GitHub release must exist
before network asset setup can succeed. Until it is hosted, import the verified release
files with `python tools/setup_assets.py --local-dir /path/to/build-assets-v1`.
Large dictionary/model binaries are downloaded into ignored `var/`, not committed.
See [build asset provenance and regeneration](docs/build-assets.md).

## Tests and development

```sh
python -m unittest discover -s tools/tests -v
python tools/build_grammar_coverage.py
cd android
sh gradlew testDebugUnitTest :core:model:test :app:assembleDebugAndroidTest
```

Instrumentation tests require a disposable device or emulator with the matching ABI.
For x86_64, add `-PtutorialEmulatorAbi=x86_64` to the build and test commands.
Do not run `connectedDebugAndroidTest` on a phone containing your only library copy;
the test lifecycle can uninstall the app. Photo parity tests and private manga fixtures
are intentionally excluded. Text-based regression fixtures remain available.

The project is organized into `android/app`, `android/core`, `android/ml`, and
`android/feature`. The checked-in Room schemas preserve database migration history.
`tools/` contains build-asset and artwork maintenance scripts; `Design mockups/`
contains the source artwork required by those scripts, not prototype web pages.

Read [CONTRIBUTING.md](CONTRIBUTING.md) for contribution and verification guidance.

## License and data sources

Original project code and original project artwork are licensed under
[GPL-3.0-only](LICENSE). Third-party libraries, fonts, model weights, dictionary data,
and dictionary-derived assets keep their own licenses; see [THIRD_PARTY.md](THIRD_PARTY.md).
In particular, JMdict and the dictionary-derived grammar lexicon are CC BY-SA 4.0,
not relicensed as GPL. Packaged tutorial footage may show third-party manga excerpts;
the project license does not grant rights to those underlying works.
