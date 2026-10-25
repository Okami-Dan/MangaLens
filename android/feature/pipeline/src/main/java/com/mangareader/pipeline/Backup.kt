package com.mangareader.pipeline

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.mangareader.database.MangaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * The library as one file the user keeps, somewhere the package manager cannot reach.
 *
 * [Vault] was supposed to be this and is not. It moves the library to
 * `Android/media/<package>`, on the premise that the directory outlives an uninstall --
 * and on the SM-A736B (Android 14) it does not: a plain `adb uninstall` took the whole
 * directory, pages and covers and staged models together. `android/README.md` had already
 * recorded that behaviour for the instrumentation runner while the vault's own class note
 * claimed the opposite. This class is the half that was missing, and the vault's
 * "What this does not protect against" section names it exactly: an export to a location
 * the user chooses.
 *
 * That location is the whole point. Anywhere the app can write unaided is somewhere
 * Android is entitled to clear when the package goes; only a document the *user* placed --
 * in Downloads, on a memory card, in Drive, on a PC over USB -- sits outside that. So what
 * this class writes to is a `content://` document from the storage-access framework, and
 * where it lands is deliberately none of the app's business.
 *
 * ## Streams, not Uris
 *
 * Everything here takes an [InputStream] or an [OutputStream] and lets the caller resolve
 * the document. [Imports] already splits this way -- it turns a Uri into a stream and
 * hands it to [Downloads.copy] -- and the reason is the same twice over: there is no SAF
 * provider inside an instrumented test, so a class that took Uris could not have the one
 * thing worth testing about it, the round trip, tested at all.
 *
 * ## The archive
 *
 * A plain ZIP, so that a user who no longer has the app -- or no longer has the phone --
 * can still open it on a desktop and find their pages as JPEGs:
 *
 *  - `manifest.txt` first, because [import] must read it before it can decide whether the
 *    rest is safe to unpack, and a ZIP read as a stream arrives in write order;
 *  - `library.db`, checkpointed (see [Vault.checkpoint]) and deflated, which is where
 *    compression actually pays -- a database of Japanese text and paths goes to roughly a
 *    tenth of its size;
 *  - `pages/...` and `covers/...` written at [Deflater.NO_COMPRESSION]. [Vault]'s note
 *    argues against archiving the images at all, but that argument is about the *live*
 *    store, where a decompress would sit in front of every render. Nothing renders from
 *    here. What survives of it is the other half: JPEG is already entropy-coded, so
 *    deflating it burns CPU across hundreds of megabytes to save nothing measurable.
 */
