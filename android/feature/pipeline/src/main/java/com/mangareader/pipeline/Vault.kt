package com.mangareader.pipeline

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.room.withTransaction
import com.mangareader.database.MangaDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Where the library lives so that clearing the app's data does not take it.
 *
 * Everything the user cannot photograph again -- the pages themselves, the covers they
 * picked, the OCR output, the words they pinned, the translations tier 2 charged for --
 * used to sit under `context.filesDir`, which Android deletes on uninstall and on
 * *Clear data*. Reinstalling the APK is the normal loop on this project, so the normal
 * loop wiped the library. This class is most of the answer; [Backup] is the rest.
 *
 * The vault is `Android/media/<package>/library`. That directory is app-scoped, needs no
 * storage permission, and is writable by both the app and an `adb shell`. [ModelStore]
 * already stages models next to it, so the access pattern is one this project has run.
 *
 * ## What this survives, and what it does not
 *
 * This class was written believing `Android/media/<package>` outlives an uninstall. **It
 * does not.** On the SM-A736B (Android 14, checked 2026-08-26) a plain `adb uninstall`
 * deleted the directory outright and took the pages, the covers and the staged models with
 * it. `android/README.md` already recorded that behaviour for the instrumentation runner,
 * which uninstalls the app when it finishes; the note that used to be here contradicted it
 * and was wrong. Treat the platform documentation on this as describing an intent that
 * OEMs do not uniformly implement.
 *
 * So, precisely:
 *
 *  - **Survives *Clear data*.** That is the guarantee this class does keep, and it is not
 *    nothing -- it is the difference between a settings mis-tap and a lost shelf.
 *  - **Survives reinstalling over the top**, which is the build loop, and is why
 *    `gradlew installDebug` all day costs the user nothing.
 *  - **Does not survive an uninstall**, on this device and probably others. Nor a factory
 *    reset, nor an OEM uninstall dialog that offers to clear shared storage too.
 *
 * The thing that survives all of those is a copy the *user* put somewhere: [Backup] writes
 * the library to a document they choose, and the settings screen must not describe the
 * vault as a substitute for having done that.
 *
 * ## One copy, not two
 *
 * The images *move* here; they are not mirrored. After [adopt] there is exactly one copy
 * of every page on the phone, in the vault, and the database stores paths relative to it
 * ([file] resolves them). Three things follow from that, all deliberate:
 *
 *  - **No symlinks back into `filesDir`.** An app cannot create a symlink on the
 *    FUSE-emulated volume and a cross-filesystem link would not be honoured there. One
 *    location is the only version of this that actually works.
 *  - **No archive around the images.** JPEG is already entropy-coded; zipping the pages
 *    would save nothing measurable and would put a decompress in front of every render.
 *    Compression is used exactly where it pays -- the database snapshot, which is text
 *    and gzips by roughly ten to one.
 *  - **A `.nomedia` marker at the package root**, so MediaStore never indexes any of
 *    this. Without it every captured page turns up in Photos and gets offered to the
 *    cloud, which is the same library a second time in the place the user least expects.
 *
 * The one thing that does exist twice is the database, and only because SQLite's file
 * locking is not dependable on the emulated volume: the live database stays app-private
 * and the vault holds a checkpointed [snapshot]. That is a few megabytes against
 * hundreds, and it is what [recover] reads on the first launch after *Clear data*.
 *
 * ## Not a backup
 *
 * Nothing above makes this a backup, and the wording on the settings screen has to keep
 * saying so. One copy of a library, on the phone that library is about, is one drop away
 * from gone whatever directory it is in. [Backup] is the export this class always pointed
 * at and never had.
 */
class Vault(context: Context) {

    private val appContext = context.applicationContext

    /**
     * The external package directory, or null when no external volume is mounted.
     *
     * `externalMediaDirs` is deprecated in favour of the MediaStore, which is right for
     * media the user is meant to browse and wrong for this: MediaStore has no notion of
     * "mine, app-scoped, and still here after the app's data is cleared", which is the
     * property being bought.
     */
    @Suppress("DEPRECATION")
    private val packageDir: File? =
        appContext.externalMediaDirs
            .filterNotNull()
            .firstOrNull { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }

    /**
     * True when the library is somewhere *Clear data* cannot reach.
     *
     * False on a device with no mounted external volume. The app still works -- [root]
     * falls back to `filesDir`, which is where everything used to live -- it just loses
     * the guarantee, and the settings screen says so rather than claiming a safety it
     * does not have. Either way [Backup] is what covers an uninstall.
     */
    val external: Boolean get() = packageDir != null

