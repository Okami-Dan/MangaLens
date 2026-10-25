package com.mangareader.translate

import android.util.Log
import com.mangareader.model.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Section 5 tier 1: the model runs on the phone, for `LlmStore.ModelFamily.QWEN3` and
 * `ModelFamily.GENERIC` alike -- the two families that speak section 7's structured
 * per-page JSON. `ModelFamily.LFM2_MT` does not, and has its own [LocalLiquidTranslator]
 * entirely.
 *
 * This is the Kotlin of `LocalLlmTranslator` in `harness/pipeline/translate.py`, and the
 * three constants below are not tuning knobs -- they are the shape of two failure modes
 * that a 1–2B model has and a hosted one does not (`docs/on-device-llm.md` section 5):
 *
 *  - **It stops early on long lists.** An 11-bubble page came back with 5 entries, and
 *    raising the token budget changed nothing: the model emits end-of-generation, it does
 *    not run out of room. So a dense page goes in chunks of [CHUNK_SIZE].
 *  - **It renumbers ids.** Handled upstream, by numbering the bubbles 1..N in
 *    [buildRequest] rather than by region id.
 *
 * Chunking does **not** abandon section 2.5's per-page call. The point of translating a
 * page at once is scene context for pronouns and implied subjects, and that is preserved:
 * each chunk carries the preceding bubbles *and their translations* as context, so every
 * chunk after the first sees what came before.
 *
 * Expect roughly 45–60 s for a page on the target device, plus a few seconds to load the
 * model. That is a background job with a notification, not something a user waits on with
 * the sheet open.
 *
 * [promptFamily] is the one thing that differs between the two families this class
 * serves. [ChatPromptFamily.QWEN3] hand-writes Qwen3's ChatML because that family's most
 * consequential detail -- the `<think></think>` direct-mode prefill -- is not something
 * llama.cpp's template applier has a channel for. [ChatPromptFamily.GENERIC] is for a
 * model whose family is *not* known in advance (a custom-pasted link): it asks llama.cpp
 * itself to detect and apply the GGUF's own embedded template. See [genericChatTemplatePrompt]
 * for what that does and does not cover.
 *
 * (An enum rather than a strategy function: [LlamaBridge] is `internal` to this module --
 * deliberately, so its native-engine types never reach `:app`'s classpath -- and a public
 * class cannot take a public parameter typed over an internal one. The enum is the public
 * seam; [qwen3Prompt] and [genericChatTemplatePrompt] stay internal.)
 */
class LocalLlmTranslator(
    private val modelFile: File,
    override val id: String,
    private val contextSize: Int = LlamaBridge.CONTEXT,
    private val promptFamily: ChatPromptFamily = ChatPromptFamily.QWEN3,
) : Translator {

    override val label = "Local LLM"

    override val configured: Boolean
        get() = LlamaBridge.available && modelFile.isFile && modelFile.length() > 0

    private var bridge: LlamaBridge? = null

    override suspend fun translatePage(
        regions: List<Region>,
        pageContext: String,
        cover: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): Map<Long, String?> = withContext(Dispatchers.Default) {
        val items = translatable(regions)
        if (items.isEmpty()) return@withContext emptyMap()

        val mapping = indexMap(regions, cover)
        val indexed = items.mapIndexed { index, region -> (index + 1) to region }
        val results = LinkedHashMap<Long, String?>()
        val history = mutableListOf<String>()

        val llama = LlamaBridge().also { bridge = it }
        try {
            val started = System.nanoTime()
            llama.load(modelFile, contextSize)
            Log.i(TAG, "loaded in " + ((System.nanoTime() - started) / 1_000_000) + " ms")

            indexed.chunked(CHUNK_SIZE).forEach { chunk ->
                coroutineContext.ensureActive()

                val context = when {
                    history.isEmpty() -> pageContext
                    else -> (if (pageContext.isEmpty()) "" else pageContext + "\n") +
                        "Earlier on this page: " +
                        history.takeLast(CHUNK_SIZE).joinToString(" / ")
                }

                val request = TranslationRequest(
                    pageContext = context,
                    bubbles = chunk.map { (index, region) -> RequestBubble(index, region.text) },
                )
                val budget = minOf(
                    MAX_NEW_TOKENS,
                    TOKENS_OVERHEAD + TOKENS_PER_BUBBLE * chunk.size,
                )

                val prompt = buildPrompt(promptFamily, llama, systemPrompt(cover), requestJson(request))
                val raw = llama.generate(prompt, budget)
                val parsed = parseResponse(raw, chunk.map { it.first })

                chunk.forEach { (index, region) ->
                    if (index in parsed) {
                        val english = parsed[index]
                        mapping[index]?.let { results[it] = english }
                        if (!english.isNullOrEmpty()) {
                            history += region.text + " = " + english
                        }
                    }
                }
                onProgress(minOf(results.size, items.size), items.size)
            }
        } finally {
            // Whatever happened, the gigabyte goes back.
            llama.close()
            bridge = null
        }
        results
    }

    override fun close() {
        bridge?.close()
        bridge = null
    }

    companion object {
        /**
         * Budget per bubble, plus JSON scaffolding.
         *
         * The Python uses 64 and 128, which is right for a desktop where an over-generous
         * budget costs nothing. On the phone it is the difference between a page and two
         * pages of waiting: greedy decoding runs to the budget whenever the model does not
         * emit end-of-generation, and every unused token is a second of four-core CPU.
         *
         * A bubble's entry is `{"id":N,"en":"..."}` -- a dozen tokens of scaffolding around
         * a line of manga dialogue, which is short by construction. 32 is roughly twice
         * what the fixture pages actually need. The log line in `nativeGenerate` reports
         * whether a chunk stopped at EOG or hit the budget, so this is checkable rather
         * than guessed at.
         */
        const val TOKENS_PER_BUBBLE = 32
        const val TOKENS_OVERHEAD = 64
        const val MAX_NEW_TOKENS = 1536

        /**
         * A tier-1 accommodation, not a general rule: a hosted model has no trouble with
         * a whole page in one call, so [RemoteTranslator] does not chunk.
         */
        const val CHUNK_SIZE = 6

        private const val TAG = "LocalLlmTranslator"
    }
}