class Backup(
    context: Context,
    /**
     * Defaulted to the shared vault, which is what the app wants, and injectable because
     * `VaultTest` builds one over a private directory -- a test that took the singleton
     * would archive the device's real library to prove a point about zip entries.
     */
    private val vault: Vault = Vault.get(context),
    private val database: MangaDatabase = MangaDatabase.get(context),
) {

    private val appContext = context.applicationContext

    /**
     * What happened, in a form the settings card can render without knowing about zips.
     *
     * A sealed result rather than a thrown exception, because every failure here is one
     * the user is meant to read: a picked file that is not a backup, a backup from a build
     * newer than this one, a card pulled out mid-write.
     */
    sealed interface Result {
        data class Done(val files: Int, val bytes: Long) : Result

        data class Failed(val reason: String) : Result
    }

    /**
     * Writes the whole library into [output].
     *
     * The caller owns [output] and closes it; this does not, because a SAF stream closed
     * twice throws on some providers.
     */
    suspend fun export(
        output: OutputStream,
        onProgress: (Vault.Progress) -> Unit = {},
    ): Result = vault.withLibraryLock { exportUnlocked(output, onProgress) }

    private suspend fun exportUnlocked(
        output: OutputStream, onProgress: (Vault.Progress) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        runCatching {
            // Room runs in WAL mode, so without this the copy is missing exactly the work
            // the user did most recently -- see Vault.checkpoint.
            vault.checkpoint(database)

            val images = libraryFiles()
            val libraryIndex = vault.databaseFile.takeIf { it.isFile }
            val bytesTotal = images.sumOf { it.length() } + (libraryIndex?.length() ?: 0L)
            val total = images.size + 1
            var bytesDone = 0L
            var written = 0

            ZipOutputStream(output.buffered(BUFFER)).use { zip ->
                zip.setLevel(Deflater.DEFAULT_COMPRESSION)

                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(manifest(files = images.size, bytes = bytesTotal).toByteArray())
                zip.closeEntry()

                if (libraryIndex != null) {
                    database.withTransaction {
                        zip.putNextEntry(ZipEntry(DATABASE))
                        libraryIndex.inputStream().buffered(BUFFER).use { it.copyTo(zip, BUFFER) }
                        zip.closeEntry()
                        written++
                        bytesDone += libraryIndex.length()
                        onProgress(Vault.Progress(written, total, bytesDone, bytesTotal))
                    }
                }

                // Everything past here is a JPEG. See the class note.
                zip.setLevel(Deflater.NO_COMPRESSION)
                images.forEach { file ->
                    coroutineContext.ensureActive()
                    zip.putNextEntry(ZipEntry(vault.relative(file)))
                    file.inputStream().buffered(BUFFER).use { it.copyTo(zip, BUFFER) }
                    zip.closeEntry()
                    written++
                    bytesDone += file.length()
                    onProgress(Vault.Progress(written, total, bytesDone, bytesTotal))
                }
                zip.finish()
            }

            Log.i(TAG, "exported " + written + " files, " + bytesTotal + " bytes")
            Result.Done(written, bytesTotal) as Result
        }.getOrElse {
            Log.w(TAG, "export failed", it)
            Result.Failed(it.message ?: it.toString())
        }
    }

    /**
     * Unpacks an archive from [input] back into the vault.
     *
     * **The images go in first and the database last**, and the database does not go where
     * Room reads it -- it is staged for [Vault.installPendingRestore] to promote on the
     * next launch. Two separate reasons, both about what a half-finished restore leaves:
     *
     *  - a restore killed partway through must not produce a library full of rows naming
     *    pages that were never unpacked, which is what writing the index first would give;
     *  - Room is open by the time the user can tap Restore, and its singleton cannot be
     *    pointed at a different file. Staging hands the swap to the one place in the app
     *    that provably runs before Room does.
     *
     * @return [Result.Done] when the caller should restart the process into the restore
     */
    suspend fun import(
        input: InputStream,
        onProgress: (Vault.Progress) -> Unit = {},
    ): Result = vault.withLibraryLock { importUnlocked(input, onProgress) }

    private suspend fun importUnlocked(
        input: InputStream, onProgress: (Vault.Progress) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val staged = mutableListOf<File>()
        runCatching {
            var header: Manifest? = null
            var bytesDone = 0L
            var written = 0

            ZipInputStream(input.buffered(BUFFER)).use { zip ->
                while (true) {
                    coroutineContext.ensureActive()
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }
                    val name = entry.name
                    if (name == MANIFEST) {
                        header = readManifest(zip.readBytes().decodeToString())
                    } else {
                        // Nothing is written before the manifest has been read and
                        // accepted, so a file that merely happens to be a zip cannot get
                        // as far as putting bytes in the vault.
                        checkNotNull(header) { NOT_A_BACKUP }
                        val target = if (name == DATABASE) {
                            vault.restoreStaging
                        } else {
                            // An allowlist, not a `..` check. The archive is a file the
                            // user could have been handed by anyone, and the only names
                            // this app ever writes are one level deep under two known
                            // parents -- so anything else is refused, not sanitized.
                            require(SAFE.matches(name)) { "unexpected entry " + name }
                            File(vault.root, name)
                        }
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered(BUFFER).use { zip.copyTo(it, BUFFER) }
                        staged += target
                        written++
                        bytesDone += target.length()
                    }
                    zip.closeEntry()
                    onProgress(
                        Vault.Progress(
                            done = written,
                            total = header?.files?.plus(1) ?: written,
                            bytesDone = bytesDone,
                            bytesTotal = header?.bytes ?: bytesDone,
                        ),
                    )
                }
            }

            val read = checkNotNull(header) { NOT_A_BACKUP }
            check(vault.restoreStaging.isFile) { "That backup has no library index in it." }
            Log.i(TAG, "imported " + written + " files from a backup taken " + read.created)
            Result.Done(written, bytesDone) as Result
        }.getOrElse { failure ->
            // A failed restore leaves nothing behind. Half a library is worse than none:
            // it looks like it worked.
            staged.forEach { it.delete() }
            Log.w(TAG, "import failed", failure)
            Result.Failed(failure.message ?: failure.toString())
        }
    }

    /** Pages and covers, skipping the `.part` files a killed copy leaves behind. */
    private fun libraryFiles(): List<File> =
        listOf(vault.pagesDir, vault.coversDir)
            .filter { it.isDirectory }
            .flatMap { it.listFiles()?.asList().orEmpty() }
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sortedBy { it.name }

    private data class Manifest(
        val schema: Int,
        val files: Int,
        val bytes: Long,
        val created: String,
    )

    /**
     * `key=value` lines, the shape `app/build.gradle.kts` already writes for `jmdict.meta`.
     */
    private fun manifest(files: Int, bytes: Long): String =
        "format=" + FORMAT + "\n" +
            "app=" + versionName() + "\n" +
            "schema=" + schemaVersion() + "\n" +
            "files=" + files + "\n" +
            "bytes=" + bytes + "\n" +
            "created=" + stamp() + "\n"

    /**
     * The schema version is the one field with teeth.
     *
     * A backup taken by a newer build can hold tables and columns this one has never heard
     * of, and Room migrates forwards only. Restoring it would either fail on open or -- far
     * worse -- be dropped and rebuilt empty, which is the user's library deleted by the
     * button labelled restore. Older is fine and is the ordinary case: Room migrates it.
     */
    private fun readManifest(text: String): Manifest {
        val fields = text.lineSequence()
            .mapNotNull { line ->
                val split = line.indexOf('=')
                if (split <= 0) null else line.take(split) to line.drop(split + 1).trim()
            }
            .toMap()

        val format = fields["format"]?.toIntOrNull()
        require(format != null) { NOT_A_BACKUP }
        require(format <= FORMAT) {
            "That backup was written by a newer version of the app (format " + format + ")."
        }

        val schema = fields["schema"]?.toIntOrNull() ?: 0
        val current = schemaVersion()
        require(schema <= current) {
            "That backup is from a newer version of the app (library format " + schema +
                ", this build reads " + current + "). Update the app and try again."
        }

        return Manifest(
            schema = schema,
            files = fields["files"]?.toIntOrNull() ?: 0,
            bytes = fields["bytes"]?.toLongOrNull() ?: 0L,
            created = fields["created"].orEmpty(),
        )
    }

    private fun schemaVersion(): Int =
        runCatching { database.openHelper.readableDatabase.version }
            .getOrDefault(0)

    private fun versionName(): String =
        runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull().orEmpty()

    companion object {
        private const val TAG = "Backup"

        private const val NOT_A_BACKUP = "That file is not a MangaLens backup."

        /** Bumped only for a change [import] could not otherwise survive. */
        private const val FORMAT = 1

        private const val MANIFEST = "manifest.txt"
        private const val DATABASE = "library.db"
        private const val BUFFER = 1 shl 16

        /** `pages/<name>` or `covers/<name>`: one level deep, no separator in the name. */
        private val SAFE = Regex("^(" + Vault.PAGES + "|" + Vault.COVERS + ")/[^/\\\\]+$")

        /** What the document picker opens with. Sorts chronologically in a file list. */
        fun suggestedName(now: Long = System.currentTimeMillis()): String =
            "mangalens-library-" +
                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) + ".zip"

        private fun stamp(): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
    }
}
