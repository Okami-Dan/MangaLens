package com.mangareader.translate

import com.mangareader.model.Region
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Section 5 tier 2: any OpenAI-compatible endpoint, or Anthropic's Messages API.
 *
 * One text field for the base URL is what makes the OpenAI-compatible half a whole
 * quality tier rather than one vendor -- the same wire format serves the commercial APIs,
 * a self-hosted vLLM and a llama.cpp server, so a user with a machine at home and a user
 * with an API key get the same code path. That is also why the API key is optional: a
 * local server usually has none, and demanding one would exclude exactly the setup
 * section 5 calls out. [protocol] exists because Anthropic's Messages API is not that
 * format -- see [ChatEndpoint]'s wires.
 *
 * The whole page goes in one call. Chunking is a tier-1 accommodation for a 1.7B model
 * that stops early (see [LocalLlmTranslator]); a hosted model has no such trouble, and
 * splitting the page would cost section 2.5's scene context for nothing.
 *
 * The HTTP call itself, and the rule that the key never leaves the class that holds it,
 * live in [ChatEndpoint] -- shared with [RemoteGrammarExplainer], which needs both.
 */
class RemoteTranslator internal constructor(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val protocol: ChatProtocol = ChatProtocol.OPENAI,
    /**
     * Swapped for a `MockEngine` by this module's tests. Internal on purpose: made a
     * public default argument it would put Ktor's types on the classpath of every module
     * that constructs a translator, and `:app` has no business seeing them.
     */
    private val engine: HttpClientEngine?,
) : Translator {

    constructor(
        baseUrl: String,
        apiKey: String,
        model: String,
        protocol: ChatProtocol = ChatProtocol.OPENAI,
    ) : this(baseUrl, apiKey, model, protocol, null)

    private val chat by lazy { ChatEndpoint(baseUrl, apiKey, model, protocol, engine) }

    /**
     * Both the host and the model matter. The host alone would serve one model's
     * translations from another's cache entry after the user switches model; the model
     * alone would do the same across two endpoints that happen to expose the same model
     * name. [protocol] additionally guards the two loopback presets (Ollama, vLLM), which
     * can share a host and a user-typed model name while speaking different wires.
     */
    override val id: String = remoteId(protocol, baseUrl, model)

    override val label: String = model.trim().ifEmpty { "Remote LLM" }

    /** No key required -- a self-hosted endpoint typically has none. */
    override val configured: Boolean =
        baseUrl.isNotBlank() && model.isNotBlank()

    override suspend fun translatePage(
        regions: List<Region>,
        pageContext: String,
        cover: Boolean,
        onProgress: (Int, Int) -> Unit,
    ): Map<Long, String?> = withContext(Dispatchers.IO) {
        val request = buildRequest(regions, pageContext, cover)
        if (request.isEmpty) return@withContext emptyMap()

        val mapping = indexMap(regions, cover)
        onProgress(0, request.bubbles.size)

        val budget = minOf(
            MAX_TOKENS,
            TOKENS_OVERHEAD + TOKENS_PER_BUBBLE * request.bubbles.size,
        )
        val raw = chat.complete(systemPrompt(cover), requestJson(request), budget)
        val parsed = parseResponse(raw, request.bubbles.map { it.id })
        onProgress(request.bubbles.size, request.bubbles.size)

        // An id the model invented that is not in the map is dropped rather than mapped
        // onto whichever region happens to sit at that index.
        parsed.mapNotNull { (index, english) ->
            mapping[index]?.let { it to english }
        }.toMap()
    }

    override fun close() {
        chat.close()
    }

    private companion object {
        /**
         * A hosted model does not stop early the way tier 1 does (see
         * [LocalLlmTranslator.TOKENS_PER_BUBBLE]'s note), so this budget only matters on
         * the Anthropic wire, where `max_tokens` is required and thinking tokens count
         * against it. Generous relative to the local budget because a remote page is a
         * few seconds either way, not a minute.
         */
        const val TOKENS_PER_BUBBLE = 96
        const val TOKENS_OVERHEAD = 256
        const val MAX_TOKENS = 8192
    }
}

/** The host, for the cache id. Falls back to a constant rather than to an empty string. */
internal fun hostOf(baseUrl: String): String = runCatching { java.net.URI(baseUrl.trim()).host }
    .getOrNull()
    .orEmpty()
    .ifEmpty { "remote" }

/**
 * The cache id shared by [RemoteTranslator] and [RemoteGrammarExplainer].
 *
 * The OpenAI branch is exactly the id format both classes used before [ChatProtocol]
 * existed, so every OpenAI and DeepSeek configuration a user already has keeps its cache
 * entries. Anthropic gets its own segment so it cannot collide with an OpenAI-wire
 * endpoint that happens to share a host and model name -- which the two loopback presets
 * (Ollama, vLLM) make a real possibility, not a hypothetical one.
 */
internal fun remoteId(protocol: ChatProtocol, baseUrl: String, model: String): String =
    "remote:" +
        (if (protocol == ChatProtocol.OPENAI) "" else protocol.name.lowercase() + ":") +
        hostOf(baseUrl) + ":" + model.trim()