    /** The vault root. Every path in the database is relative to this. */
    val root: File = packageDir?.let { File(it, LIBRARY) } ?: appContext.filesDir

    val pagesDir: File get() = File(root, PAGES).apply { mkdirs() }

    val coversDir: File get() = File(root, COVERS).apply { mkdirs() }

    /** Where a legacy install left its files: `filesDir/pages` and `filesDir/covers`. */
    private val legacyPages = File(appContext.filesDir, PAGES)
    private val legacyCovers = File(appContext.filesDir, COVERS)

    /** Where Room keeps the live database. Internal so [Backup] can copy it out. */
    internal val databaseFile: File get() = appContext.getDatabasePath(DATABASE)

    private val snapshotFile: File get() = File(root, SNAPSHOT)

    /**
     * Where [Backup.import] leaves a restored database for [installPendingRestore].
     *
     * Not the live path, because Room is open by the time a restore can be asked for and
     * overwriting the file underneath it corrupts both. Not the snapshot path either: a
     * restore has to beat [recover], which only fires when there is no database at all.
     *
     * Public rather than internal because its existence is a fact about the app that
     * outlives this module -- a restore staged but not yet installed means the next launch
     * will replace the library, which is worth being able to ask about.
     */
    val restoreStaging: File get() = File(root, RESTORE)

    /** Owns the background snapshots. See [snapshotSoon] for why it is not the caller's. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pending: Job? = null

    /** Serializes image changes with backup export, restore staging and snapshots. */
    suspend fun <T> withLibraryLock(block: suspend () -> T): T = libraryMutex.withLock { block() }

    init {
        // Cheap, idempotent, and the consequence of skipping it is the user's manga in
        // their camera roll -- so it happens on construction rather than on first write.
        packageDir?.let { dir ->
            val marker = File(dir, ".nomedia")
            if (!marker.exists()) {
                runCatching {
                    dir.mkdirs()
                    marker.createNewFile()
                }.onFailure { Log.w(TAG, "could not write .nomedia", it) }
            }
        }
    }

    /**
     * Resolves a stored path.
     *
     * Two fallbacks, both for the same reason: the database is rewritten by migration
     * 5->6 in one transaction, and the files are moved by [adopt] over however long a few
     * hundred megabytes take. Between those, a row already says `pages/ab12.jpg` while
     * the JPEG is still in `filesDir`, and an adoption interrupted by the user leaving
     * the app leaves some rows on each side of the line indefinitely.
     *
     *  - an absolute path is returned as it stands, because a row written by an older
     *    build -- or by a fixture test that stored a path of its own -- means what it
     *    says and would resolve to nonsense underneath the vault;
     *  - a relative path with nothing at it is looked for in the old location before it
     *    is given up on, so a half-adopted library reads correctly rather than showing
     *    blank cards until the move finishes.
     */
    fun file(relative: String): File {
        if (relative.startsWith("/")) return File(relative)
        val inVault = File(root, relative)
        if (!external || inVault.exists()) return inVault
        val legacy = File(appContext.filesDir, relative)
        return if (legacy.exists()) legacy else inVault
    }

    /**
     * The reader's downscaled copy of a page, or the full-resolution one when there is
     * none. Derived from the stored path rather than held in a column of its own: it is
     * the same file with a suffix, and a column that can only ever hold one value is a
     * column that can disagree with the disk.
     */
    fun display(relative: String): File {
        val full = file(relative)
        if (full.name.endsWith(DISPLAY_SUFFIX)) return full
        val display = File(full.parentFile, full.nameWithoutExtension + DISPLAY_SUFFIX)
        return if (display.exists()) display else full
    }

    /** Relative form of a file already inside the vault, for storing in the database. */
    fun relative(file: File): String {
        val prefix = root.absolutePath + File.separator
        val path = file.absolutePath
        return if (path.startsWith(prefix)) {
            path.removePrefix(prefix).replace(File.separatorChar, '/')
        } else {
            path
        }
    }

    data class Progress(
        val done: Int,
        val total: Int,
        val bytesDone: Long,
        val bytesTotal: Long,
    ) {
        val fraction: Float get() = if (bytesTotal <= 0) 0f else bytesDone.toFloat() / bytesTotal
    }

    /** Files still sitting in `filesDir` waiting to be moved in. Empty once adopted. */
    fun pendingAdoption(): List<File> =
        if (!external) {
            emptyList()
        } else {
            listOf(legacyPages, legacyCovers)
                .filter { it.isDirectory }
                .flatMap { it.listFiles()?.asList().orEmpty() }
                .filter { it.isFile && !it.name.endsWith(".part") }
        }

