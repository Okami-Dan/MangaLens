# Android project

Start with the [repository README](../README.md) for prerequisites and asset setup.
Run Gradle here with `sh gradlew` on Unix or `gradlew.bat` on Windows.

The application ID is `com.mangareader`; the displayed app name is Manga Lens.
The current source version remains `0.4-m4` (`versionCode = 1`).

Modules separate the Compose UI, shared models, capture, Room storage, detection,
OCR, linguistic analysis, translation, and pipeline orchestration. Generated APK
assets come from this repository's `var/` and `tools/` directories. Build caches,
downloaded llama.cpp sources, and device-specific SDK configuration are not source.
