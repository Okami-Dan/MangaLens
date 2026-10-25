package com.mangareader

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangareader.database.MangaDatabase
import com.mangareader.pipeline.Backup
import com.mangareader.pipeline.Vault
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The vault's two irreversible moments: moving an existing library out of app storage,
 * and putting one back after a reinstall.
 *
 * Both run against a [Harness] context rather than the real one. The point of the class
 * is what it does to files on disk, and a test that adopted the *actual* library would
 * move the device's own pages around to prove it -- so this gives it a private
 * `filesDir`, a private external directory and a private database path, and checks the
 * bytes.
 *
 * The one thing not covered here is the round trip that matters most, because no
 * instrumented test can perform it: uninstall the app, install it again, and find the
 * shelf intact. That is the manual check, and the pieces below are what it is made of.
 */
@RunWith(AndroidJUnit4::class)
class VaultTest {

    /**
     * A context with somewhere private to put things.
     *
     * [getApplicationContext] has to be overridden too: [Vault] takes it on construction,
     * and a plain `ContextWrapper` hands back the real `Application` -- which would send
     * every path in the test straight back to the directories it is trying to avoid.
     *
     * The external root is a subdirectory of the real one rather than a temp path
     * anywhere: `Vault` only accepts a directory that `Environment.getExternalStorageState`
     * reports as mounted, which is a property of where it is, not of the app.
     */
    private class Harness(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getExternalMediaDirs(): Array<File> =
            arrayOf(File(root, "external").apply { mkdirs() })

        override fun getDatabasePath(name: String): File =
            File(root, "databases").apply { mkdirs() }.let { File(it, name) }
    }

    private lateinit var root: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val external = checkNotNull(target.externalMediaDirs.firstOrNull()) {
            "no external media directory on this device"
        }
        root = File(external, "vault-test")
        root.deleteRecursively()
        root.mkdirs()
        context = Harness(target, root)