    /**
     * Moves an old install's images into the vault. Safe to call on every launch.
     *
     * A move, not a copy: the source is deleted once the destination is complete, so the
     * phone never holds two copies of a library. Resumable, because it is one file at a
     * time and a destination that already exists at the right length is skipped -- an
     * adoption killed halfway through by the user leaving the app picks up where it
     * stopped rather than starting over.
     */
    suspend fun adopt(onProgress: (Progress) -> Unit = {}): Int = withLibraryLock {
        adoptUnlocked(onProgress)
    }

    private suspend fun adoptUnlocked(onProgress: (Progress) -> Unit): Int = withContext(Dispatchers.IO) {
        val work = pendingAdoption()
        if (work.isEmpty()) return@withContext 0

        val bytesTotal = work.sumOf { it.length() }
        var bytesDone = 0L
        var moved = 0

        work.forEachIndexed { index, source ->
            coroutineContext.ensureActive()
            val directory = if (source.parentFile?.name == COVERS) coversDir else pagesDir
            val target = File(directory, source.name)
            val size = source.length()
            onProgress(Progress(index, work.size, bytesDone, bytesTotal))
            if (move(source, target)) moved++
            bytesDone += size
        }
        onProgress(Progress(work.size, work.size, bytesTotal, bytesTotal))

        // Only the directories, and only if the loop emptied them. A file left behind by
        // a failed move keeps its folder, and the next launch tries it again.
        listOf(legacyPages, legacyCovers).forEach { if (it.isDirectory) it.delete() }
        Log.i(TAG, "adopted " + moved + " of " + work.size + " files into " + root)
        moved
    }

    /**
     * Folds the write-ahead log back into the `.db` file, so that copying it copies
     * everything.
     *
     * Not optional, for either caller. Room runs in WAL mode, so recent writes -- the
     * series created a minute ago, the word just pinned -- live in `-wal` and not in the
     * `.db` file at all. Copying the `.db` without checkpointing first takes a database
     * missing exactly the work the user would most notice losing.
     */
    internal fun checkpoint(database: MangaDatabase = MangaDatabase.get(appContext)) {
        database.openHelper.writableDatabase
            .query("PRAGMA wal_checkpoint(TRUNCATE)")
            .use { check(it.moveToFirst() && it.getInt(0) == 0) { "Library checkpoint is busy" } }
    }

    /**
     * Writes the database into the vault, so that *Clear data* has something to come back
     * to.
     */
    suspend fun snapshot(): Boolean = withLibraryLock { snapshotUnlocked() }

    /** Caller must hold [withLibraryLock], including until obsolete files are deleted. */
    internal suspend fun snapshotUnlocked(
        database: MangaDatabase = MangaDatabase.get(appContext),
    ): Boolean = withContext(Dispatchers.IO) {
        if (!external) return@withContext false
        runCatching {
            checkpoint(database)
            database.withTransaction {
                val source = databaseFile
                if (!source.isFile) return@withTransaction false

                root.mkdirs()
                val part = File(root, SNAPSHOT + ".part")
                source.inputStream().buffered(BUFFER).use { input ->
                    GZIPOutputStream(part.outputStream().buffered(BUFFER)).use { output ->
                        input.copyTo(output, BUFFER)
                    }
                }
                if (!part.renameTo(snapshotFile)) {
                    part.delete()
                    error("could not move the snapshot into place")
                }
                Log.i(TAG, "snapshot " + snapshotFile.length() + " bytes from " + source.length())
                true
            }
        }.onFailure { Log.w(TAG, "snapshot failed", it) }.getOrDefault(false)
    }

    /**
     * [snapshot] without waiting for it, coalesced so that a burst of triggers costs one
     * write.
     *
     * The callers are lifecycle edges -- the activity stopping, a page finishing its
     * analysis -- and neither can hold a scope long enough to be sure the copy lands:
     * `lifecycleScope` is cancelled on the way out of exactly the transition that matters
     * most. So the scope lives here, on a process singleton. Dropping a trigger is
     * harmless (the next one picks the same database up), and a cancelled copy leaves the
     * previous snapshot in place because [snapshot] writes through a `.part` file.
     */
    fun snapshotSoon() {
        if (!external) return
        if (pending?.isActive == true) return
        pending = scope.launch { snapshot() }
    }

