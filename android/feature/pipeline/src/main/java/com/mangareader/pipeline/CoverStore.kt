package com.mangareader.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.mangareader.database.MangaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cover art for a series or a volume: one picked image, copied into app-private storage.
 *
 * Deliberately not part of [PageStore]. A cover needs none of what that class does -- no
 * rectify, no quad search, no `Rectify.ensureLoaded()` -- and pulling OpenCV's native
 * library in to scale a JPEG would put a second of load time behind dismissing a dialog.
 * `BitmapFactory` alone is enough for an image that is only ever shown at card size.
 *
 * The picked `content://` Uri is read once and copied, not remembered: a Uri from the
 * photo picker carries a permission grant that does not survive a reboot, so storing it
 * would give the library covers that quietly stop loading.
 *
 * The copy lands in the [Vault] alongside the pages, so a cover the user chose survives a
 * Clear data or an upgrade. An uninstall requires an explicit backup file.
 */
class CoverStore(private val context: Context) {

    enum class Owner { SERIES, VOLUME }

    private val vault by lazy { Vault.get(context) }

    companion object {
        /** Matches [PageStore]'s, so covers and page thumbnails compress alike. */
        private const val JPEG_QUALITY = 92

        /**
         * Cards are ~150 dp wide; this is generous enough for the largest phone and for
         * the grid to stay sharp, and small enough that a 12 MP pick does not sit in
         * memory as a full-size bitmap.
         */
        private const val TARGET_WIDTH = 600
    }

    /**
     * Copies [uri] into `covers/<owner>-$id-<uuid>.jpg`, downscaled, and returns the
     * path relative to [Vault.root] -- which is the form the database stores.
     *
     * **A fresh filename makes a re-picked cover appear.** This used to write
     * `covers/$id.jpg` every time, and overwriting a file in place changes nothing the UI
     * can see: the column keeps the same string, so no Room flow emits, nothing
     * recomposes, and `Folders.rememberImage` -- which decodes once per `produceState`
     * key -- goes on showing the bitmap it decoded the first time. The old cover stayed on
     * the card until the screen was left and re-entered. A new name changes the column,
     * and the change is what the grid is watching for.
     *
     * The caller commits the new path before [prune] removes unused files. The owner
     * separates series from volumes even when migrated rows share an ID; database
     * references protect shared legacy files from either folder's cleanup.
     *
     * @param id the series or volume id.
     * @throws IllegalStateException if the image cannot be read or decoded, which the
     *   caller surfaces as a snackbar rather than failing the whole creation.
     */
    suspend fun store(uri: Uri, id: String, owner: Owner): String = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth > 0) { "could not read the picked image" }

        // Decode straight to roughly the size wanted. inSampleSize only halves, so this
        // lands within 2x of the target and the scale below finishes the job.
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = open(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("could not decode the picked image")

        val scaled = if (decoded.width > TARGET_WIDTH) {
            val height = (decoded.height.toLong() * TARGET_WIDTH / decoded.width).toInt()
            Bitmap.createScaledBitmap(decoded, TARGET_WIDTH, height.coerceAtLeast(1), true)
        } else {
            decoded
        }

        val file = CoverFiles.newFile(vault.coversDir, owner, id)
        try {
            file.outputStream().use {
                check(scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) {
                    "could not save the picked image"
                }
            }
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
        vault.relative(file)
    }

    /** Call after committing the new path or deleting the row. Shared legacy files stay. */
    suspend fun prune(id: String, owner: Owner) = withContext(Dispatchers.IO) {
        val referenced = MangaDatabase.get(context).pages().referencedImages()
            .map { vault.file(it).canonicalFile }.toSet()
        CoverFiles.obsolete(vault.coversDir, owner, id, referenced).forEach {
            check(it.delete() || !it.exists()) { "could not remove the old cover" }
        }
    }

    private fun open(uri: Uri) =
        context.contentResolver.openInputStream(uri) ?: error("could not open the picked image")
}
