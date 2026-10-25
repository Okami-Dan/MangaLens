package com.mangareader.translate

import com.mangareader.model.Region

/**
 * Stage 7 -- translation. Section 5's single interface, three implementations.
 *
 *     tier 0  NoOpTranslator      the default; the dictionary layer needs no LLM at all
 *     tier 1  LocalLlmTranslator  a small instruct model running on the phone
 *     tier 2  RemoteTranslator    any OpenAI-compatible endpoint
 *
 * This is the Kotlin of `harness/pipeline/translate.py`, and the two are kept in step
 * deliberately: `Request.kt` produces byte-identical JSON and byte-identical cache keys
 * to the Python, which is what `TranslateParityTest` asserts and what lets a page
 * translated on the desktop harness and a page translated on the phone be compared at
 * all.
 *
 * The call is **per page, not per bubble** (section 2.5): every bubble goes in one
 * structured request in reading order, so the model can use preceding bubbles as
 * context for pronouns, implied subjects and register -- all of which Japanese omits
 * constantly and English cannot.
 */
interface Translator : AutoCloseable {

    /**
     * Identifies the model behind this translator. Goes into the cache key and into
     * `Region.translatorId`, so a page translated by one model is not silently served
     * from another's cache entry.
     */
    val id: String

    /** For the settings screen and the bubble sheet's tier-3 footnote. */
    val label: String

    /** False when the tier is selected but not usable yet -- no key, no model file. */
    val configured: Boolean
    val supportsContext: Boolean get() = true

    /**
     * @param regions every region on the page; [translatable] decides which are sent
     * @param pageContext section 7's optional user-supplied series/volume note
     * @param cover which system prompt to use; a cover is not a continuous scene
     * @param onProgress (done, total) in bubbles, for the tier-1 job that takes a minute
     * @return region id -> English, or null where the model declined to guess. A region
     *   absent from the map was not translated and shows as untranslated; this never
     *   fails a whole page over one bad bubble.
     */
    suspend fun translatePage(
        regions: List<Region>,
        pageContext: String = "",
        cover: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Map<Long, String?>

    /** Tiers that hold no resources do not need to do anything here. */
    override fun close() {}
}

/**
 * Section 5 tier 0, and the default.
 *
 * Not a null object for its own sake: [configured] being false is what the bubble
 * sheet's tier 3 reads to show the configure prompt, and what stage 7 reads to skip
 * itself entirely rather than write empty translations over a page.
 */
object NoOpTranslator : Translator {
    override val id = "none"
    override val label = "None (dictionary only)"
    override val configured = false

    override suspend fun translatePage(
        regions: List<Region>,
        pageContext: String,
        cover: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): Map<Long, String?> = emptyMap()
}
