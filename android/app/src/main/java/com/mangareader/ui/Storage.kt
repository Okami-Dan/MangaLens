package com.mangareader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mangareader.pipeline.Backup
import com.mangareader.pipeline.ImageRetention
import com.mangareader.pipeline.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.system.exitProcess

/**
 * The state behind "where the library is, and whether the user has a copy of it".
 *
 * [Vault] and [Backup] do the work; this owns the two things that work needs from the app
 * -- a scope that outlives the settings screen, and somewhere for progress to be observed
 * from. All three of the long operations here (adoption, export, import) move a few
 * hundred megabytes on a real shelf, so none of them can be a dialog the user waits on.
 *
 * @param restored what `MainActivity` learned from [Vault.recover] before Room opened.
 *   Reported once, on the launch it happened, because "your library came back" is exactly
 *   the thing a user who has just cleared the app's data needs told.
 */
class StorageController(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: ReaderSettings,
    val restored: Boolean,
) {
    private val appContext = context.applicationContext
    val vault = Vault.get(appContext)
    private val backup = Backup(appContext)

    /** Non-null while an old library is being moved in. */
    var adoption by mutableStateOf<Vault.Progress?>(null)
        private set

    /** How many files the last adoption moved, for the one-line report afterwards. */
    var adopted by mutableStateOf(0)
        private set

    var bytes by mutableStateOf(0L)
        private set

    /** Set by [backUpNow] so the settings screen can confirm something happened. */
    var backedUpAt by mutableStateOf(0L)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val protected: Boolean get() = vault.external

    val location: String get() = vault.root.absolutePath

    /**
     * Move an old install's images in, if there are any. Called once at startup.
     *
     * Idempotent and resumable -- see [Vault.adopt] -- so an app killed mid-move picks up
     * where it stopped on the next launch rather than starting the copy over.
     */
    fun adoptIfNeeded() {
        if (adoption != null) {
            refresh()
            return
        }
        scope.launch {
            runCatching {
                adopted = vault.adopt { adoption = it }
                ImageRetention(appContext).cleanAll()
                adopted
            }
                .onSuccess { adopted = it }
                .onFailure { error = it.message ?: it.toString() }
            adoption = null
            refresh()
            // The paths in the database now resolve against the vault, so the snapshot
            // that describes them belongs next to the files it names.
            vault.snapshotSoon()
        }
    }

    /**
     * "Save the index now" -- a checkpoint the user asked for rather than waited for.
     *
     * Worth being exact about what this is: it writes the *database* into the vault, on
     * the same volume as the images it names. It is not a backup and the button no longer
     * says it is. [exportTo] is the backup.
     */
    fun backUpNow() {
        scope.launch {
            val ok = vault.snapshot()
            if (ok) backedUpAt = System.currentTimeMillis() else error = "Could not save the index"
            refresh()
        }
    }

    /** Non-null while an export or a restore is running. */
    var transfer by mutableStateOf<Vault.Progress?>(null)
        private set

    /** Set when an export finishes, so the card can report it without a snackbar. */
    var exported by mutableStateOf<Backup.Result.Done?>(null)
        private set

    /** When the user last wrote a real backup, or 0. Persisted -- see [ReaderSettings]. */
    val lastBackupAt get() = settings.lastBackupAt

    /**
     * Writes the library to the document the user picked.
     *
     * The stream is opened and closed here rather than inside [Backup], which takes plain
     * streams so that its round trip can be tested where no SAF provider exists.
     */
    fun exportTo(uri: Uri) {
        if (transfer != null) return
        scope.launch {
            error = null
            exported = null
            transfer = Vault.Progress(0, 0, 0, 0)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = appContext.contentResolver.openOutputStream(uri)
                        ?: error("Could not open the file you picked.")
                    // Closing it is part of the write -- a SAF stream flushes to the
                    // provider on close -- so it stays off the main thread too.
                    stream.use { backup.export(it) { progress -> transfer = progress } }
                }
            }.getOrElse { Backup.Result.Failed(it.message ?: it.toString()) }

            when (result) {
                is Backup.Result.Done -> {
                    exported = result
                    settings.setLastBackupAt(System.currentTimeMillis())
                }

                is Backup.Result.Failed -> error = result.reason
            }
            transfer = null
            refresh()
        }
    }

    /**
     * Unpacks a backup and restarts into it.
     *
     * The restart is not politeness about a stale screen: Room is open, its singleton
     * cannot be pointed at another file, and [Vault.installPendingRestore] runs in
     * `MainActivity` before Room is touched. Relaunching is how the app gets back to that
     * moment. [Backup.import] has already staged everything by the time this fires, so a
     * process that dies here restores on the next launch anyway.
     */
    fun importFrom(uri: Uri) {
        if (transfer != null) return
        scope.launch {
            error = null
            transfer = Vault.Progress(0, 0, 0, 0)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val stream = appContext.contentResolver.openInputStream(uri)
                        ?: error("Could not open the file you picked.")
                    stream.use { backup.import(it) { progress -> transfer = progress } }
                }
            }.getOrElse { Backup.Result.Failed(it.message ?: it.toString()) }

            transfer = null
            when (result) {
                is Backup.Result.Done -> restart()
                is Backup.Result.Failed -> {
                    error = result.reason
                    refresh()
                }
            }
        }
    }

    private fun restart() {
        val intent = appContext.packageManager
            .getLaunchIntentForPackage(appContext.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent == null) {
            error = "Restored. Close and reopen the app to load your library."
            return
        }
        appContext.startActivity(intent)
        // The new task is already starting; this process still holds the old database
        // handle, and only its death lets the restore be installed cleanly on the way in.
        exitProcess(0)
    }

    fun refresh() {
        scope.launch {
            bytes = withContext(Dispatchers.IO) { vault.bytes() }
        }
    }
}
