package com.mangareader.pipeline

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.mangareader.capture.Rectify
import com.mangareader.database.MangaDatabase
import com.mangareader.model.PipelineStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.coroutines.coroutineContext

/** Promote reader images before discarding OCR sources. All steps are restart-safe. */
class ImageRetention(
    private val context: Context,
    private val database: MangaDatabase = MangaDatabase.get(context),
    private val vault: Vault = Vault.get(context),
) {
    suspend fun cleanAll(): Long = withContext(Dispatchers.IO) {
        var reclaimed = 0L
        for (page in database.pages().all()) {
            coroutineContext.ensureActive()
            reclaimed += cleanPage(page.id)
        }
        if (reclaimed > 0) Log.i(TAG, "Reclaimed $reclaimed image bytes")
        reclaimed
    }

    /** Cleanup failure must never turn successful OCR into a processing failure. */
    suspend fun cleanPage(pageId: String): Long = withContext(Dispatchers.IO) {
        try {
            vault.withLibraryLock { cleanLocked(pageId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Log.w(TAG, "Image cleanup deferred for $pageId", failure)
            0L
        }
    }

    private suspend fun cleanLocked(pageId: String): Long {
        // A staged restore describes a different library and will be installed on restart.
        if (vault.restoreStaging.exists()) return 0L
        require(pageId.matches(Regex("[A-Za-z0-9_-]+")))
        val page = database.pages().byId(pageId) ?: return 0L
        val source = vault.file(page.imagePath)
        val journal = File(vault.pagesDir, "${pageId}_cleanup.txt")
        if (source.name.endsWith(Vault.DISPLAY_SUFFIX) && page.rawImagePath == null && !journal.exists()) {
            return 0L
        }
        var retained = source
        val completed = page.stage.index >= PipelineStage.ANALYSED.index
        if (completed) {
            retained = if (source.name.endsWith(Vault.DISPLAY_SUFFIX)) source
            else File(source.parentFile, source.nameWithoutExtension + Vault.DISPLAY_SUFFIX)
            requireManaged(retained)
            if (!readable(retained, page.width, page.height)) {
                // If the only copy is missing/corrupt, leave every source and reference alone.
                check(!source.name.endsWith(Vault.DISPLAY_SUFFIX)) { "Reader image is missing" }
                makeReader(source, retained)
            }
            check(readable(retained, page.width, page.height)) { "Reader image could not be verified" }
        }

        val obsolete = linkedSetOf<String>()
        if (journal.isFile) obsolete += journal.readLines().filter { it.isNotBlank() }
        if (completed && source.canonicalFile != retained.canonicalFile) obsolete += vault.relative(source)
        page.rawImagePath?.let { obsolete += it }
        if (obsolete.isEmpty()) return 0L
        obsolete.forEach { requireManaged(vault.file(it)) }

        // The journal survives a crash after the row changes, including nonstandard legacy paths.
        val part = File(journal.path + ".part")
        part.writeText(obsolete.joinToString("\n"))
        check(part.renameTo(journal)) { "Could not save image cleanup journal" }
        database.pages().retainReaderImage(pageId, vault.relative(retained))

        // An older snapshot may still name the source. Replace it before removing any files.
        if (vault.external && !vault.snapshotUnlocked(database)) return 0L
        coroutineContext.ensureActive()
        val referenced = database.pages().referencedImages()
            .map { vault.file(it).canonicalFile }.toSet()
        var reclaimed = 0L
        var finished = true
        for (path in obsolete) {
            val file = vault.file(path)
            if (file.canonicalFile in referenced) continue
            val bytes = file.length()
            if (!file.exists() || file.delete()) reclaimed += bytes
            else finished = false
        }
        if (finished) journal.delete()
        return reclaimed
    }

    private fun requireManaged(file: File) {
        val parent = file.canonicalFile.parentFile
        check(parent == vault.pagesDir.canonicalFile ||
            parent == File(context.applicationContext.filesDir, Vault.PAGES).canonicalFile) {
            "Image is outside managed page storage"
        }
    }

    private fun readable(file: File, width: Int? = null, height: Int? = null): Boolean {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return false
        try {
            if (width == null || height == null) return true
            val expectedWidth = minOf(width, PageStore.DISPLAY_WIDTH)
            val expectedHeight = height.toDouble() * expectedWidth / width.coerceAtLeast(1)
            return bitmap.width == expectedWidth && kotlin.math.abs(bitmap.height - expectedHeight) <= 1.0
        } finally { bitmap.recycle() }
    }

    private fun makeReader(source: File, target: File) {
        requireManaged(source)
        check(Rectify.ensureLoaded()) { "OpenCV failed to load" }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: error("OCR source is unreadable")
        val rgba = Mat()
        val rgb = Mat()
        val part = File(target.path + ".part")
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            PageStore(context, database, vault).writeScaledJpeg(rgb, part, PageStore.DISPLAY_WIDTH)
            check(readable(part)) { "Generated reader image is unreadable" }
            check(part.renameTo(target)) { "Could not install reader image" }
        } finally {
            bitmap.recycle()
            rgba.release()
            rgb.release()
            part.delete()
        }
    }

    private companion object { const val TAG = "ImageRetention" }
}
