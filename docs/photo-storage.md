# Photo storage after OCR

Captures and imports retain a full-resolution rectified JPEG while OCR is pending.
After detection, OCR, and analysis commit together, the page switches to its reader
JPEG (at most 1,400 pixels wide). The reader JPEG and 480-pixel thumbnail keep their
existing quality of 92. Original dimensions and normalized region boxes remain in
the database, so selection and overlays use the same coordinates.

Camera originals are no longer saved. The nullable `rawImagePath` column remains for
compatibility with older databases and backups; cleanup clears it and removes those
originals. Pending and failed pages keep their full-resolution **OCR** source.

Cleanup runs after successful processing and after startup adoption, including after
restoring a backup. Missing or corrupt reader images are regenerated from the OCR
source, encoded to a temporary file, decoded for verification, and promoted before
the database reference changes. A cleanup journal records obsolete paths before any
reference changes. Sources are removed only after the updated vault snapshot succeeds;
failed snapshots or deletions are retried on later launches. Backup, snapshot, adoption,
page storage/deletion, and cleanup share a lock. A staged restore defers cleanup until
the restored database is installed. Cleanup refuses paths outside managed page storage
and protects files referenced elsewhere in the library.

Completed pages reuse stored OCR results, including pages with zero regions. Translation
and manual corrections continue to use stored text. Repeating OCR at full resolution
requires a new source; the retained reader image is not used to rerun OCR.

## Regression coverage

`ImageRetentionTest` exercises isolated storage, image promotion, pending sources,
missing/corrupt images, failed snapshots, interrupted deletion, referenced-file
protection, restore staging, cancellation, transaction rollback, and backup round
trips. Build the pipeline instrumentation APK and run it on a disposable device:

```sh
cd android
sh gradlew :feature:pipeline:assembleDebugAndroidTest
sh gradlew :feature:pipeline:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mangareader.pipeline.ImageRetentionTest
```

Use `gradlew.bat` on Windows. See CONTRIBUTING.md and README.md before running tests
against any installation containing library data.
