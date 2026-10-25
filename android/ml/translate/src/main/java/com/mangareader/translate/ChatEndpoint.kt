package com.mangareader.translate

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.UnknownServiceException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Which wire format an endpoint speaks. [ChatEndpoint] picks the [ChatWire] for it.
 *
 * Three of these four look alike from the outside -- all POST `/chat/completions` with a
 * `choices[0].message.content` reply -- but a bare numeric `max_tokens` cap is not enough
 * to keep any of them from spending their whole budget on hidden reasoning and returning
 * no visible text at all. Measured directly against each provider's smallest current
 * model before this enum grew these two extra values (`tools/probe_endpoints.py`):
 *
 * - `gpt-5.6-luna` (2026-09): 400s on `max_tokens` and on `temperature != 1`. Needs
 *   `max_completion_tokens` and no `temperature`. Reasoning is real but self-bounded
 *   (roughly 150-850 tokens on this app's page-translation prompt) -- `reasoning_effort:
 *   "low"` keeps it well inside the existing per-page budget instead of merely hoping the
 *   budget is generous enough.
 * - `deepseek-v4-flash`: accepts the classic `max_tokens` + `temperature` shape, which
 *   made this the easy one to miss. On every one of four real pages it spent the *entire*
 *   cap on hidden reasoning and returned zero visible characters (`finish_reason:
 *   "length"`, `completion_tokens == reasoning_tokens == the cap`, even at a 4096-token
 *   cap) -- not a truncation to guard against with a bigger number, a wall to route
 *   around. `"thinking": {"type": "disabled"}` is DeepSeek's own switch for it; with it
 *   set, the same four pages used 100-175 tokens each, all visible.
 *
 * [ChatProtocol.OPENAI] stays the plain, unmodified dialect -- untouched for
 * [RemoteProvider.OLLAMA], [RemoteProvider.VLLM], [RemoteProvider.CUSTOM], and any older
 * non-reasoning OpenAI model a user types in. A self-hosted Qwen3 server can exhibit the
 * same all-reasoning-no-output failure (confirmed against this repo's own vLLM instance),
 * but its fix -- `chat_template_kwargs: {"enable_thinking": false}` -- is a detail of one
 * model family a `CUSTOM`/`VLLM` base URL gives no way to detect, so it is not built into
 * this wire; see `docs/on-device-llm.md` for the harness-side bake-off workaround.
 */
enum class ChatProtocol { OPENAI, OPENAI_REASONING, DEEPSEEK, ANTHROPIC }

/**
 * One chat completion call, and the discipline around it.
 *
 * Factored out of [RemoteTranslator] when [RemoteGrammarExplainer] needed the same thing.
 * The duplication worth avoiding here is not the twenty lines of Ktor setup -- it is the
 * rule those lines exist to enforce:
 *
 * **The key never leaves this class.** It is not logged, not put in a cache key, and not
 * included in any message thrown from here. [describe] builds error strings from the
 * status code and the host alone, never from the response body, because an upstream error
 * body can echo the request back and the request carries the key. That rule has to hold
 * for every caller, and the way to make it hold is to have one caller.
 *
 * [ChatProtocol.ANTHROPIC] does not weaken this: the wire that touches the key ([ChatWire]
 * below) is `private` to this file, so no other file can even name the type. The key still
 * never leaves -- the boundary just moved from "this class" to "this file".
 */
internal class ChatEndpoint(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    protocol: ChatProtocol = ChatProtocol.OPENAI,
    /** Swapped for a `MockEngine` by this module's tests. */
    engine: HttpClientEngine? = null,
) : AutoCloseable {

    private val wire: ChatWire = when (protocol) {
        ChatProtocol.OPENAI -> OpenAiWire
        ChatProtocol.OPENAI_REASONING -> OpenAiReasoningWire
        ChatProtocol.DEEPSEEK -> DeepSeekWire
        ChatProtocol.ANTHROPIC -> AnthropicWire
    }

    private val endpoint = baseUrl.trim().trimEnd('/') + wire.path

    val host: String = runCatching { java.net.URI(baseUrl.trim()).host }
        .getOrNull()
        .orEmpty()
        .ifEmpty { "remote" }

    private val client: HttpClient by lazy {
        val block: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) {
                // Endpoints add fields freely -- usage blocks, system fingerprints,
                // provider extensions -- and none of them are this class's business.
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                // A dense page is a long generation even on a hosted model, and a phone
                // on mobile data is not a fast link. Generous, but bounded: the reader
                // shows a running state and the user can leave the screen.
                requestTimeoutMillis = 180_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
            expectSuccess = false
        }
        if (engine != null) HttpClient(engine, block) else HttpClient(OkHttp, block)
    }

    suspend fun complete(system: String, user: String, maxTokens: Int): String {
        val response: HttpResponse = try {
            client.post(endpoint) {
                contentType(ContentType.Application.Json)
                wire.prepare(this, apiKey, model.trim(), system, user, maxTokens)
            }
        } catch (error: SSLHandshakeException) {
            throw TranslatorException(
                "Could not establish a secure HTTPS connection to $host. Check the server's " +
                    "TLS certificate, certificate chain, and the phone's date and time.",
            )
        } catch (error: SSLPeerUnverifiedException) {
            throw TranslatorException(
                "Could not verify $host's HTTPS certificate. Check that the certificate is " +
                    "trusted and matches the hostname in the base URL.",
            )
        } catch (error: UnknownServiceException) {
            throw TranslatorException(
                "$host is plain http and this app only allows that to localhost. Use " +
                    "https, or forward it to localhost with adb reverse.",
            )
        } catch (error: IOException) {
            throw TranslatorException("Could not reach $host. Check the base URL and the network.")
        }

        if (!response.status.isSuccess()) throw TranslatorException(describe(response.status))

        return wire.textOf(response, host)
    }

    /**
     * Status codes as sentences a user can act on. Deliberately built from the code and
     * the host alone, never from the response body -- see the class note.
     */
    private fun describe(status: HttpStatusCode): String = when (status.value) {
        400 -> "$host rejected the request (400). Check the model name in Settings."
        401, 403 -> "$host rejected the API key (${status.value}). Check it in Settings."
        404 -> "$host has no ${wire.path} at that base URL (404). Check the base URL."
        413 -> "That request was too large for $host (413). Try a page with fewer bubbles."
        429 -> "$host is rate-limiting or out of quota (429). Try again shortly."
        in 500..599 -> "$host had a server error (${status.value}). Try again shortly."
        else -> "$host returned ${status.value} ${status.description}."
    }

    override fun close() {
        runCatching { client.close() }
    }
}

/** A failure with a message that is safe to put on a screen. */
class TranslatorException(message: String) : Exception(message)

/**
 * The four things a wire format is: a path, how it sets a request up, and how it reads
 * the answer back out. Both implementations below are `private object`s -- stateless, so
 * there is nowhere for a key to be retained even by accident, and unnameable outside this
 * file, so the class doc's "the key never leaves this class" narrows to "this file" rather
 * than being broken.
 */
private sealed interface ChatWire {
    /** Appended to the trimmed base URL. Also the path [ChatEndpoint.describe] names. */
    val path: String

    /**
     * Sets method-independent request state: headers and body. Takes the builder rather
     * than returning a body object, because Ktor's kotlinx.serialization converter
     * resolves the serializer from the compile-time type of what reaches `setBody` --
     * a `fun body(): Any` compiles and then fails at runtime with "serializer for class
     * Any not found". Each wire calls `setBody` with its own concrete request type here,
     * so the type stays static.
     */
    fun prepare(
        builder: HttpRequestBuilder,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
    )

    /** The assistant's text, or a [TranslatorException] built from [host] alone. */
    suspend fun textOf(response: HttpResponse, host: String): String
}

private object OpenAiWire : ChatWire {
    override val path = "/chat/completions"

    /**
     * Fallback for a caller that passes 0 or less. [RemoteTranslator] and
     * [RemoteGrammarExplainer] always compute a positive budget, so in practice this is
     * only reached by a future caller that forgets to -- unlike the identically-shaped
     * dead code this replaced, this one bounds an actual code path other than "never".
     */
    private const val FALLBACK_MAX_TOKENS = 2048

    override fun prepare(
        builder: HttpRequestBuilder,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
    ) {
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer " + apiKey.trim())
        builder.setBody(
            ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", system),
                    ChatMessage("user", user),
                ),
                // Structured output decoded as deterministically as the wire format
                // allows; sampling here mostly buys malformed JSON.
                temperature = 0.0,
                maxTokens = if (maxTokens > 0) maxTokens else FALLBACK_MAX_TOKENS,
            ),
        )
    }

    override suspend fun textOf(response: HttpResponse, host: String): String =
        openAiTextOf(response, host)
}

/**
 * `gpt-5.6-luna` and the rest of the current OpenAI reasoning family: `max_tokens` and
 * `temperature` are both rejected outright (measured -- see [ChatProtocol]'s doc). This
 * wire sends the modern spelling and no sampling parameter, plus `reasoning_effort: "low"`
 * so the hidden-reasoning share of the cap stays small instead of merely hoping the
 * caller's budget covers whatever the model decides to spend.
 */
private object OpenAiReasoningWire : ChatWire {
    override val path = "/chat/completions"

    private const val FALLBACK_MAX_TOKENS = 2048

    override fun prepare(
        builder: HttpRequestBuilder,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
    ) {
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer " + apiKey.trim())
        builder.setBody(
            OpenAiReasoningRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", system),
                    ChatMessage("user", user),
                ),
                maxCompletionTokens = if (maxTokens > 0) maxTokens else FALLBACK_MAX_TOKENS,
                reasoningEffort = "low",
            ),
        )
    }

    override suspend fun textOf(response: HttpResponse, host: String): String =
        openAiTextOf(response, host)
}

/**
 * DeepSeek's `/chat/completions` accepts the classic `max_tokens` + `temperature` shape --
 * the trap is that it accepts it while quietly reasoning past it. Measured against four
 * real pages of this app's own translation prompt: every one spent its *entire* token cap
 * on hidden reasoning and returned no visible text at all, even at a 4096-token cap (see
 * [ChatProtocol]'s doc). `"thinking": {"type": "disabled"}` is the fix -- not a bigger
 * number, since a bigger number just bought more reasoning, not an answer.
 */
private object DeepSeekWire : ChatWire {
    override val path = "/chat/completions"

    private const val FALLBACK_MAX_TOKENS = 2048

    override fun prepare(
        builder: HttpRequestBuilder,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
    ) {
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer " + apiKey.trim())
        builder.setBody(
            DeepSeekRequest(
                model = model,
                messages = listOf(
                    ChatMessage("system", system),
                    ChatMessage("user", user),
                ),
                temperature = 0.0,
                maxTokens = if (maxTokens > 0) maxTokens else FALLBACK_MAX_TOKENS,
                thinking = ThinkingConfig(type = "disabled"),
            ),
        )
    }

    override suspend fun textOf(response: HttpResponse, host: String): String =
        openAiTextOf(response, host)
}

/**
 * The `choices[0].message.content` shape is identical across [OpenAiWire],
 * [OpenAiReasoningWire] and [DeepSeekWire] -- only the request differs between them.
 */
private suspend fun openAiTextOf(response: HttpResponse, host: String): String {
    val body = runCatching { response.body<ChatResponse>() }.getOrNull()
        ?: throw TranslatorException("$host returned something that is not a chat completion.")
    return body.choices.firstOrNull()?.message?.content
        ?: throw TranslatorException("$host returned no choices for this request.")
}

/**
 * The Anthropic Messages API. Differs from [OpenAiWire] in every dimension the interface
 * has: `/v1/messages` rather than `/chat/completions`, `x-api-key` + a version header
 * rather than a bearer token, a top-level `system` string rather than a system message,
 * a required `max_tokens`, and a `content` block array rather than `choices[0].message`.
 *
 * Deliberately sends no `temperature`. Current Claude models (the ones this app's default
 * model string names) reject sampling parameters on a chat-completion-shaped request with
 * HTTP 400; omitting it is the one choice that works on every model a user might type in.
 */
private object AnthropicWire : ChatWire {
    override val path = "/messages"

    /**
     * Not 1024. `claude-opus-5` runs adaptive thinking by default even when the
     * `thinking` field is omitted, and those tokens count against `max_tokens` -- a cap
     * sized for the visible JSON alone truncates mid-object on a dense page, and
     * [parseResponse] is tolerant enough to silently hand back a partial page rather than
     * fail loudly on it.
     */
    private const val MAX_TOKENS = 8192

    override fun prepare(
        builder: HttpRequestBuilder,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        maxTokens: Int,
    ) {
        if (apiKey.isNotBlank()) builder.header("x-api-key", apiKey.trim())
        builder.header("anthropic-version", "2023-06-01")
        builder.setBody(
            AnthropicRequest(
                model = model,
                maxTokens = if (maxTokens > 0) maxTokens else MAX_TOKENS,
                system = system,
                messages = listOf(AnthropicMessage("user", user)),
            ),
        )
    }

    override suspend fun textOf(response: HttpResponse, host: String): String {
        val body = runCatching { response.body<AnthropicResponse>() }.getOrNull()
            ?: throw TranslatorException("$host returned something that is not a message.")
        val text = body.content.filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("")
        if (text.isNotEmpty()) return text

        // stop_reason is a bounded server-side enum, not free text -- the sentences below
        // are constants selected by it, never the value itself, to keep the class's rule
        // that nothing from the response body reaches a thrown message verbatim.
        throw TranslatorException(
            when (body.stopReason) {
                "refusal" -> "$host declined this page."
                "max_tokens" -> "$host ran out of room on this page (try a page with fewer bubbles)."
                else -> "$host returned no text for this request."
            },
        )
    }
}

@Serializable
private data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int,
)

/** [OpenAiReasoningWire]'s body: the modern cap spelling, no `temperature`. */
@Serializable
private data class OpenAiReasoningRequest(
    val model: String,
    val messages: List<ChatMessage>,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int,
    // No default: this file's Json omits a property equal to its declared default
    // (`encodeDefaults` is off), and this field must always reach the wire.
    @SerialName("reasoning_effort") val reasoningEffort: String,
)

/** [DeepSeekWire]'s body: classic shape plus the field that turns hidden reasoning off. */
@Serializable
private data class DeepSeekRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double,
    @SerialName("max_tokens") val maxTokens: Int,
    val thinking: ThinkingConfig,
)

/** Same note as [OpenAiReasoningRequest.reasoningEffort]: no default, always explicit. */
@Serializable
private data class ThinkingConfig(val type: String)

@Serializable
private data class ChatMessage(val role: String, val content: String)

/** The same shape coming back, except that `content` is not guaranteed to be there. */
@Serializable
private data class ReplyMessage(val role: String = "assistant", val content: String? = null)

@Serializable
private data class ChatResponse(
    val choices: List<Choice> = emptyList(),
)

@Serializable
private data class Choice(
    val message: ReplyMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    /** A top-level string, not a message -- the Anthropic wire's system prompt slot. */
    val system: String,
    val messages: List<AnthropicMessage>,
    // No `temperature`: see AnthropicWire's class note.
)

@Serializable
private data class AnthropicMessage(val role: String, val content: String)

@Serializable
private data class AnthropicResponse(
    val content: List<AnthropicBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
)

/** `text` is absent on non-text blocks (thinking, tool use), hence nullable. */
@Serializable
private data class AnthropicBlock(val type: String = "", val text: String? = null)
