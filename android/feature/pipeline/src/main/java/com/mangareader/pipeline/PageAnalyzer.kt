package com.mangareader.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.room.withTransaction
import com.mangareader.capture.Rectify
import com.mangareader.analysis.Kana
import com.mangareader.database.MangaDatabase
import com.mangareader.database.RegionEntity
import com.mangareader.database.TokenEntity
import com.mangareader.database.LexicalSerial
import com.mangareader.model.ANALYSIS_VERSION
import com.mangareader.detection.Detector
import com.mangareader.model.Order
import com.mangareader.model.PageKind
import com.mangareader.model.PipelineStage
import com.mangareader.model.Region
import com.mangareader.ocr.MangaOcr
import com.mangareader.ocr.cropRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Stages 3 to 6 over a stored page: detect, order, OCR, analyse, persist.
 *
 * Stage 2 already ran in [PageStore] on the way in, so this starts from the rectified
 * image on disk. Completed pages use saved results; their OCR sources are discarded.
 *
 * This is the same chain `app/src/androidTest/.../PipelineFixtureTest.kt` runs, in the
 * same order and with the same defaults -- that test is what establishes it agrees with
 * `harness/pipeline/runner.py` (30 of 31 regions byte-identical, reading order
 * identical). Keep the two in step: a threshold changed here and not there means the
 * parity number stops describing the app.
 *
 * Progress is emitted per stage because section 3.1 asks for it by name rather than as
 * a spinner, and because OCR is 6 of the 10 seconds a page costs on the target device.
 */
