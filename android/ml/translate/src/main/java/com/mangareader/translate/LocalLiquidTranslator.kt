package com.mangareader.translate

import android.util.Log
import com.mangareader.model.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Tier 1 for `ModelFamily.LFM2_MT`: LiquidAI/LFM2-350M-ENJP-MT, a dedicated EN<->JA
 * translation model, not a general instruction-follower.
 *
 * [LocalLlmTranslator] is Qwen3's ChatML wrapped around section 7's structured per-page
 * JSON request. This model has no notion of either: its own card gives exactly two
 * system prompts -- "Translate to English." / "Translate to Japanese." -- and expects
 * the text to translate as the user turn, full stop. Sending it the JSON request would
 * test whether a 350M translation-only model can also do arbitrary JSON formatting,
 * which is not what tier 1 is for, so this generates once per bubble in the model's own
 * protocol instead of once per page.
 *
 * The cost is exactly what [LocalLlmTranslator]'s per-page call buys the other family:
 * no cross-bubble context, so pronouns, register and the scene a bubble sits inside are
 * invisible to it. That is a structural property of this model, not a bug here -- see
 * `docs/on-device-llm.md` section 2's bake-off writeup.
 *
 * [MAX_NEW_TOKENS] is not a budget for a real answer -- a translated line needs a
 * fraction of it. `tools/bench_translate.py`'s desktop bake-off found the reason it
 * matters anyway: a cover-page region that OCR'd to bare punctuation (`．．．`) sent this
 * model into a repetition loop with no cap on it, burning 95 seconds on one bubble. The
 * cap exists to bound how long one bad OCR read can run, not to budget the answer.
 */
class LocalLiquidTranslator(
    private val modelFile: File,
    override val id: String,
    private val contextSize: Int = LlamaBridge.CONTEXT,
) : Translator {

    override val label = "Local LLM"
    override val supportsContext = false

    override val configured: Boolean
        get() = LlamaBridge.available && modelFile.isFile && modelFile.length() > 0

    private var bridge: LlamaBridge? = null

    override suspend fun translatePage(
        regions: List<Region>,
        pageContext: String,
        cover: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): Map<Long, String?> = withContext(Dispatchers.Default) {
        // pageContext and cover are Qwen3-family concepts -- a page-level note and a
        // different system prompt for cover lettering -- and this model has room for
        // neither: its system prompt is one of two fixed sentences.
        val items = translatable(regions)
        if (items.isEmpty()) return@withContext emptyMap()

        val results = LinkedHashMap<Long, String?>()
        val llama = LlamaBridge().also { bridge = it }
        try {
            val started = System.nanoTime()
            llama.load(modelFile, contextSize)
            Log.i(TAG, "loaded in " + ((System.nanoTime() - started) / 1_000_000) + " ms")

            items.forEachIndexed { index, region ->
                coroutineContext.ensureActive()
                val raw = llama.generate(prompt(region.text), MAX_NEW_TOKENS)
                // Observed habit: the model sometimes prefixes its answer with "- ",
                // which reads as a stray bullet rather than part of the translation.
                results[region.id] = raw.trim().removePrefix("- ").trim().ifEmpty { null }
                onProgress(index + 1, items.size)
            }
        } finally {
            // Whatever happened, the gigabyte goes back.
            llama.close()
            bridge = null
        }
        results
    }

    /**
     * The model's own `chat_template.jinja`: plain ChatML, a fixed system sentence, one
     * user turn. No cover variant, no page context, no `<think>` prefill -- this family
     * is not hybrid.
     */
    private fun prompt(text: String): String = buildString {
        append("<|im_start|>system\n")
        append("Translate to English.")
        append("<|im_end|>\n")
        append("<|im_start|>user\n")
        append(text)
        append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    override fun close() {
        bridge?.close()
        bridge = null
    }

    companion object {
        const val MAX_NEW_TOKENS = 128

        private const val TAG = "LocalLiquidTranslator"
    }
}
