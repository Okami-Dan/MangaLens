package com.mangareader.pipeline

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.mangareader.database.MangaDatabase
import com.mangareader.database.TranslationCacheEntity
import com.mangareader.model.PageKind
import com.mangareader.model.PipelineStage
import com.mangareader.translate.Translator
import com.mangareader.translate.cacheKey
import com.mangareader.translate.translatable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 7 over a stored page.
 *
 * Deliberately *not* a stage inside [PageAnalyzer]. Stages 3 to 6 run automatically when
 * a page is opened, because they are free -- no network, no account, no gigabyte. Stage 7
 * is none of those things: on tier 2 it costs money per page and on tier 1 it costs about
 * a minute of the phone's CPU (`docs/on-device-llm.md` section 4, which asks for
 * translate-on-demand rather than automatic translation of every capture). So it is its
 * own object with its own entry point, and the user asks for it.
 *
 * The cache is section 7's requirement -- "re-opening a page must never re-bill" -- and it
 * is checked before the translator is so much as consulted.
 */
class PageTranslator(private val context: Context) {

    data class Progress(
        val done: Int,
        val total: Int,
        val detail: String = "",
    ) {
        val fraction: Float get() = if (total <= 0) -1f else done.toFloat() / total
    }

    data class Result(
        val pageId: String,
        val translatorId: String,
        /** Bubbles sent, or that would have been sent had the cache not answered. */
        val bubbles: Int,
        val translated: Int,
        /** Bubbles the model explicitly declined as unreadable -- section 7. */
        val declined: Int,
        val fromCache: Boolean,
        val millis: Long,
    ) {
        /** Bubbles the model simply never mentioned. The tier-1 early-stop failure mode. */
        val missing: Int get() = bubbles - translated - declined
    }

    private val database by lazy { MangaDatabase.get(context) }

    /**
     * @param translator built by the caller from settings; this module knows nothing
     *   about DataStore or about where an API key is kept
     * @param force re-run even on a cache hit, for the sheet's "translate again"
     * @throws IllegalStateException if the tier is not configured -- the caller is
     *   expected to have offered the settings screen first
     */
    suspend fun translate(
        pageId: String,
        translator: Translator,
        pageContext: String = "",
        force: Boolean = false,
        currentPageContext: (suspend () -> String)? = null,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.Default) {
        check(translator.configured) { "no translator configured" }
        val page = database.pages().byId(pageId) ?: error("no such page: " + pageId)
        val started = System.nanoTime()

        val stored = database.regions().byPage(pageId)
        val regions = stored.map { it.toRegion() }
        val cover = page.kind == PageKind.COVER
        val items = translatable(regions)

        if (items.isEmpty()) {
            // An interior page of pure SFX, or one that OCR read nothing from. Section
            // OCR read nothing on it. A legitimate outcome, not a failure.
            return@withContext Result(
                pageId = pageId,
                translatorId = translator.id,
                bubbles = 0,
                translated = 0,
                declined = 0,
                fromCache = false,
                millis = elapsed(started),
            )
        }

        // Switching translator must not leave one model's English on the page next to
        // another's -- the sheet names the model that produced each line.
        val previous = stored.mapNotNull { it.translatorId }.distinct()
        if (previous.any { it != translator.id }) {
            database.regions().clearTranslations(pageId)
        }

        val effectiveContext = if (translator.supportsContext)
            database.translationContext(pageId, pageContext).promptNote() else ""
        val key = cacheKey(regions, translator.id, cover, effectiveContext)
        val cached = if (force) null else database.translations().byKey(key)?.results()
        val results = cached ?: run {
            onProgress(Progress(0, items.size, "Translating"))
            translator.translatePage(regions, effectiveContext, cover) { done, total ->
                onProgress(Progress(done, total, "Translating"))
            }.also { fresh ->
                database.translations().put(
                    TranslationCacheEntity.of(key, pageId, translator.id, fresh),
                )
            }
        }

        // Only the regions stage 7 was allowed to see are written. A region the model
        // never mentioned still gets the translator id, so the sheet can tell "this page
        // has been translated and this bubble was skipped" from "not translated yet".
        val allowed = items.map { it.id }.toSet()
        database.withTransaction {
            // Inference can outlive an OCR edit, a page move or a series rename. Keep
            // its paid cache entry, but display it only for the request it answered.
            val currentPage = database.pages().byId(pageId)
            val currentRegions = database.regions().byPage(pageId).map { it.toRegion() }
            val currentContext = if (translator.supportsContext)
                database.translationContext(pageId, currentPageContext?.invoke() ?: pageContext).promptNote() else ""
            if (currentPage != null && key == cacheKey(currentRegions, translator.id,
                    currentPage.kind == PageKind.COVER, currentContext)) {
                allowed.forEach { regionId ->
                    database.regions().setTranslation(
                        pageId = pageId,
                        regionId = regionId,
                        text = results[regionId],
                        translatorId = translator.id,
                        requestKey = key,
                    )
                }
                database.pages().markStage(pageId, PipelineStage.TRANSLATED)
            }
        }

        val translated = allowed.count { results[it] != null }
        val declined = allowed.count { results.containsKey(it) && results[it] == null }
        Log.i(
            TAG,
            pageId + "  " + translator.id + "  " + translated + "/" + allowed.size +
                " translated  " + declined + " declined  " +
                (if (cached != null) "from cache  " else "") + elapsed(started) + " ms",
        )

        Result(
            pageId = pageId,
            translatorId = translator.id,
            bubbles = allowed.size,
            translated = translated,
            declined = declined,
            fromCache = cached != null,
            millis = elapsed(started),
        )
    }

    /**
     * Drop this page's English and its cache entry, so the next run really re-runs.
     * Behind the sheet's "translate again" when the user did not like the answer.
     */
    suspend fun clear(pageId: String) = withContext(Dispatchers.Default) {
        database.regions().clearTranslations(pageId)
        database.translations().clearPage(pageId)
        database.pages().markStage(pageId, PipelineStage.ANALYSED)
    }

    private fun elapsed(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private companion object {
        const val TAG = "PageTranslator"
    }
}