/**
 * The public seam [LocalLlmTranslator] and [LocalGrammarExplainer] take instead of a
 * strategy function typed over the `internal` [LlamaBridge] -- see [LocalLlmTranslator]'s
 * class doc for why. [QWEN3] and [GENERIC] mean exactly what
 * `LlmStore.ModelFamily.QWEN3` / `.GENERIC` mean; this enum exists only because those
 * live in a different module and can't carry an `internal`-typed behavior across the
 * boundary themselves.
 */
enum class ChatPromptFamily { QWEN3, GENERIC }

/** Dispatches to [qwen3Prompt] or [genericChatTemplatePrompt] by [family]. */
internal fun buildPrompt(family: ChatPromptFamily, llama: LlamaBridge, system: String, user: String): String =
    when (family) {
        ChatPromptFamily.QWEN3 -> qwen3Prompt(llama, system, user)
        ChatPromptFamily.GENERIC -> genericChatTemplatePrompt(llama, system, user)
    }

/**
 * Qwen3's ChatML, assembled here rather than by llama.cpp.
 *
 * llama.cpp can apply a model's own chat template, but only through its `common` target
 * and the Jinja engine inside it -- a large dependency for one string, and one that puts
 * the most consequential detail of this file behind a template the app does not control.
 * So the format is written out.
 *
 * The `<think></think>` pair is what selects Qwen3's **direct** mode. Qwen3's dense
 * checkpoints are hybrid, and pre-filling an empty reasoning block is exactly what the
 * official template emits for `enable_thinking=False`. Without it the model spends
 * hundreds of tokens deliberating before the JSON and runs straight into the budget
 * above, which truncates the array. [parseResponse] strips a reasoning block anyway,
 * because being wrong about this should cost a worse translation rather than none.
 */
private fun qwen3Prompt(llama: LlamaBridge, system: String, user: String): String = buildString {
    append("<|im_start|>system\n")
    append(system)
    append("<|im_end|>\n")
    append("<|im_start|>user\n")
    append(user)
    append("<|im_end|>\n")
    append("<|im_start|>assistant\n")
    append("<think>\n\n</think>\n\n")
}

/**
 * For a model whose family is not known in advance (`LlmStore.ModelFamily.GENERIC`, a
 * custom-pasted link): asks llama.cpp to detect and apply the GGUF's own embedded chat
 * template rather than assuming Qwen3's, via [LlamaBridge.applyTemplate] -- see that
 * function's doc for exactly what "detect" covers (60+ named families, recognized from
 * the template string itself) and does not (no template-kwarg channel, so a
 * hybrid-reasoning custom model has no `enable_thinking`-equivalent to disable, unlike
 * [qwen3Prompt]'s hand-written prefill above).
 *
 * @throws IllegalStateException if the model's embedded template exists but matches none
 *   of llama.cpp's known families -- refused rather than silently mis-formatted
 */
private fun genericChatTemplatePrompt(llama: LlamaBridge, system: String, user: String): String =
    llama.applyTemplate(listOf("system" to system, "user" to user))
        ?: error("This model's chat template is not one this app recognizes.")
