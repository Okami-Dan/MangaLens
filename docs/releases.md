# Releasing Manga Lens

## Source and build assets

The public repository root is this directory's parent. Publish only this source tree,
not the surrounding development workspace. Initialize a new Git repository here if
needed, review ignored files and the staged diff, and set the public remote to
`https://github.com/Okami-Dan/MangaLens.git`.

On Unix, mark `android/gradlew` executable. When committing from Windows, record the
executable bit with `git update-index --chmod=+x android/gradlew`. The documented
`sh gradlew` invocation also works before the executable bit is set.

Before public builds, create the `build-assets-v1` release and attach the four inputs
specified in `tools/build-assets.json`, along with the manifest, SHA256SUMS, notices,
and license texts. A prepared copy is supplied separately in the maintainer's
workspace under `outputs/publish-assets/build-assets-v1/`; that folder is not part
of the source repository. Check its SHA256SUMS before uploading. Do not replace
assets under a published tag with different bytes.

## APK build and signing

Prepare assets and run the checks in CONTRIBUTING.md. Build with
`sh gradlew :app:assembleRelease` from `android/` (`gradlew.bat` on Windows).
The result is `app/build/outputs/apk/release/app-release-unsigned.apk`.
Preserve `app/build/outputs/mapping/release/mapping.txt` with the matching APK.
This source distribution does not create or contain a signing key.

Use the existing maintainer release key, stored securely outside Git. With SDK
build-tools on PATH, align and sign the APK, allowing apksigner to prompt for secrets:

```sh
zipalign -p -f 4 app-release-unsigned.apk MangaLens-aligned.apk
apksigner sign --ks /secure/path/release.jks --out MangaLens.apk MangaLens-aligned.apk
apksigner verify --verbose --print-certs MangaLens.apk
```

Run from the directory containing the copied unsigned APK, or supply absolute paths.
Never put keystore passwords into scripts, command history, or the repository.
Check the certificate fingerprint against the previous public release so upgrades
can install over existing libraries. The current source keeps `versionCode = 1`
and `versionName = "0.4-m4"`; increment them deliberately for later app releases.

## Distribution checks

Smoke-test the signed APK on a supported disposable ARM64 device, including first-run
model setup, camera/import, dictionary/grammar, backup/restore, tutorials, and the
translation modes affected by changes. Do not use a personal library for destructive
tests. Inspect the final APK for the OCR pair, dictionary, fonts, tutorial media,
and third-party notice assets. Record device/API/ABI and any checks not performed.

Create a source tag for the exact commit. Attach the signed APK, its SHA-256, release
notes, and third-party notices/licenses to its GitHub release. Provide the matching
complete source through that tag, including changes to bundled native dependencies
if applicable, and keep the build-assets release available. Retain the exact R8 mapping
privately with the release records for crash diagnosis; it need not be in source Git.

Tutorial recordings are retained as shipped app resources. Review third-party material
shown in them when distributing or replacing the clips; the code license does not
license third-party manga artwork.
