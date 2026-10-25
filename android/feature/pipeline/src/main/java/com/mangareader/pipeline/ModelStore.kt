package com.mangareader.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File


/**
 * Where the ONNX graphs come from, and how they get onto the phone.
 *
 * Plan section 4 stage 5 says not to ship the model in the APK, but that was measured
 * against the fp32 checkpoint -- 444 MB, "bloats the download unacceptably"
 * (`docs/onnx-export.md`). The INT8 export is a different number, and the two files
 * with nowhere else to come from are 111 MB combined, less than the 53 MB JMdict asset
 * (`DictionaryStore`) already ships bundled at a comparable size class. So `encoder.onnx`
 * and `decoder.onnx` are [Source.BUNDLED] now: staged into the APK's assets at build
 * time by `app/build.gradle.kts`'s `stageOcrAssets`, same technique as JMdict, and
 * unpacked to the app-private files directory on install like everything else here.
 * `detector.onnx` (161 MB) and `vocab.txt` stay downloaded -- Hugging Face already
 * serves both, so bundling them too would only grow the APK for nothing.
 *
 * Four sources, then:
 *
 *  - **Bundled.** `encoder.onnx` and `decoder.onnx`, see above. Never needs a network
 *    or a user action beyond the one Install tap everything else already goes through.
 *  - **Download.** `detector.onnx` and `vocab.txt` are Hugging Face files, taken from
 *    the same repos `tools/fetch_models.py` pulls, so the desktop harness and the
 *    phone run the same bytes.
 *  - **Import.** The fallback for any artifact that is neither of the above -- there
 *    is none today, but a build without the OCR pair staged (see `stageOcrAssets`'s own
 *    requirement) falls back to this rather than refusing to compile. [install] with a
 *    list of Uris takes files from a document picker -- Downloads, Drive, a USB stick --
 *    and works out which artifact each one is by hashing it. That is the path off the
 *    CLI for a user who is not holding a cable.
 *  - **Sideload.** The same files staged into the app's external media directory with
 *    `adb push` -- the one external location both the adb shell and the app can reach
 *    without granular storage permissions -- and installed from there:
 *
 *        adb push var/models/manga-ocr-onnx-int8/.
 *          /sdcard/Android/media/com.mangareader/models/manga-ocr/
 *
 * All four write through a `.part` file and verify SHA-256 before renaming, so a
 * half-copied 87 MB encoder never presents itself as a model. The fetching itself lives
 * in [Downloads], shared with `LlmStore` -- tier 1's GGUF has the same problem at ten
 * times the size.
 */
class ModelStore(private val context: Context) {

    /**
     * @param relativePath under `models/`; for the OCR trio the directory part is what
     *   [com.mangareader.ocr.MangaOcr] is handed. Doubles as the asset path when
     *   [bundled] is true -- `stageOcrAssets` stages files at exactly this path under
     *   `assets/`, so there is one string to keep in step rather than two.
     * @param url null when the artifact is not publicly hosted -- see the class note
     * @param bundled true when the artifact ships inside the APK itself
     */
    data class Artifact(
        val relativePath: String,
        val label: String,
        val bytes: Long,
        val sha256: String,
        val url: String?,
        val bundled: Boolean = false,
    ) {
        val name: String get() = relativePath.substringAfterLast('/')
    }

    /** Where a missing artifact can be found right now. */
    enum class Source {
        /** Staged in external media by `adb push`; installing copies it in. */
        STAGED,

        /** Inside the APK's own assets; installing unpacks it, no network needed. */
        BUNDLED,

        /** Fetchable over the network. */
        REMOTE,

        /**
         * None of the above -- but the user can still hand it over from a file, which
         * is what [install] with a list of Uris is for. Not a dead end any more; it
         * just cannot be satisfied without the user pointing at something.
         */
        NONE,
    }

    data class Pending(val artifact: Artifact, val source: Source)

    sealed interface Status {
        /** Everything is installed; these are the paths the stages open. */
        data class Ready(val detector: File, val ocrDir: File) : Status

        data class Incomplete(val pending: List<Pending>) : Status {
            val bytes: Long get() = pending.sumOf { it.artifact.bytes }
        }
    }

    data class Progress(
        val label: String,
        val index: Int,
        val total: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) {
        val fraction: Float get() = if (bytesTotal <= 0) 0f else bytesDone.toFloat() / bytesTotal
    }