class PageAnalyzer(
    private val context: Context,
    private val database: MangaDatabase = MangaDatabase.get(context),
    private val vault: Vault = Vault.get(context),
) {

    companion object {
        private val analysisMutex = Mutex()
        private const val TAG = "PageAnalyzer"
    }

    /** Section 4's stage numbers, so the UI can show "5 · Reading text". */
    enum class Stage(val number: Int, val label: String) {
        LOAD(0, "Loading models"),
        DETECT(3, "Finding bubbles"),
        ORDER(4, "Sorting reading order"),
        OCR(5, "Reading text"),
        ANALYSE(6, "Looking up words"),

        /**
         * Stage 7 does not run here -- [PageTranslator] owns it, because it is asked for
         * rather than run on open. The entry lives in this enum anyway so the reader's
         * stage panel can render translation progress with the same widget as everything
         * else, numbered the way section 4 numbers it.
         */
        TRANSLATE(7, "Translating"),
    }

    data class Progress(
        val stage: Stage,
        val detail: String = "",
        /** Within the stage, 0..1; -1 when it cannot be known (model load, detect). */
        val fraction: Float = -1f,
    )

    data class Result(
        val pageId: String,
        val kind: PageKind,
        val regions: Int,
        val dialogue: Int,
        val tokens: Int,
        /** Free-floating regions section 3.4's glossary could place. */
        val sfxMatched: Int,
        /** False when the JMdict asset was not unpacked, so tier 2 has no glosses. */
        val hadDictionary: Boolean,
        val millis: Long,
        /** Seconds per stage, the shape `Page.timings` and the debug panel expect. */
        val timings: Map<String, Double>,
    )

    private val models by lazy { ModelStore(context) }
    private val dictionaries by lazy { DictionaryStore(context) }

    /**
     * Where `Detector`/`MangaOcr` cache their optimized ONNX graphs.
     *
     * `context.cacheDir`, not next to the source models in `ModelStore.installDir`: the
     * cache is regenerable from the source at any time -- a miss just costs one page
     * the optimization pass again -- so it belongs where Android is free to reclaim it
     * under storage pressure rather than permanently doubling the ~285 MB `ModelStore`
     * already asks for. See `Detector`'s constructor doc for the full reasoning.
     */
    private val onnxCacheDir: File get() = File(context.cacheDir, "onnx_opt")

    /**
     * @throws IllegalStateException if the models are not installed -- the caller is
     *   expected to have offered [ModelStore.install] first
     */
    suspend fun analyse(
        pageId: String,
        onProgress: (Progress) -> Unit = {},
    ): Result = analysisMutex.withLock {
        val result = vault.withLibraryLock { analyseUnlocked(pageId, onProgress) }
        ImageRetention(context, database, vault).cleanPage(pageId)
        result
    }

    /** Import and reader may race to open a newly stored page. Recheck under the lock. */
    suspend fun analysePending(
        pageId: String,
        onProgress: (Progress) -> Unit = {},
    ): Result? = analysisMutex.withLock {
        val result = vault.withLibraryLock {
            val page = database.pages().byId(pageId)
            if (page == null || page.stage.index >= PipelineStage.ANALYSED.index) null
            else analyseUnlocked(pageId, onProgress)
        }
        ImageRetention(context, database, vault).cleanPage(pageId)
        result
    }

    /** Upgrade only stage 6 using stored, corrected text. Safe after source-image cleanup. */
    suspend fun refreshAnalysis(pageId: String) = analysisMutex.withLock {
        vault.withLibraryLock {
            withContext(Dispatchers.Default) {
                val page = database.pages().byId(pageId) ?: return@withContext
                if (page.stage.index < PipelineStage.ANALYSED.index) return@withContext
                val stale = database.regions().byPage(pageId).filter { it.analysisVersion != ANALYSIS_VERSION }
                if (stale.isEmpty()) return@withContext
                runCatching { dictionaries.install() }
                dictionaries.analyser().use { analyser ->
                    stale.forEach { stored ->
                        coroutineContext.ensureActive()
                        val analysed = analyser.analyse(stored.toRegion(), page.kind)
                        val corrected = ReadingCorrections.reapply(
                            database.tokens().byRegion(pageId, stored.regionId),
                            analysed.tokens.map { TokenEntity.of(pageId, stored.regionId, it) }, stored.toRegion().text)
                        val words = analyser.lexical(stored.toRegion().text, corrected.map { it.toToken() })
                        database.withTransaction {
                            database.tokens().clearRegion(pageId, stored.regionId)
                            database.tokens().insertAll(corrected)
                            database.regions().setAnalysis(pageId, stored.regionId,
                                LexicalSerial.encode(words), analysed.analysisVersion)
                            database.regions().setKind(pageId, stored.regionId, analysed.kind)
                            database.regions().setSfx(pageId, stored.regionId, RegionEntity.of(pageId, analysed).sfx)
                        }
                    }
                }
            }
        }
    }

    private suspend fun analyseUnlocked(
        pageId: String,
        onProgress: (Progress) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        val page = database.pages().byId(pageId) ?: error("no such page: " + pageId)
        if (page.stage.index >= PipelineStage.ANALYSED.index) {
            val regions = database.regions().byPage(pageId)
            return@withContext Result(
                pageId, page.kind, regions.size,
                regions.count { it.kind != com.mangareader.model.RegionKind.TEXT_SFX },
                database.tokens().byPage(pageId).size, regions.count { it.sfx != null },
                dictionaries.isInstalled, 0L, emptyMap(),
            )
        }
        val ready = models.status() as? ModelStore.Status.Ready
            ?: error("models are not installed")
        check(Rectify.ensureLoaded()) { "OpenCV failed to load" }

        val started = System.nanoTime()
        val timings = linkedMapOf<String, Double>()
        onProgress(Progress(Stage.LOAD))

        var mark = System.nanoTime()
        // Full resolution, not the display copy: OCR reads 224x224 crops of individual
        // bubbles, and downscaling the page first throws away exactly the strokes it
        // needs. The display JPEG exists for the reader, not for this.
        val image = decode(vault.file(page.imagePath))
        timings["decode"] = seconds(mark)
        try {

        // Split rather than one "load" bucket: the two sessions are built from
        // different files and reuse the cached optimized graph independently (see
        // `Detector.optimizedFile`), so a slow one here points at a specific model
        // rather than at "loading" in general.
        mark = System.nanoTime()
        val read = Detector(ready.detector, cacheDir = onnxCacheDir).use { detector ->
            timings["load_detector"] = seconds(mark)
            mark = System.nanoTime()
            MangaOcr(ready.ocrDir, cacheDir = onnxCacheDir).use { ocr ->
                timings["load_ocr"] = seconds(mark)

                mark = System.nanoTime()
                onProgress(Progress(Stage.DETECT))
                val detections = detector.detect(image)
                timings["detect"] = seconds(mark)

                mark = System.nanoTime()
                onProgress(Progress(Stage.ORDER))
                // A cover has no panels and no reading path; section 4 orders it by
                // prominence instead.
                val ordered = if (detections.kind == PageKind.COVER) {
                    Order.assignCoverOrder(detections.regions)
                } else {
                    Order.assignReadingOrder(detections.regions)
                }
                timings["order"] = seconds(mark)

                mark = System.nanoTime()
                val out = mutableListOf<Region>()
                ordered.forEachIndexed { index, region ->
                    coroutineContext.ensureActive()
                    onProgress(
                        Progress(
                            Stage.OCR,
                            detail = "" + (index + 1) + " of " + ordered.size,
                            fraction = index.toFloat() / ordered.size,
                        ),
                    )
                    val reading = ocr.read(cropRegion(image, region.box))
                    out += region.copy(
                        ocrText = reading.text,
                        ocrConfidence = reading.confidence,
                    )
                }
                timings["ocr"] = seconds(mark)
                Triple(out.toList(), detections.kind, detections.colourFraction)
            }
        }
        val (read5, kind, colourFraction) = read

        // [6] analyse -- no network, no model, so it runs unconditionally. The ONNX
        // sessions are closed by this point on purpose: Kuromoji's IPADIC costs ~60 MB
        // of heap to build and holding it alongside 290 MB of resident model is how a
        // mid-range phone gets its analysis killed rather than merely slowed.
        mark = System.nanoTime()
        onProgress(Progress(Stage.ANALYSE))
        // Idempotent, and a no-op after the first page. It is here as well as at
        // startup so that a page captured in the first few seconds of a fresh install
        // still gets its glosses rather than silently landing without them.
        runCatching {
            dictionaries.install { progress ->
                onProgress(
                    Progress(Stage.ANALYSE, "Unpacking dictionary", progress.fraction),
                )
            }
        }.onFailure { Log.w(TAG, "dictionary unpack failed", it) }

        val analysed = dictionaries.analyser().use { analyser ->
            analyser.hasDictionary to read5.mapIndexed { index, region ->
                coroutineContext.ensureActive()
                onProgress(
                    Progress(
                        Stage.ANALYSE,
                        detail = "" + (index + 1) + " of " + read5.size,
                        fraction = index.toFloat() / read5.size,
                    ),
                )
                analyser.analyse(region, kind)
            }
        }
        val hadDictionary = analysed.first
        val regions = analysed.second
        timings["analyse"] = seconds(mark)

        persistAnalysis(pageId, regions, kind, colourFraction)

        val millis = ((System.nanoTime() - started) / 1_000_000.0).toLong()
        Log.i(
            TAG,
            pageId + "  " + kind + "  " + regions.size + " regions  " +
                regions.sumOf { it.tokens.size } + " tokens  " + millis + " ms  " +
                timings.entries.joinToString(" ") { it.key + " " + format(it.value) + "s" },
        )
        Result(
            pageId = pageId,
            kind = kind,
            regions = regions.size,
            dialogue = regions.count { it.kind != com.mangareader.model.RegionKind.TEXT_SFX },
            tokens = regions.sumOf { it.tokens.size },
            sfxMatched = regions.count { it.sfx != null },
            hadDictionary = hadDictionary,
            millis = millis,
            timings = timings,
        )
        } finally { image.release() }
    }

    /**
     * Section 3.3's Tier 1 edit affordance: "OCR will be wrong sometimes and the user
     * must be able to correct it, which re-runs tiers 2 and 3".
     *
     * Tier 2 is re-derived here. Tier 3 is not re-run automatically even though it is now
     * possible: a correction is one keystroke and stage 7 is a paid call or a minute of
     * CPU, so the correction invalidates the page's cache key -- which it does for free,
     * the key being a hash of the bubble texts -- and the user asks for the retranslation
     * when they are done editing. This is the `reanalyse_region` seam
     * `harness/pipeline/runner.py` names -- one region, no models loaded, no page
     * re-read -- so a correction lands in well under a second where re-running
     * [analyse] would cost the same ten seconds the whole page did.
     *
     * @param text the correction, or null to fall back to what OCR read
     */
    suspend fun editRegionText(
        pageId: String,
        regionId: Long,
        text: String?,
    ): Region = analysisMutex.withLock { withContext(Dispatchers.Default) {
        val page = database.pages().byId(pageId) ?: error("no such page: " + pageId)
        val previous = database.tokens().byRegion(pageId, regionId)
        val stored = database.regions().byId(pageId, regionId)
            ?: error("no such region: " + pageId + "/" + regionId)
        val edited = stored.toRegion().copy(ocrTextEdited = text?.takeIf { it.isNotBlank() })

        val analysed = dictionaries.analyser().use { it.analyse(edited, page.kind) }

        val corrected = ReadingCorrections.reapply(
            previous,
            analysed.tokens.map { TokenEntity.of(pageId, regionId, it) },
            edited.text,
        )
        val words = dictionaries.analyser().use { it.lexical(edited.text, corrected.map { row -> row.toToken() }) }
        database.withTransaction {
            database.regions().editText(pageId, regionId, edited.ocrTextEdited)
            database.tokens().clearRegion(pageId, regionId)
            database.tokens().insertAll(corrected)
            database.regions().setAnalysis(pageId, regionId, LexicalSerial.encode(words), analysed.analysisVersion)
            database.regions().clearTranslations(pageId)
            database.regions().setSfx(pageId, regionId, RegionEntity.of(pageId, analysed).sfx)
            database.regions().setKind(pageId, regionId, analysed.kind)
        }
        // The correction can move the region across the dialogue/SFX line in either
        // direction, so the verdict has to be persisted alongside the gloss.
        analysed.copy(tokens = corrected.map { it.toToken() }, lexicalUnits = words)
    } }

    /** Save or reset one reading without changing OCR text or another bubble's tokens. */
    suspend fun editTokenReading(
        pageId: String,
        regionId: Long,
        position: Int,
        surface: String,
        reading: String?,
    ) = analysisMutex.withLock { withContext(Dispatchers.Default) {
        val current = database.tokens().byRegion(pageId, regionId)
            .firstOrNull { it.position == position && it.surface == surface }
            ?: error("This word changed. Reopen the bubble and try again.")
        val updated = if (reading == null) {
            val page = database.pages().byId(pageId) ?: error("no such page: $pageId")
            val region = database.regions().byId(pageId, regionId)
                ?: error("no such region: $pageId/$regionId")
            val generated = dictionaries.analyser().use {
                it.analyse(region.toRegion(), page.kind).tokens
            }.firstOrNull { it.position == position && it.surface == surface }
                ?: error("This word changed. Reopen the bubble and try again.")
            TokenEntity.of(pageId, regionId, generated)
        } else {
            val normalized = Kana.kanaReading(reading.trim())
            require(normalized.isNotEmpty()) { "Enter a reading in kana." }
            ReadingCorrections.corrected(current, normalized)
        }
        val region = database.regions().byId(pageId, regionId) ?: return@withContext
        val rows = database.tokens().byRegion(pageId, regionId).map {
            if (it.position == updated.position) updated else it
        }
        val words = dictionaries.analyser().use { it.lexical(region.toRegion().text, rows.map { row -> row.toToken() }) }
        database.withTransaction {
            database.tokens().insertAll(listOf(updated))
            database.regions().setAnalysis(pageId, regionId, LexicalSerial.encode(words), region.analysisVersion)
        }
    } }

    /** Keep results and their completion marker atomic, including when an insert fails. */
    internal suspend fun persistAnalysis(
        pageId: String, regions: List<Region>, kind: PageKind, colourFraction: Float,
    ) = database.withTransaction {
        database.tokens().clearPage(pageId)
        database.regions().clearPage(pageId)
        database.regions().insertAll(regions.map { RegionEntity.of(pageId, it) })
        database.tokens().insertAll(regions.flatMap { region ->
            region.tokens.map { TokenEntity.of(pageId, region.id, it) }
        })
        database.pages().markAnalysed(pageId, PipelineStage.ANALYSED, kind, colourFraction)
    }

    private fun decode(file: File): Mat {
        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        ) ?: error("could not decode " + file.name)
        val rgba = Mat()
        val rgb = Mat()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            return rgb
        } catch (failure: Throwable) {
            rgb.release()
            throw failure
        } finally {
            rgba.release()
            bitmap.recycle()
        }
    }

    private fun seconds(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000_000.0

    private fun format(value: Double) = String.format("%.2f", value)

}
