package com.mangareader.pipeline

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangareader.database.MangaDatabase
import com.mangareader.database.PageEntity
import com.mangareader.database.RegionEntity
import com.mangareader.database.SeriesEntity
import com.mangareader.database.VolumeEntity
import com.mangareader.model.Box
import com.mangareader.model.PageKind
import com.mangareader.model.PipelineStage
import com.mangareader.model.Region
import com.mangareader.model.RegionKind
import com.mangareader.model.Token
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** Uses its own Room database and image directories; never touches the user's library. */
@RunWith(AndroidJUnit4::class)
class ImageRetentionTest {
    private class Harness(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getExternalMediaDirs(): Array<File> = arrayOf(File(root, "external").apply { mkdirs() })
        override fun getDatabasePath(name: String): File =
            (if (File(name).isAbsolute) File(name) else File(root, "databases/$name")).also {
            it.parentFile!!.mkdirs()
        }
    }

    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var database: MangaDatabase
    private lateinit var vault: Vault
    private lateinit var retention: ImageRetention

    @Before
    fun setUp() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val external = checkNotNull(target.externalMediaDirs.firstOrNull())
        root = File(external, "retention-tests/${UUID.randomUUID()}").apply { mkdirs() }
        context = Harness(target, root)
        database = Room.databaseBuilder(context, MangaDatabase::class.java,
            context.getDatabasePath("manga-reader.db").absolutePath).build()
        vault = Vault(context)
        assertTrue(vault.external)
        retention = ImageRetention(context, database, vault)
        database.series().insert(SeriesEntity("s", "Series", "", "", null, 0))
        database.volumes().insert(VolumeEntity("v", "s", 1, null, 0))
        assertEquals(vault.databaseFile.canonicalPath, File(database.openHelper.writableDatabase.path).canonicalPath)
    }

    @After
    fun tearDown() {
        database.close()
        root.deleteRecursively()
    }

    private fun jpeg(name: String, width: Int = 800, height: Int = 1000): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return File(vault.pagesDir, name).also { file ->
            try {
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            } finally { bitmap.recycle() }
        }
    }

    private suspend fun page(
        stage: PipelineStage = PipelineStage.ANALYSED,
        display: Boolean = true,
        raw: Boolean = false,
    ): PageEntity {
        jpeg("p.jpg")
        jpeg("p_thumb.jpg", 480, 600)
        if (display) jpeg("p_display.jpg")
        if (raw) jpeg("p_raw.jpg")
        return PageEntity("p", "v", 0, "pages/p.jpg",
            if (raw) "pages/p_raw.jpg" else null, "pages/p_thumb.jpg", 800, 1000,
            stage, PageKind.INTERIOR, true, 0f, false, null, "Page", 0, "").also {
            database.pages().insert(it)
        }
    }

    private fun full() = File(vault.pagesDir, "p.jpg")
    private fun reader() = File(vault.pagesDir, "p_display.jpg")
    private fun journal() = File(vault.pagesDir, "p_cleanup.txt")

    @Test
    fun completedPageRetainsReaderAndThumbnailAndPreservesText() = runBlocking {
        val before = page(raw = true)
        val region = RegionEntity.of("p", Region(1, RegionKind.TEXT_BUBBLE,
            Box(.1f, .2f, .7f, .8f), .9f, ocrText = "猫", ocrTextEdited = "犬",
            translation = "dog", translatorId = "test"))
        database.regions().insertAll(listOf(region))
        val retainedBytes = reader().readBytes()
        val reclaimed = full().length() + File(vault.pagesDir, "p_raw.jpg").length()
        assertEquals(reclaimed, retention.cleanPage("p"))
        val after = checkNotNull(database.pages().byId("p"))
        assertEquals(before.copy(imagePath = "pages/p_display.jpg", rawImagePath = null), after)
        PageAnalyzer(context, database, vault).analyse("p") { fail("Saved OCR must be reused") }
        assertEquals(listOf(region), database.regions().byPage("p"))
        assertArrayEquals(retainedBytes, reader().readBytes())
        assertFalse(full().exists())
        assertFalse(File(vault.pagesDir, "p_raw.jpg").exists())
        assertTrue(vault.file(after.thumbnailPath!!).exists())
        assertFalse(journal().exists())
        assertEquals(reader(), vault.display(after.imagePath))
        assertEquals(0L, retention.cleanAll())
    }

    @Test
    fun pendingPageKeepsOcrSourceButDiscardsCameraOriginal() = runBlocking {
        page(stage = PipelineStage.RECTIFIED, raw = true)
        retention.cleanAll()
        assertTrue(full().exists())
        assertEquals("pages/p.jpg", database.pages().byId("p")!!.imagePath)
        assertNull(database.pages().byId("p")!!.rawImagePath)
        assertFalse(File(vault.pagesDir, "p_raw.jpg").exists())
    }

    @Test
    fun missingReaderIsGeneratedBeforeSourceIsRemoved() = runBlocking {
        page(display = false)
        assertTrue(retention.cleanAll() > 0)
        assertFalse(full().exists())
        val bitmap = checkNotNull(BitmapFactory.decodeFile(reader().path))
        try { assertEquals(800, bitmap.width); assertEquals(1000, bitmap.height) }
        finally { bitmap.recycle() }
    }

    @Test
    fun corruptReaderIsReplacedFromSource() = runBlocking {
        page()
        reader().writeText("interrupted JPEG write")
        assertTrue(retention.cleanPage("p") > 0)
        assertFalse(full().exists())
        checkNotNull(BitmapFactory.decodeFile(reader().path)).recycle()
    }

    @Test
    fun unreadableSourceAndReaderLeaveReferencesAndOriginalsIntact() = runBlocking {
        val before = page(display = false, raw = true)
        full().writeText("bad source")
        assertEquals(0L, retention.cleanAll())
        assertEquals(before, database.pages().byId("p"))
        assertTrue(full().exists())
        assertTrue(File(vault.pagesDir, "p_raw.jpg").exists())
    }

    @Test
    fun failedSnapshotKeepsSourcesAndJournalAndNextLaunchRetries() = runBlocking {
        page(raw = true)
        val blocked = File(vault.root, "library.db.gz.part").apply { mkdirs() }
        assertEquals(0L, retention.cleanPage("p"))
        assertEquals("pages/p_display.jpg", database.pages().byId("p")!!.imagePath)
        assertTrue(full().exists())
        assertTrue(journal().exists())
        assertTrue(blocked.delete())
        assertTrue(ImageRetention(context, database, vault).cleanAll() > 0)
        assertFalse(full().exists())
        assertFalse(File(vault.pagesDir, "p_raw.jpg").exists())
        assertFalse(journal().exists())
    }

    @Test
    fun deletionInterruptedAfterPromotionResumesFromJournal() = runBlocking {
        page()
        database.pages().retainReaderImage("p", "pages/p_display.jpg")
        journal().writeText("pages/p.jpg")
        assertTrue(retention.cleanAll() > 0)
        assertFalse(full().exists())
        assertFalse(journal().exists())
    }

    @Test
    fun anotherPageReferenceProtectsAFileFromDeletion() = runBlocking {
        val before = page()
        database.pages().insert(before.copy(id = "other", readingIndex = 1, stage = PipelineStage.RECTIFIED))
        retention.cleanPage("p")
        assertTrue(full().exists())
        assertEquals("pages/p.jpg", database.pages().byId("other")!!.imagePath)
    }

    @Test
    fun stagedRestoreDefersCleanup() = runBlocking {
        val before = page(raw = true)
        vault.restoreStaging.writeText("restore awaiting restart")
        assertEquals(0L, retention.cleanAll())
        assertEquals(before, database.pages().byId("p"))
        assertTrue(full().exists())
    }

    @Test
    fun journalCannotDeleteFilesOutsidePageStorage() = runBlocking {
        val before = page()
        val unrelated = File(root, "unrelated.jpg").apply { writeText("keep this") }
        journal().writeText(unrelated.path)
        assertEquals(0L, retention.cleanPage("p"))
        assertEquals(before, database.pages().byId("p"))
        assertTrue(unrelated.exists())
        assertTrue(full().exists())
    }

    @Test
    fun backupWaitsForImageChangesToFinish() = runBlocking {
        page()
        val backup = Backup(context, vault, database)
        val output = ByteArrayOutputStream()
        val export = vault.withLibraryLock {
            async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { backup.export(output) }.also {
                assertFalse("Export must wait while image changes hold the lock", it.isCompleted)
            }
        }
        assertTrue(export.await() is Backup.Result.Done)
    }

    @Test
    fun completedEmptyPageSkipsModelsAndOcr() = runBlocking {
        page()
        retention.cleanPage("p")
        val analyzer = PageAnalyzer(context, database, vault)
        val result = analyzer.analyse("p") { fail("Completed pages must not run OCR") }
        assertEquals(0, result.regions)
        assertEquals(0L, result.millis)
        assertTrue(result.timings.isEmpty())
        assertNull(analyzer.analysePending("p"))
        assertFalse(full().exists())
    }

    @Test
    fun missingModelsLeavePendingSourceRetryable() = runBlocking {
        page(stage = PipelineStage.RECTIFIED)
        try { PageAnalyzer(context, database, vault).analyse("p"); fail("Expected missing models") }
        catch (_: IllegalStateException) { }
        assertTrue(full().exists())
        assertEquals(PipelineStage.RECTIFIED, database.pages().byId("p")!!.stage)
    }

    @Test
    fun cancelledCleanupKeepsSourceForNextAttempt() = runBlocking {
        page()
        vault.withLibraryLock {
            val job = launch(Dispatchers.Default) { retention.cleanPage("p") }
            job.cancelAndJoin()
        }
        assertTrue(full().exists())
        assertTrue(retention.cleanPage("p") > 0)
    }

    @Test
    fun analysisInsertFailureRollsBackResultsAndCompletionMarker() = runBlocking {
        page(stage = PipelineStage.RECTIFIED)
        val old = RegionEntity.of("p", Region(1, RegionKind.TEXT_BUBBLE, Box(0f, 0f, 1f, 1f), 1f,
            ocrText = "old"))
        database.regions().insertAll(listOf(old))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_tokens BEFORE INSERT ON tokens BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val fresh = Region(2, RegionKind.TEXT_BUBBLE, Box(0f, 0f, 1f, 1f), 1f,
            ocrText = "猫", tokens = listOf(Token(0, "猫", "ねこ", "猫", "名詞", "noun")))
        try {
            PageAnalyzer(context, database, vault).persistAnalysis("p", listOf(fresh), PageKind.COVER, .5f)
            fail("Expected failed token insertion")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(listOf(old), database.regions().byPage("p"))
        assertEquals(PipelineStage.RECTIFIED, database.pages().byId("p")!!.stage)
        assertTrue(full().exists())
    }

    @Test
    fun captureAndImportRetainSourcesUntilSuccessfulProcessing() = runBlocking {
        val store = PageStore(context, database, vault)
        val bitmap = Bitmap.createBitmap(1600, 2000, Bitmap.Config.ARGB_8888)
        try {
            val imported = store.storeImported(bitmap, "import-source", "v")
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
            val captured = store.store(bytes, framing = PageStore.Framing.WholeFrame, volumeId = "v")
            assertFalse(vault.pagesDir.listFiles()!!.any { it.name.endsWith("_raw.jpg") })
            val analyzer = PageAnalyzer(context, database, vault)
            for (stored in listOf(imported, captured)) {
                assertTrue(vault.file(stored.imagePath).exists())
                analyzer.persistAnalysis(stored.id, emptyList(), PageKind.INTERIOR, 0f)
                analyzer.analyse(stored.id)
                val page = database.pages().byId(stored.id)!!
                assertTrue(page.imagePath.endsWith(Vault.DISPLAY_SUFFIX))
                assertFalse(vault.file(stored.imagePath).exists())
                val reader = checkNotNull(BitmapFactory.decodeFile(vault.file(page.imagePath).path))
                try { assertEquals(1400, reader.width) } finally { reader.recycle() }
                assertEquals(1600, page.width)
                assertEquals(2000, page.height)
            }
            assertFalse(store.storeImported(bitmap, "import-source", "v").inserted)
            assertFalse(store.store(bytes, framing = PageStore.Framing.WholeFrame, volumeId = "v").inserted)
        } finally { bitmap.recycle() }
    }

    @Test
    fun snapshotAndBackupBothReferenceRetainedImages() = runBlocking {
        page(raw = true)
        retention.cleanAll()
        val snapshotCopy = File(root, "snapshot.db")
        GZIPInputStream(File(vault.root, "library.db.gz").inputStream()).use { input ->
            snapshotCopy.outputStream().use { input.copyTo(it) }
        }
        val recovered = Room.databaseBuilder(context, MangaDatabase::class.java, snapshotCopy.path).build()
        try { assertEquals("pages/p_display.jpg", recovered.pages().byId("p")!!.imagePath) }
        finally { recovered.close() }

        val output = ByteArrayOutputStream()
        val backup = Backup(context, vault, database)
        assertTrue(backup.export(output) is Backup.Result.Done)
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                names += entry.name
                zip.closeEntry()
            }
        }
        assertTrue("pages/p_display.jpg" in names)
        assertTrue("pages/p_thumb.jpg" in names)
        assertFalse("pages/p.jpg" in names)
        assertFalse("pages/p_raw.jpg" in names)
        assertTrue(backup.import(ByteArrayInputStream(output.toByteArray())) is Backup.Result.Done)
        val restoredCopy = File(root, "restored.db")
        vault.restoreStaging.copyTo(restoredCopy)
        val restored = Room.databaseBuilder(context, MangaDatabase::class.java, restoredCopy.path).build()
        try {
            assertEquals("pages/p_display.jpg", restored.pages().byId("p")!!.imagePath)
            assertTrue(vault.file(restored.pages().byId("p")!!.imagePath).exists())
        } finally { restored.close() }
    }
}