    companion object {
        private val installationMutex = Mutex()
        private const val TAG = "ModelStore"

        private const val DETECTOR_REPO = "ogkalu/comic-text-and-bubble-detector"
        private const val OCR_REPO = "kha-white/manga-ocr-base"

        private fun hf(repo: String, name: String) =
            "https://huggingface.co/$repo/resolve/main/$name"

        /**
         * Sizes and hashes are of the artifacts `docs/onnx-export.md` section 4 lists
         * as shipping -- the fp32 detector (the INT8 one costs real OCR accuracy) and
         * the INT8 OCR pair.
         */
        val ARTIFACTS = listOf(
            Artifact(
                relativePath = "detector.onnx",
                label = "Bubble detector",
                bytes = 168_481_531,
                sha256 = "065744e91c0594ad8663aa8b870ce3fb27222942eded5a3cc388ce23421bd195",
                url = hf(DETECTOR_REPO, "detector.onnx"),
            ),
            Artifact(
                relativePath = "manga-ocr/encoder.onnx",
                label = "OCR encoder",
                bytes = 87_063_458,
                sha256 = "673b48044efdb8943bfdb2db748882acee025e30df5c888600ebceedf4dd3ddb",
                url = null,
                bundled = true,
            ),
            Artifact(
                relativePath = "manga-ocr/decoder.onnx",
                label = "OCR decoder",
                bytes = 29_650_985,
                sha256 = "e06a1576620f00875e44dc13d6e7c2375120a2729278e066adf9b7c223ea12c9",
                url = null,
                bundled = true,
            ),
            Artifact(
                relativePath = "manga-ocr/vocab.txt",
                label = "OCR vocabulary",
                bytes = 24_072,
                sha256 = "344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d",
                url = hf(OCR_REPO, "vocab.txt"),
            ),
        )
    }

    /** Installed models. App-private, so uninstalling the app reclaims the 285 MB. */
    val installDir: File get() = File(context.filesDir, "models")

    /**
     * The `adb push` target. Read-only as far as this class is concerned.
     *
     * `externalMediaDirs` is deprecated in favour of the MediaStore, which is the right
     * advice for media and the wrong one here: `Android/media/<package>` is the only
     * app-scoped directory an `adb shell` can write to and the app can then read without
     * asking for storage permissions, which is exactly what staging a 117 MB export off
     * a USB cable needs.
     */
    @Suppress("DEPRECATION")
    val stagingDirs: List<File>
        get() = context.externalMediaDirs.filterNotNull().map { File(it, "models") }

    fun status(): Status {
        val pending = ARTIFACTS.filterNot { installed(it) }.map { artifact ->
            Pending(
                artifact,
                when {
                    staged(artifact) != null -> Source.STAGED
                    artifact.bundled -> Source.BUNDLED
                    artifact.url != null -> Source.REMOTE
                    else -> Source.NONE
                },
            )
        }
        return if (pending.isEmpty()) {
            Status.Ready(
                detector = File(installDir, "detector.onnx"),
                ocrDir = File(installDir, "manga-ocr"),
            )
        } else {
            Status.Incomplete(pending)
        }
    }

