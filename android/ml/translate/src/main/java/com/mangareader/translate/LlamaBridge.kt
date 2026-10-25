package com.mangareader.translate

import java.io.File

/**
 * The JNI seam onto llama.cpp. Three calls, and nothing that could be done in Kotlin.
 *
 * Deliberately narrow: everything above this line -- the prompt, the chunking, the
 * parsing -- is Kotlin, testable on the JVM, and shared with the tier-2 path. What is
 * left here is the part that genuinely needs native code, which is running the model.
 *
 * Not thread-safe, and not meant to be: one page is translated at a time
 * (`docs/on-device-llm.md` section 4 asks for a serialized queue, because a mid-range SoC
 * thermally throttles on a run of several pages).
 */
internal class LlamaBridge : AutoCloseable {

    private var handle: Long = 0L

    val loaded: Boolean get() = handle != 0L

    /**
     * @param contextSize section 3's context budget; a chunked page is well inside it
     * @param threads section 3: **four**, the big cluster. Using all eight cores makes
     *   throughput worse -- the A55s become stragglers on every synchronized matmul.
     * @throws IllegalStateException if the file is not a loadable GGUF
     */
    fun load(model: File, contextSize: Int = CONTEXT, threads: Int = THREADS) {
        check(!loaded) { "a model is already loaded" }
        handle = nativeLoad(model.absolutePath, contextSize, threads)
        check(handle != 0L) { "the model could not be loaded" }
    }

    /** Greedy completion of [prompt], stopping at end-of-generation or [maxTokens]. */
    fun generate(prompt: String, maxTokens: Int): String {
        check(loaded) { "no model is loaded" }
        return nativeGenerate(handle, prompt, maxTokens)
    }

    /**
     * The loaded GGUF's own embedded chat template (`tokenizer.chat_template`), or null
     * if it has none. Exposed for a future "here is the template this model uses" UI
     * touch; [applyTemplate] does not need this called first -- it looks the template up
     * itself.
     */
    fun chatTemplate(): String? {
        check(loaded) { "no model is loaded" }
        return nativeChatTemplate(handle)
    }

    /**
     * Formats [messages] (role to content, in the order to send) through llama.cpp's own
     * template applier -- for a custom-pasted model whose family is not known in advance,
     * so nothing in this module can hand-write its prompt the way [LocalLlmTranslator]'s
     * Qwen3 wrapper and [LocalLiquidTranslator]'s LFM2 wrapper do for the families they
     * know (`LlmStore.ModelFamily.GENERIC`).
     *
     * `llama_chat_apply_template` recognizes 60+ named chat-template families by sniffing
     * the GGUF's own embedded template string -- Llama 3.x, Gemma, Mistral, Phi, DeepSeek,
     * and more. A model with **no** embedded template is not a failure case: the native
     * call already defaults that to plain ChatML, matching llama.cpp's own convention.
     *
     * @return null only when the embedded template exists but matches none of the known
     *   families -- a real, if rare, failure for an arbitrary pasted model. There is no
     *   channel here for template kwargs (no `enable_thinking`-equivalent), so a
     *   hybrid-reasoning custom model may still exhaust its token budget thinking; that is
     *   a limit of this call, not something a null return reports.
     */
    fun applyTemplate(messages: List<Pair<String, String>>, addAssistant: Boolean = true): String? {
        check(loaded) { "no model is loaded" }
        return nativeApplyTemplate(
            handle,
            messages.map { it.first }.toTypedArray(),
            messages.map { it.second }.toTypedArray(),
            addAssistant,
        )
    }

    /**
     * Section 4 of the doc: unload the moment the work is done. A gigabyte held resident
     * between pages is how a 6 GB phone gets the app killed while it is backgrounded.
     */
    override fun close() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0L
        }
    }

    private external fun nativeLoad(modelPath: String, contextSize: Int, threads: Int): Long

    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int): String

    private external fun nativeChatTemplate(handle: Long): String?

    private external fun nativeApplyTemplate(
        handle: Long,
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean,
    ): String?

    private external fun nativeFree(handle: Long)

    companion object {
        /**
         * Section 3: 2048 leaves headroom for a chunked page and keeps the KV cache small.
         * The model's own window is far larger; this is a memory decision, not a limit.
         */
        const val CONTEXT = 2048

        /** Section 3: the four A78s, never the four A55s as well. */
        const val THREADS = 4

        /** False when the build carries no native library, so tier 1 can say so. */
        val available: Boolean by lazy {
            runCatching { System.loadLibrary("mangareader_llama") }.isSuccess
        }
    }
}

/**
 * Whether this build carries a native inference library that loads on this device.
 *
 * Public because the settings screen has to say so plainly rather than offering a tier
 * that cannot run; [LlamaBridge] itself stays internal to this module.
 */
fun llamaAvailable(): Boolean = LlamaBridge.available
