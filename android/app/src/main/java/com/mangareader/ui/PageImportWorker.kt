package com.mangareader.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mangareader.R
import com.mangareader.database.MangaDatabase
import com.mangareader.model.PipelineStage
import com.mangareader.pipeline.ModelStore
import com.mangareader.pipeline.PageAnalyzer
import com.mangareader.pipeline.PageStore
import com.mangareader.pipeline.Vault
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A foreground, resumable import. Re-running after process death skips committed pages. */
class PageImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val volumeId = inputData.getString(KEY_VOLUME) ?: return Result.failure()
        val manifestPath = inputData.getString(KEY_MANIFEST)
        val retryOnly = inputData.getBoolean(KEY_RETRY, false)
        val pdf = inputData.getBoolean(KEY_PDF, false)
        val manifest = manifestPath?.let(::File)
        val sources = if (retryOnly) emptyList() else try {
            val json = JSONObject(manifest?.readText() ?: error("Import list is missing"))
            val uris = json.getJSONArray("uris")
            (0 until uris.length()).map { Uri.parse(uris.getString(it)) }
        } catch (failure: Exception) {
            return Result.failure(workDataOf(KEY_ERROR to (failure.message ?: "Import list is invalid")))
        }

        setForeground(foreground(0, 0, "Preparing import"))
        var imported = 0
        var duplicates = 0
        var failed = 0
        var analysed = 0
        val errors = mutableListOf<String>()
        val database = MangaDatabase.get(applicationContext)
        val store = PageStore(applicationContext)
        val analyzer = PageAnalyzer(applicationContext)
        val ready = ModelStore(applicationContext).status() is ModelStore.Status.Ready
        if (!ready) {
            sources.forEach { uri ->
                runCatching { applicationContext.contentResolver.releasePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            manifest?.delete()
            return Result.failure(workDataOf(KEY_ERROR to "Install OCR models, then retry."))
        }

        suspend fun analyse(pageId: String) {
            try {
                if (analyzer.analysePending(pageId) != null) analysed++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                failed++
                errors += "OCR: ${failure.message ?: pageId}"
            }
        }

        try {
            if (retryOnly) {
                val pending = database.pages().byVolume(volumeId)
                    .filter { it.stage.index < PipelineStage.ANALYSED.index }
                pending.forEachIndexed { index, page ->
                    currentCoroutineContext().ensureActive()
                    report(index + 1, pending.size, "Reading page ${index + 1} of ${pending.size}")
                    analyse(page.id)
                }
            } else {
                sources.forEachIndexed { sourceIndex, uri ->
                    currentCoroutineContext().ensureActive()
                    val label = displayName(applicationContext, uri)
                    try {
                        if (pdf) {
                            val scratch = File(applicationContext.cacheDir, "import-${id}.pdf")
                            try {
                                val hash = copyAndHash(uri, scratch)
                                ParcelFileDescriptor.open(scratch, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                                    PdfRenderer(fd).use { renderer ->
                                        require(renderer.pageCount > 0) { "PDF has no pages" }
                                        repeat(renderer.pageCount) { pageIndex ->
                                            currentCoroutineContext().ensureActive()
                                            report(pageIndex + 1, renderer.pageCount,
                                                "$label: page ${pageIndex + 1} of ${renderer.pageCount}")
                                            try {
                                                renderer.openPage(pageIndex).use { page ->
                                                    val bitmap = render(page)
                                                    try {
                                                        val saved = store.storeImported(bitmap,
                                                            "pdf:$hash:$pageIndex", volumeId)
                                                        if (saved.inserted) imported++ else duplicates++
                                                        analyse(saved.id)
                                                    } finally { bitmap.recycle() }
                                                }
                                            } catch (cancelled: CancellationException) {
                                                throw cancelled
                                            } catch (failure: Exception) {
                                                failed++
                                                errors += "$label page ${pageIndex + 1}: ${failure.message}"
                                            }
                                        }
                                    }
                                }
                            } finally { scratch.delete() }
                        } else {
                            report(sourceIndex + 1, sources.size,
                                "Importing $label (${sourceIndex + 1} of ${sources.size})")
                            val hash = hash(uri)
                            val bitmap = decodePng(uri)
                            try {
                                val saved = store.storeImported(bitmap, "png:$hash", volumeId)
                                if (saved.inserted) imported++ else duplicates++
                                analyse(saved.id)
                            } finally { bitmap.recycle() }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failed++
                        errors += "$label: ${failure.message}"
                    }
                }
            }
            Vault.get(applicationContext).snapshot()
            return Result.success(workDataOf(
                KEY_SUMMARY to "$imported imported, $duplicates skipped, $analysed analysed, $failed failed" +
                    errors.firstOrNull()?.let { ": $it" }.orEmpty(),
            ))
        } finally {
            Vault.get(applicationContext).snapshotSoon()
            if (!retryOnly) {
                sources.forEach { uri ->
                    runCatching { applicationContext.contentResolver.releasePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                manifest?.delete()
            }
        }
    }

    private suspend fun report(done: Int, total: Int, label: String) {
        setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_LABEL to label))
        setForeground(foreground(done, total, label))
    }

    private fun foreground(done: Int, total: Int, label: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Page import",
            NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Importing manga pages")
            .setContentText(label)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(4202, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(4202, notification)
    }

    private suspend fun hash(uri: Uri): String = MessageDigest.getInstance("SHA-256").let { digest ->
        applicationContext.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: error("Cannot open image")
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun copyAndHash(uri: Uri, target: File): String =
        MessageDigest.getInstance("SHA-256").let { digest ->
            applicationContext.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("Cannot open PDF")
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private fun decodePng(uri: Uri): Bitmap {
        val resolver = applicationContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
            Unit
        }
            ?: error("Cannot open image")
        require(bounds.outMimeType == "image/png") { "Not a PNG image" }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image has invalid dimensions" }
        val sample = generateSequence(1) { it * 2 }
            .first { bounds.outWidth / it <= MAX_SIDE && bounds.outHeight / it <= MAX_SIDE }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Cannot decode PNG")
        return opaque(decoded)
    }

    private fun render(page: PdfRenderer.Page): Bitmap {
        // PDF points are 1/72 inch; render up to ~220 dpi for a typical manga page.
        val factor = minOf(3.0, MAX_SIDE.toDouble() / maxOf(page.width, page.height))
        val width = (page.width * factor).toInt().coerceAtLeast(1)
        val height = (page.height * factor).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun opaque(source: Bitmap): Bitmap {
        if (!source.hasAlpha()) return source
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        Canvas(out).drawBitmap(source, 0f, 0f, null)
        source.recycle()
        return out
    }

    companion object {
        const val WORK_NAME = "import-pages"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_LABEL = "label"
        const val KEY_SUMMARY = "summary"
        const val KEY_ERROR = "error"
        private const val KEY_VOLUME = "volume"
        private const val KEY_MANIFEST = "manifest"
        private const val KEY_RETRY = "retry"
        private const val KEY_PDF = "pdf"
        private const val CHANNEL = "page-import"
        private const val MAX_SIDE = 3000

        fun displayName(context: Context, uri: Uri): String = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        }.getOrNull() ?: uri.lastPathSegment ?: "document"

        fun enqueue(
            context: Context, volumeId: String, uris: List<Uri>,
            retryOnly: Boolean = false, pdf: Boolean = false,
        ) {
            val manifest = if (retryOnly) null else {
                val directory = File(context.filesDir, "import-jobs").apply { mkdirs() }
                File(directory, "${UUID.randomUUID()}.json").also { file ->
                    file.writeText(JSONObject().put("uris", JSONArray(uris.map(Uri::toString))).toString())
                }
            }
            val request = OneTimeWorkRequestBuilder<PageImportWorker>()
                .setInputData(workDataOf(KEY_VOLUME to volumeId, KEY_MANIFEST to manifest?.absolutePath,
                    KEY_RETRY to retryOnly, KEY_PDF to pdf))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Also covers a queued job cancelled before doWork() could run its finally block. */
        suspend fun cleanupAbandoned(context: Context) = withContext(Dispatchers.IO) {
            val active = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME)
                .get().any { !it.state.isFinished }
            if (active) return@withContext
            File(context.filesDir, "import-jobs").listFiles()?.forEach { manifest ->
                runCatching {
                    val uris = JSONObject(manifest.readText()).getJSONArray("uris")
                    repeat(uris.length()) { index ->
                        runCatching { context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(uris.getString(index)), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    }
                }
                manifest.delete()
            }
        }
    }
}