        // Pin Room's singleton to the real context before the harness can claim it.
        // `Vault.checkpoint` -- which `Backup.export` calls -- goes through
        // `MangaDatabase.get`, and that caches the first context it is handed for the
        // life of the process. Left to chance it would either bind the whole test run to
        // this throwaway directory or try to open the fixture bytes below as a database.
        MangaDatabase.get(target)
    }

    @Test
    fun adopt_movesTheLibraryAndLeavesExactlyOneCopy() = runBlocking {
        val vault = Vault(context)
        assertTrue("the harness directory should count as external", vault.external)

        val legacyPage = File(context.filesDir, "pages/ab12.jpg").also { it.parentFile?.mkdirs() }
        legacyPage.writeBytes(ByteArray(4096) { it.toByte() })
        val legacyCover = File(context.filesDir, "covers/s1.jpg").also { it.parentFile?.mkdirs() }
        legacyCover.writeBytes(ByteArray(512) { 7 })

        val moved = vault.adopt()

        assertEquals(2, moved)
        assertTrue("the page should be in the vault", vault.file("pages/ab12.jpg").isFile)
        assertTrue("the cover should be in the vault", vault.file("covers/s1.jpg").isFile)
        assertEquals(4096L, vault.file("pages/ab12.jpg").length())

        // A move, not a copy. The whole storage argument for the vault rests on there
        // being one of each file on the phone afterwards, not two.
        assertFalse("the old page should be gone", legacyPage.exists())
        assertFalse("the old cover should be gone", legacyCover.exists())
    }

    @Test
    fun adopt_isIdempotentAndResumable() = runBlocking {
        val vault = Vault(context)
        val legacy = File(context.filesDir, "pages/ab12.jpg").also { it.parentFile?.mkdirs() }
        legacy.writeBytes(ByteArray(1024))

        assertEquals(1, vault.adopt())
        assertEquals("nothing left to move", 0, vault.adopt())
        assertTrue(vault.pendingAdoption().isEmpty())

        // The half-moved case: the destination already exists at the right length, which
        // is what an adoption killed between the copy and the delete leaves behind. The
        // source goes; the destination is not rewritten.
        //
        // The parent has to be recreated first. `adopt` deletes `pages/` once it empties
        // it, so by here the legacy directory is gone and writing straight into it fails
        // with ENOENT -- which is the vault behaving correctly and the test having to
        // stage the scenario properly rather than anything to fix in `Vault`.
        legacy.parentFile?.mkdirs()
        legacy.writeBytes(ByteArray(1024))
        assertEquals(1, vault.adopt())
        assertFalse(legacy.exists())
    }

    @Test
    fun recover_restoresTheSnapshotOnlyWhenThereIsNoDatabase() {
        val vault = Vault(context)
        val payload = "SQLite format 3\u0000 and then some rows".toByteArray()

        val snapshot = File(vault.root, "library.db.gz").also { it.parentFile?.mkdirs() }
        GZIPOutputStream(snapshot.outputStream()).use { it.write(payload) }

        val database = context.getDatabasePath("manga-reader.db")
        assertFalse(database.exists())
        assertTrue("a missing database and a present snapshot means restore", vault.recover())
        assertArrayEqualsBytes(payload, database.readBytes())

        // And never over a database that is already there: on every launch after the
        // first, the app-private copy is the live one and the snapshot is behind it.
        database.writeBytes("newer".toByteArray())
        assertFalse(vault.recover())
        assertEquals("newer", database.readText())
    }

    @Test
    fun file_resolvesRelativePathsAndLeavesAbsoluteOnesAlone() {
        val vault = Vault(context)
        assertEquals(File(vault.root, "pages/ab12.jpg"), vault.file("pages/ab12.jpg"))

        // Rows written before migration 5->6 still hold an absolute path. Resolving one
        // underneath the vault would produce a path that exists nowhere.
        val absolute = "/data/user/0/com.mangareader/files/pages/ab12.jpg"
        assertEquals(File(absolute), vault.file(absolute))
    }

    @Test
    fun display_prefersTheReadersCopyAndFallsBackToTheFullPage() {
        val vault = Vault(context)
        val full = File(vault.pagesDir, "ab12.jpg").also { it.writeBytes(ByteArray(8)) }
        assertEquals(full, vault.display("pages/ab12.jpg"))

        val display = File(vault.pagesDir, "ab12_display.jpg").also { it.writeBytes(ByteArray(4)) }
        assertEquals(display, vault.display("pages/ab12.jpg"))
    }

    /**
     * The round trip the vault could never make: out to a file, and back from it.
     *
     * This is the test that covers the actual bug. `Vault` moves the library to
     * `Android/media`, which an uninstall deletes on this device, so the only thing that
     * survives one is a document the user put somewhere themselves. Everything below the
     * stream boundary is testable; the picker on top of it is not.
     */
    @Test
    fun backup_roundTripsTheLibraryAndStagesTheDatabase() = runBlocking {
        val vault = Vault(context)
        val backup = Backup(context, vault)

        val page = File(vault.pagesDir, "ab12.jpg").also { it.writeBytes(ByteArray(4096) { i -> i.toByte() }) }
        val cover = File(vault.coversDir, "s1.jpg").also { it.writeBytes(ByteArray(512) { 7 }) }
        val index = context.getDatabasePath("manga-reader.db").also {
            it.parentFile?.mkdirs()
            it.writeBytes("SQLite format 3 pretend this is a library".toByteArray())
        }

        val archive = ByteArrayOutputStream()
        val exported = backup.export(archive)
        assertTrue("export said: " + exported, exported is Backup.Result.Done)

        // The images are gone from the phone; the archive is all that is left of them,
        // which is the state a user is in after the uninstall that started all this.
        page.delete()
        cover.delete()
        index.delete()

        val imported = backup.import(ByteArrayInputStream(archive.toByteArray()))
        assertTrue("import said: " + imported, imported is Backup.Result.Done)

        assertArrayEqualsBytes(ByteArray(4096) { it.toByte() }, vault.file("pages/ab12.jpg").readBytes())
        assertArrayEqualsBytes(ByteArray(512) { 7 }, vault.file("covers/s1.jpg").readBytes())

        // The database is staged, not live: Room is open by the time a restore can be
        // asked for, so the swap belongs to the next launch.
        assertFalse("the live database must not be touched by an import", index.exists())
        assertTrue(vault.restoreStaging.isFile)

        assertTrue(vault.installPendingRestore())
        assertEquals("SQLite format 3 pretend this is a library", index.readText())
        assertFalse("the staged copy should be consumed", vault.restoreStaging.exists())
    }

    /**
     * A backup from a build newer than this one is refused rather than restored.
     *
     * Room migrates forwards only. Handing it a database with tables this build has never
     * heard of ends either in a crash on open or -- because a future maintainer may well
     * reach for `fallbackToDestructiveMigration` to make that crash go away -- in the
     * library being dropped and rebuilt empty by the button labelled restore.
     */
    @Test
    fun backup_refusesAnArchiveFromANewerBuild() = runBlocking {
        val vault = Vault(context)
        val backup = Backup(context, vault)

        val archive = zip(
            "schema=9999",
            "pages/later.jpg" to ByteArray(16) { 3 },
        )

        val result = backup.import(ByteArrayInputStream(archive))
        assertTrue(result is Backup.Result.Failed)
        assertFalse("nothing may be written from a refused archive", File(vault.pagesDir, "later.jpg").exists())
        assertFalse(vault.restoreStaging.exists())
    }

    /**
     * An entry that tries to climb out of the vault is refused, and takes the whole
     * restore with it.
     *
     * The archive is a file the user could have been handed by anyone, so `Backup` matches
     * entry names against an allowlist rather than screening for `..`.
     */
    @Test
    fun backup_refusesAnEntryThatEscapesTheVault() = runBlocking {
        val vault = Vault(context)
        val backup = Backup(context, vault)

        val escapee = File(vault.root.parentFile, "escaped.txt")
        escapee.delete()

        val archive = zip(
            "schema=1",
            "../escaped.txt" to "no".toByteArray(),
        )

        val result = backup.import(ByteArrayInputStream(archive))
        assertTrue(result is Backup.Result.Failed)
        assertFalse("an entry outside the vault must not be written", escapee.exists())
    }

    /** A hand-built archive, for the cases a real export cannot produce. */
    private fun zip(schema: String, vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.txt"))
            val manifest = listOf("format=1", schema, "files=1", "bytes=16", "created=now")
            zip.write(manifest.joinToString(separator = "\n", postfix = "\n").toByteArray())
            zip.closeEntry()
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun assertArrayEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.toList(), actual.toList())
    }
}