    /**
     * Promotes a database [Backup.import] staged, over whatever is there now.
     *
     * **Must run before anything opens Room, and before [recover].** It shares that slot
     * for the same reason `recover` needs it -- Room creates and caches its handle on
     * first access -- and it goes first because a restore the user explicitly asked for
     * outranks a snapshot the app took on its own.
     *
     * Unconditional, unlike [recover]: the user picked a file and tapped restore, so an
     * existing library is the thing being replaced, not a reason to decline. The `-wal`
     * and `-shm` are deleted for the same reason [recover] deletes them -- replaying the
     * old log over the new file would undo precisely what was just restored.
     *
     * Works with no external volume, because a backup lives on neither and restoring one
     * must not depend on a directory this class has learnt not to trust.
     *
     * @return true when a restore was installed, for the note the app shows afterwards
     */
    fun installPendingRestore(): Boolean {
        val staged = restoreStaging
        if (!staged.isFile) return false
        val target = databaseFile

        return runCatching {
            target.parentFile?.mkdirs()
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            target.delete()

            // A rename first, because when the vault has fallen back to `filesDir` both
            // paths are on the same filesystem and it is free. Across the emulated volume
            // and internal storage it cannot work, and there the copy is the move.
            if (!staged.renameTo(target)) {
                staged.copyTo(target, overwrite = true)
                check(target.length() == staged.length()) { "short copy of the restore" }
                staged.delete()
            }

            // The snapshot beside it describes the library that was just replaced.
            snapshotFile.delete()
            Log.i(TAG, "installed a restored library of " + target.length() + " bytes")
            true
        }.onFailure {
            Log.w(TAG, "could not install the restored library", it)
            staged.delete()
        }.getOrDefault(false)
    }

    /**
     * Restores the snapshot when the app has no database and the vault has one -- which
     * is precisely the state *Clear data* leaves behind.
     *
     * **Must run before anything opens Room.** Room creates the file on first access, and
     * a zero-row database that exists is indistinguishable from a library that was never
     * there. `MainActivity` calls this before `setContent`.
     *
     * @return true when a library was restored, for the one-time note the app shows
     */
    fun recover(): Boolean {
        if (!external) return false
        val snapshot = snapshotFile
        val target = databaseFile
        if (!snapshot.isFile || target.exists()) return false

        return runCatching {
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, DATABASE + ".part")
            GZIPInputStream(snapshot.inputStream().buffered(BUFFER)).use { input ->
                part.outputStream().buffered(BUFFER).use { output -> input.copyTo(output, BUFFER) }
            }
            // A fresh install has neither, but a -wal left over from an install that was
            // cleared rather than removed would be replayed over the restored file and
            // undo the restore.
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            if (!part.renameTo(target)) {
                part.delete()
                error("could not move the restored database into place")
            }
            Log.i(TAG, "recovered " + target.length() + " bytes of library from the vault")
            true
        }.onFailure { Log.w(TAG, "recovery failed", it) }.getOrDefault(false)
    }

    /** What the vault occupies, for the settings screen's storage readout. */
    fun bytes(): Long =
        if (!root.isDirectory) {
            0L
        } else {
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

    /**
     * Copy-then-delete, because [File.renameTo] cannot cross a filesystem and internal
     * storage and the emulated volume are two. The length check before the source is
     * removed is what makes this a move rather than a gamble.
     */
    private suspend fun move(source: File, target: File): Boolean {
        if (target.isFile && target.length() == source.length()) {
            source.delete()
            return true
        }
        val part = File(target.parentFile, target.name + ".part")
        return runCatching {
            source.inputStream().buffered(BUFFER).use { input ->
                part.outputStream().buffered(BUFFER).use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(part.length() == source.length()) { "short copy of " + source.name }
            target.delete()
            check(part.renameTo(target)) { "could not move " + source.name + " into place" }
            source.delete()
            true
        }.onFailure {
            part.delete()
            Log.w(TAG, "could not adopt " + source.name, it)
        }.getOrDefault(false)
    }

    companion object {
        private val libraryMutex = Mutex()
        private const val TAG = "Vault"

        @Volatile
        private var instance: Vault? = null

        /**
         * The shared vault, in the shape [MangaDatabase.get] already uses.
         *
         * A singleton because construction is not quite free -- it probes the external
         * volumes and writes the `.nomedia` marker -- and because the library grid asks
         * for one per card. Nothing here holds an Activity: the constructor takes the
         * application context off whatever it is handed.
         */
        fun get(context: Context): Vault = instance ?: synchronized(this) {
            instance ?: Vault(context).also { instance = it }
        }

        /** Subdirectories of [root]; also the prefixes migration 5->6 writes. */
        const val PAGES = "pages"
        const val COVERS = "covers"

        const val DISPLAY_SUFFIX = "_display.jpg"

        private const val LIBRARY = "library"
        private const val SNAPSHOT = "library.db.gz"

        /** Where a restore waits between [Backup.import] and the next launch. */
        private const val RESTORE = "restore.db"
        private const val DATABASE = "manga-reader.db"
        private const val BUFFER = 1 shl 16
    }
}
