package com.mangareader.translate

import android.util.Log
import com.mangareader.model.GrammarAnswer
import com.mangareader.model.Phrase
import com.mangareader.model.LexicalUnit
import com.mangareader.model.TranslationContext
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Section 3.3 tier 4's optional half: a model's prose notes on top of the table's.
 *
 * A sibling of [Translator], with the same three tiers and the same rule about tier 0 --
 * the section is already filled in before this is ever asked, because the bundled rule
 * table produced it offline. This only ever **adds**. If it is not configured, if it
 * fails, or if the user never taps the button, the breakdown is still there.
 *
 * The call is **per bubble, on demand**, which is the opposite of [Translator]'s per-page
 * rule and is deliberate. A translation is worth prefetching for the whole page because
 * the user will read every bubble; a grammar explanation is worth having for the one line
 * they stopped on. On tier 1 that is a few seconds against the 45-60s a page costs, and on
 * tier 2 it is one small request the user explicitly asked for.
 */
interface GrammarExplainer : AutoCloseable {

    /**
     * Goes into the cache key and onto the note, so one model's notes are not served from
     * another's entry and the sheet can badge who wrote them.
     */
    val id: String

    /** For the sheet's footnote and the button's label. */
    val label: String

    /** False when the tier is selected but not usable yet -- no key, no model file. */
    val configured: Boolean

    /**
     * @param text the bubble's recognized Japanese, corrections included
     * @param phrases the offline breakdown, numbered, so the prompt can ask the model to
     *   improve on it token by token rather than to produce a second copy of it
     * @return explanations keyed by `Token.position`, plus any head corrections. Empty on
     *   anything the parser could not use -- never an exception for a badly-shaped answer,
     *   though a transport failure does throw so the sheet can say what went wrong.
     */
    suspend fun explain(text: String, phrases: List<Phrase>, words: List<LexicalUnit> = emptyList(),
        context: TranslationContext = TranslationContext()): GrammarAnswer

    override fun close() {}
}

/**
 * Tier 0, and the default. [configured] being false is what the sheet reads to show the
 * "choose a translator in Settings" line instead of a button that cannot work.
 */
object NoOpGrammarExplainer : GrammarExplainer {
    override val id = "none"
    override val label = "None (dictionary only)"
    override val configured = false
    override suspend fun explain(
        text: String,
        phrases: List<Phrase>,
        words: List<LexicalUnit>,
        context: TranslationContext,
    ): GrammarAnswer = GrammarAnswer.NONE
}

/** Tier 2: the same remote endpoint the translator uses -- OpenAI-compatible or Anthropic. */
class RemoteGrammarExplainer internal constructor(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val protocol: ChatProtocol = ChatProtocol.OPENAI,
    private val engine: HttpClientEngine?,
) : GrammarExplainer {

    constructor(
        baseUrl: String,
        apiKey: String,
        model: String,
        protocol: ChatProtocol = ChatProtocol.OPENAI,
    ) : this(baseUrl, apiKey, model, protocol, null)

    private val chat by lazy { ChatEndpoint(baseUrl, apiKey, model, protocol, engine) }

    override val id: String = remoteId(protocol, baseUrl, model)

    override val label: String = model.trim().ifEmpty { "Remote LLM" }

    override val configured: Boolean = baseUrl.isNotBlank() && model.isNotBlank()

    override suspend fun explain(
        text: String,
        phrases: List<Phrase>,
        words: List<LexicalUnit>,
        context: TranslationContext,
    ): GrammarAnswer = withContext(Dispatchers.IO) {
        if (text.isBlank() || phrases.isEmpty()) return@withContext GrammarAnswer.NONE
        val raw = chat.complete(GRAMMAR_SYSTEM_PROMPT + "\n\n" + NAMING_CONTEXT,
            grammarRequest(text, phrases, words, context), MAX_TOKENS)
        parseGrammarNotes(raw).validated(phrases, words)
    }

    override fun close() {
        chat.close()
    }

    private companion object {
        /** One bubble's worth of notes, not a page -- see the class doc's per-bubble rule. */
        const val MAX_TOKENS = 2048
    }
}

/**
 * Tier 1: the model already on the phone, for `ModelFamily.QWEN3` and `ModelFamily.GENERIC`
 * alike -- see [LocalLlmTranslator]'s class doc for what distinguishes the two and why
 * `ModelFamily.LFM2_MT` gets neither this class nor that one.
 *
 * Reuses [LlamaBridge]; [promptFamily] is the same seam [LocalLlmTranslator] has, and for
 * the same reason -- see that class's doc for why it is an enum rather than a strategy
 * function typed over the `internal` [LlamaBridge]. Qwen3's `<think></think>`
 * direct-mode prefill versus asking llama.cpp to detect an unknown model's own chat
 * template. No chunking: chunking exists because a 1.7B model stops early on an
 * eleven-item list, and this asks for a handful of notes about one line.
 */
class LocalGrammarExplainer(
    private val modelFile: File,
    override val id: String,
    private val contextSize: Int = LlamaBridge.CONTEXT,
    private val promptFamily: ChatPromptFamily = ChatPromptFamily.QWEN3,
) : GrammarExplainer {

    override val label = "Local LLM"

    override val configured: Boolean
        get() = LlamaBridge.available && modelFile.isFile && modelFile.length() > 0

    private var bridge: LlamaBridge? = null

    override suspend fun explain(
        text: String,
        phrases: List<Phrase>,
        words: List<LexicalUnit>,
        context: TranslationContext,
    ): GrammarAnswer = withContext(Dispatchers.Default) {
        if (text.isBlank() || phrases.isEmpty()) return@withContext GrammarAnswer.NONE

        val llama = LlamaBridge().also { bridge = it }
        try {
            val started = System.nanoTime()
            llama.load(modelFile, contextSize)
            Log.i(TAG, "loaded in " + ((System.nanoTime() - started) / 1_000_000) + " ms")

            val prompt = buildPrompt(promptFamily, llama, GRAMMAR_SYSTEM_PROMPT + "\n\n" + NAMING_CONTEXT,
                grammarRequest(text, phrases, words, context))
            parseGrammarNotes(llama.generate(prompt, MAX_NEW_TOKENS)).validated(phrases, words)
        } finally {
            // Whatever happened, the gigabyte goes back.
            llama.close()
            bridge = null
        }
    }

    override fun close() {
        bridge?.close()
        bridge = null
    }

    companion object {
        /**
         * Raised from 512 with the move to per-token notes.
         *
         * The old shape asked for four or five notes about a whole line. This one can be
         * asked about ten tokens, and a ten-entry JSON array of one-sentence notes does
         * not fit 512 -- the answer was being truncated mid-object, which the parser then
         * dropped as malformed. The cap exists to stop a model that never emits
         * end-of-generation from costing two minutes of four-core CPU for one bubble, not
         * to budget an answer that was asked for.
         */
        const val MAX_NEW_TOKENS = 768

        private const val TAG = "LocalGrammarExplainer"
    }
}