    /**
     * Fetch or copy whatever [status] reports missing. Returns the resulting status, so
     * a caller that could not satisfy every artifact still learns which are left.
     */
    suspend fun install(onProgress: (Progress) -> Unit = {}): Status =
        installationMutex.withLock { withContext(Dispatchers.IO) {
            val incomplete = status() as? Status.Incomplete ?: return@withContext status()
            val work = incomplete.pending.filter { it.source != Source.NONE }
            val total = work.sumOf { it.artifact.bytes }
            var doneBefore = 0L

            work.forEachIndexed { index, pending ->
                coroutineContext.ensureActive()
                val artifact = pending.artifact
                val target = File(installDir, artifact.relativePath)
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, artifact.name + ".part")

                val verb = when (pending.source) {
                    Source.STAGED -> "Copying"
                    Source.BUNDLED -> "Unpacking"
                    else -> "Downloading"
                }
                val report = { copied: Long ->
                    onProgress(
                        Progress(
                            label = verb + " " + artifact.label,
                            index = index,
                            total = work.size,
                            bytesDone = doneBefore + copied,
                            bytesTotal = total,
                        ),
                    )
                }
                report(0)

                val digest = when (pending.source) {
                    Source.STAGED ->
                        checkNotNull(staged(artifact)).inputStream()
                            .use { Downloads.copy(it, part, report) }

                    // Streamed straight from the APK's own assets -- see the class
                    // note on `stageOcrAssets`, which staged it there at the same
                    // relativePath this reads back.
                    Source.BUNDLED ->
                        context.assets.open(artifact.relativePath).use { Downloads.copy(it, part, report) }

                    Source.REMOTE -> Downloads.download(checkNotNull(artifact.url), part, report)
                    Source.NONE -> error("unreachable")
                }

                if (!digest.equals(artifact.sha256, ignoreCase = true)) {
                    part.delete()
                    error(artifact.label + ": checksum mismatch (got " + digest + ")")
                }
                if (!part.renameTo(target)) {
                    part.delete()
                    error(artifact.label + ": could not move into place")
                }
                doneBefore += artifact.bytes
                Log.i(TAG, "installed " + artifact.relativePath + " (" + artifact.bytes + " bytes)")
            }
            status()
        } }

    /**
     * Installs artifacts from files the user picked, identified by their checksums.
     *
     * The way onto a phone for the INT8 OCR pair without a USB cable. What each file *is*
     * is decided by hashing it and looking the digest up in [ARTIFACTS], never by its
     * name -- see [Imports] for why -- so a file that does not match a pinned checksum is
     * reported rather than installed, and the same pass that identifies a good file
     * rejects a truncated one.
     *
     * Files that are already installed are accepted and re-written rather than refused:
     * re-importing a model the user believes is broken is a reasonable thing to do, and
     * the checksum makes it safe.
     *
     * @return the resulting status, so the caller learns what is still missing
     * @throws IllegalStateException naming every file that could not be identified
     */
    suspend fun install(uris: List<Uri>, onProgress: (Progress) -> Unit = {}): Status =
        installationMutex.withLock { withContext(Dispatchers.IO) {
            if (uris.isEmpty()) return@withContext status()
            val documents = uris.map { it to Imports.describe(context, it) }
            val total = documents.sumOf { it.second.bytes }
            var doneBefore = 0L
            val unmatched = mutableListOf<String>()

            documents.forEachIndexed { index, (uri, document) ->
                coroutineContext.ensureActive()
                installDir.mkdirs()
                val scratch = File(installDir, "import.part")

                val digest = Imports.copy(context, uri, scratch) { copied ->
                    onProgress(
                        Progress(
                            label = "Importing " + document.name,
                            index = index,
                            total = documents.size,
                            bytesDone = doneBefore + copied,
                            bytesTotal = total,
                        ),
                    )
                }
                doneBefore += document.bytes

                val artifact = ARTIFACTS.firstOrNull { it.sha256.equals(digest, ignoreCase = true) }
                if (artifact == null) {
                    scratch.delete()
                    unmatched += document.name
                    return@forEachIndexed
                }

                val target = File(installDir, artifact.relativePath)
                target.parentFile?.mkdirs()
                target.delete()
                if (!scratch.renameTo(target)) {
                    scratch.delete()
                    error(artifact.label + ": could not move into place")
                }
                Log.i(TAG, "imported " + artifact.relativePath + " from " + document.name)
            }

            check(unmatched.isEmpty()) {
                unmatched.joinToString(", ") +
                    (if (unmatched.size == 1) " is not one" else " are not") +
                    " of the model files this build expects. Checksums have to match " +
                    "exactly; a partial download will not."
            }
            status()
        } }

    /** Free the staged copy once installed; ~117 MB the user gets back. */
    fun clearStaging(): Long {
        var freed = 0L
        stagingDirs.forEach { dir ->
            dir.walkBottomUp().forEach { file ->
                if (file.isFile) {
                    freed += file.length()
                    file.delete()
                } else if (file != dir) {
                    file.delete()
                }
            }
        }
        return freed
    }

    private fun installed(artifact: Artifact): Boolean =
        File(installDir, artifact.relativePath).length() == artifact.bytes

    private fun staged(artifact: Artifact): File? = stagingDirs
        .map { File(it, artifact.relativePath) }
        .firstOrNull { it.length() == artifact.bytes }

}
