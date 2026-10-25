package com.mangareader.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.mangareader.capture.Rectify
import com.mangareader.database.SeriesEntity
import com.mangareader.database.MangaDatabase
import com.mangareader.database.PageEntity
import com.mangareader.database.VolumeEntity
import com.mangareader.model.PageKind
import com.mangareader.model.PipelineStage
import com.mangareader.model.PointF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns a captured still into a stored page: stage 2, then disk, then Room.
 *
 * Orchestration, which section 7 assigns to `:feature:pipeline` -- this module, which
 * M2 created when the chain became a chain. [PageAnalyzer] picks the page up from here
 * and runs stages 3 to 5 against the image this wrote.
 */
class PageStore(
    private val context: Context,
    private val database: MangaDatabase = MangaDatabase.get(context),
    private val vault: Vault = Vault.get(context),
) {

    /** Matches `harness/pipeline/runner.py::Options`. */
    companion object {
        private val importMutex = Mutex()
        private const val JPEG_QUALITY = 92
        private const val THUMBNAIL_WIDTH = 480

        /**
         * The reader displays this rather than the full-resolution page. OCR needs the
         * full resolution; a phone screen does not, and compositing a 1920x2560 bitmap
         * under a live zoom/pan transform is visibly expensive.
         *
         * It is found again by suffix rather than by a column of its own -- see
         * [Vault.display], which is what the reader calls.
         */
        internal const val DISPLAY_WIDTH = 1400
    }

    data class Stored(
        val id: String, val imagePath: String, val rectified: Boolean,
        val inserted: Boolean = true,
    )

    /** Save a digital page without running the camera's page-quad search. */
    suspend fun storeImported(bitmap: Bitmap, sourceKey: String, volumeId: String): Stored =
        importMutex.withLock {
            vault.withLibraryLock { storeImportedUnlocked(bitmap, sourceKey, volumeId) }
        }

    private suspend fun storeImportedUnlocked(
        bitmap: Bitmap, sourceKey: String, volumeId: String,
    ): Stored = withContext(Dispatchers.Default) {
        val id = MessageDigest.getInstance("SHA-256")
            .digest(sourceKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
        database.pages().byId(id)?.let {
            return@withContext Stored(id, it.imagePath, it.rectified, inserted = false)
        }
        require(database.volumes().byId(volumeId) != null) { "Volume no longer exists" }
        check(Rectify.ensureLoaded()) { "OpenCV failed to load" }
        val rgba = Mat()
        val image = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, image, Imgproc.COLOR_RGBA2RGB)
            val directory = vault.pagesDir
            val full = File(directory, "$id.jpg")
            val thumb = File(directory, "${id}_thumb.jpg")
            val display = File(directory, id + Vault.DISPLAY_SUFFIX)
            val targets = listOf(full, thumb, display)
            try {
                writeJpeg(image, full)
                writeScaledJpeg(image, thumb, THUMBNAIL_WIDTH)
                writeScaledJpeg(image, display, DISPLAY_WIDTH)
                database.pages().insert(
                    PageEntity(
                        id = id, volumeId = volumeId,
                        readingIndex = database.pages().nextReadingIndex(volumeId),
                        imagePath = vault.relative(full), rawImagePath = null,
                        thumbnailPath = vault.relative(thumb),
                        width = image.width(), height = image.height(),
                        stage = PipelineStage.RECTIFIED, kind = PageKind.INTERIOR,
                        kindAuto = true, colourFraction = 0f, rectified = false,
                        quad = null, title = "Page", capturedAt = System.currentTimeMillis(),
                        notes = "",
                    ),
                )
            } catch (failure: Throwable) {
                targets.forEach { it.delete() }
                throw failure
            }
            Stored(id, vault.relative(full), false)
        } finally {
            rgba.release()
            image.release()
        }
    }

    /** The user's choice on the confirmation screen, kept distinct from a missing quad. */
    sealed interface Framing {
        data object Automatic : Framing
        data class Manual(val quad: List<PointF>) : Framing
        data object WholeFrame : Framing
    }

    /**
     * @param jpeg the bytes straight off CameraX's ImageCapture
     * @param framing automatic detection, manually placed corners, or the full frame
     * @param volumeId which folder the page lands in. Defaults to the unsorted bucket,
     *   which is where a page with nowhere else to go belongs -- the capture flow always
     *   passes the volume the user opened.
     */
    suspend fun store(
        jpeg: ByteArray,
        rotationDegrees: Int = 0,
        framing: Framing = Framing.Automatic,
        volumeId: String = MangaDatabase.UNSORTED_ID,
        title: String = "",
    ): Stored = vault.withLibraryLock { storeUnlocked(jpeg, rotationDegrees, framing, volumeId, title) }

    private suspend fun storeUnlocked(
        jpeg: ByteArray, rotationDegrees: Int, framing: Framing, volumeId: String, title: String,
    ): Stored = withContext(Dispatchers.Default) {
        check(Rectify.ensureLoaded()) { "OpenCV failed to load" }

        // Page identity is the content hash, so re-importing the same photo reuses it
        // -- the rule harness/pipeline/runner.py::_content_id follows.
        val id = MessageDigest.getInstance("SHA-256")
            .digest(jpeg)
            .joinToString("") { "%02x".format(it) }
            .take(16)

        database.pages().byId(id)?.let {
            return@withContext Stored(id, it.imagePath, it.rectified, inserted = false)
        }

        val raw = decode(jpeg, rotationDegrees)
        val result = when (framing) {
            Framing.Automatic -> Rectify.rectify(raw)
            is Framing.Manual -> Rectify.rectify(raw, framing.quad)
            Framing.WholeFrame -> Rectify.rectify(raw, detectQuad = false)
        }
        val image = result.image

        val directory = vault.pagesDir
        val imagePath = File(directory, "$id.jpg")
        val thumbPath = File(directory, "${id}_thumb.jpg")
        val displayPath = File(directory, id + Vault.DISPLAY_SUFFIX)

        writeJpeg(image, imagePath)
        writeScaledJpeg(image, thumbPath, THUMBNAIL_WIDTH)
        writeScaledJpeg(image, displayPath, DISPLAY_WIDTH)

        // The page's foreign key needs a volume. Normally the library screen made one and
        // this does nothing; when nothing did -- the unsorted default, the fixture tests --
        // the folder chain is created here so a capture can never fail for want of a
        // folder. Conditional rather than insert-or-ignore, so an app whose pages all live
        // in real series never grows an empty "Unsorted" card.
        if (database.volumes().byId(volumeId) == null) ensureUnsorted(volumeId)
        database.pages().insert(
            PageEntity(
                id = id,
                volumeId = volumeId,
                readingIndex = database.pages().nextReadingIndex(volumeId),
                // Relative to the vault, never absolute: the library outlives the
                // install, and `/data/user/0/...` does not. `Vault.file` resolves them.
                imagePath = vault.relative(imagePath),
                rawImagePath = null,
                thumbnailPath = vault.relative(thumbPath),
                width = image.width(),
                height = image.height(),
                // M1 stops here. Detection, OCR and analysis move this forward.
                stage = PipelineStage.RECTIFIED,
                kind = PageKind.INTERIOR,
                kindAuto = true,
                colourFraction = 0f,
                rectified = result.rectified,
                quad = result.quad,
                title = title.ifBlank { "Page" },
                capturedAt = System.currentTimeMillis(),
                notes = "",
            ),
        )
        Stored(id, vault.relative(imagePath), result.rectified)
    }

    /**
     * The quad [Rectify] would pick for this still, for seeding the confirm screen's
     * handles. Falls back to section 4's inset default when auto-detect declines --
     * the screen still needs four handles to grab.
     */
    suspend fun proposeQuad(
        jpeg: ByteArray,
        rotationDegrees: Int = 0,
    ): Pair<List<PointF>, Boolean> =
        withContext(Dispatchers.Default) {
            check(Rectify.ensureLoaded()) { "OpenCV failed to load" }
            val raw = decode(jpeg, rotationDegrees)
            val found = Rectify.findPageQuad(raw)
            if (found == null) {
                Rectify.defaultQuad() to false
            } else {
                found.map {
                    PointF(
                        (it.x / raw.width()).toFloat(),
                        (it.y / raw.height()).toFloat(),
                    )
                } to true
            }
        }

    /**
     * Deletes every page in a volume, images and all.
     *
     * The row side would cascade on its own, but the JPEGs would not: four files per page
     * sitting in `files/pages` with nothing left pointing at them. Call this before
     * dropping the volume row, while the paths are still readable.
     */
    suspend fun deletePages(volumeId: String) = vault.withLibraryLock { deletePagesUnlocked(volumeId) }

    private suspend fun deletePagesUnlocked(volumeId: String) = withContext(Dispatchers.IO) {
        database.pages().byVolume(volumeId).forEach { page ->
            deleteFiles(page)
            database.savedWords().forgetPage(page.id)
            database.pages().delete(page.id)
        }
    }

    /**
     * Deletes one page, images and all, and closes the hole it leaves.
     *
     * The renumbering is the part that is not obvious. A page's reading index is its
     * position in the volume and the grid's caption is that index plus one, so removing
     * the middle page of five and stopping there leaves a shelf reading 1, 2, 4, 5 --
     * a page number that refers to nothing. [PageDao.closeGap] pulls the rest down.
     */
    suspend fun deletePage(pageId: String) = vault.withLibraryLock { deletePageUnlocked(pageId) }

    private suspend fun deletePageUnlocked(pageId: String) = withContext(Dispatchers.IO) {
        val page = database.pages().byId(pageId) ?: return@withContext
        deleteFiles(page)
        database.savedWords().forgetPage(page.id)
        database.pages().delete(page.id)
        database.pages().closeGap(page.volumeId, page.readingIndex)
    }

    /** Keep pages linked from pinned words and discard the rest, leaving the volume active. */
    suspend fun archiveVolume(volumeId: String): Int = withContext(Dispatchers.IO) {
        val protected = database.savedWords().sourcePageIds(volumeId).toSet()
        val discarded = database.pages().byVolume(volumeId)
            .filterNot { it.id in protected }
            .sortedByDescending { it.readingIndex }
        // Descending order avoids renumbering pages that are about to be deleted.
        discarded.forEach { deletePage(it.id) }
        discarded.size
    }

    /**
     * Moves a page to the end of another volume, and closes the hole it leaves behind.
     *
     * Nothing on disk moves: a page is stored under its content hash in one flat
     * `pages/` directory, and which volume it belongs to is a column, not a path.
     */
    suspend fun movePage(pageId: String, volumeId: String) = withContext(Dispatchers.IO) {
        val page = database.pages().byId(pageId) ?: return@withContext
        if (page.volumeId == volumeId) return@withContext
        database.pages().setVolume(
            id = page.id,
            volumeId = volumeId,
            readingIndex = database.pages().nextReadingIndex(volumeId),
        )
        database.pages().closeGap(page.volumeId, page.readingIndex)
    }

    /** All retained images and any files left by interrupted cleanup. */
    private fun deleteFiles(page: PageEntity) {
        // Resolved first: the reader's copy is found by walking out from the full
        // page, so asking for it after that file is gone would look in the wrong
        // place for a library still half-adopted out of `filesDir`.
        val display = vault.display(page.imagePath)
        val journal = File(vault.pagesDir, "${page.id}_cleanup.txt")
        if (journal.isFile) {
            journal.readLines().forEach { path ->
                val file = vault.file(path)
                val parent = file.canonicalFile.parentFile
                if (parent == vault.pagesDir.canonicalFile ||
                    parent == File(context.filesDir, Vault.PAGES).canonicalFile) file.delete()
            }
            journal.delete()
        }
        listOfNotNull(
            page.imagePath,
            page.rawImagePath,
            page.thumbnailPath,
        ).forEach { vault.file(it).delete() }
        display.delete()
    }

    /**
     * The bucket a page falls back to. Written as a series holding one volume under the
     * same well-known id, which is what migration 4->5 left behind for the pre-M5 flat
     * library and what `SeriesScreen` renders as "Unsorted".
     */
    private suspend fun ensureUnsorted(volumeId: String) {
        val now = System.currentTimeMillis()
        database.series().insert(
            SeriesEntity(
                id = MangaDatabase.UNSORTED_ID,
                title = MangaDatabase.UNSORTED_TITLE,
                japaneseTitle = "",
                wikiUrl = "",
                coverPath = null,
                createdAt = now,
            ),
        )
        database.volumes().insert(
            VolumeEntity(
                id = volumeId,
                seriesId = MangaDatabase.UNSORTED_ID,
                number = 1,
                coverPath = null,
                createdAt = now,
            ),
        )
    }

    /**
     * [rotationDegrees] comes from `ImageProxy.imageInfo.rotationDegrees`. CameraX
     * records orientation in the JPEG's EXIF rather than rotating the pixels, and
     * BitmapFactory ignores EXIF, so without this every handheld capture arrives
     * sideways and the page quad search looks for a landscape page in a portrait frame.
     */
    private fun decode(jpeg: ByteArray, rotationDegrees: Int): Mat {
        val bitmap = BitmapFactory.decodeByteArray(
            jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: error("could not decode captured image")
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        bitmap.recycle()

        val rotation = when ((rotationDegrees % 360 + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return rgb
        }
        val rotated = Mat()
        Core.rotate(rgb, rotated, rotation)
        return rotated
    }

    private fun writeJpeg(image: Mat, file: File) {
        val bitmap = Bitmap.createBitmap(image.width(), image.height(), Bitmap.Config.ARGB_8888)
        try {
            Utils.matToBitmap(image, bitmap)
            file.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) {
                    "Could not encode page image"
                }
            }
        } finally { bitmap.recycle() }
    }

    internal fun writeScaledJpeg(image: Mat, file: File, targetWidth: Int) {
        if (image.width() <= targetWidth) {
            writeJpeg(image, file)
            return
        }
        val scale = targetWidth.toDouble() / image.width()
        val scaled = Mat()
        try {
            Imgproc.resize(
                image, scaled,
                Size(targetWidth.toDouble(), (image.height() * scale)),
                0.0, 0.0, Imgproc.INTER_AREA,
            )
            writeJpeg(scaled, file)
        } finally { scaled.release() }
    }
}
