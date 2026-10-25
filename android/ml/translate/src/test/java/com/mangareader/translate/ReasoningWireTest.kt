package com.mangareader.translate

import com.mangareader.model.Box
import com.mangareader.model.Region
import com.mangareader.model.RegionKind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatProtocol.OPENAI_REASONING] and [ChatProtocol.DEEPSEEK], added after
 * `tools/probe_endpoints.py` measured that a bare numeric `max_tokens` on the classic
 * [ChatProtocol.OPENAI] wire either 400s or gets silently spent on hidden reasoning --
 * see [ChatProtocol]'s doc for the numbers. [RemoteTranslatorTest] and
 * [AnthropicTranslatorTest] cover the two wires that predate this file; those two are
 * new, so what is worth pinning here is exactly what each sends that neither of the
 * others do.
 */
class ReasoningWireTest {

    private val key = "sk-secret-value-do-not-leak-9999"

    private val regions = listOf(
        region(7, RegionKind.TEXT_BUBBLE, readingIndex = 0, text = "そっちから煙見える？"),
        region(3, RegionKind.TEXT_BUBBLE, readingIndex = 1, text = "合流地点変えていい？"),
    )

    // -- OPENAI_REASONING (gpt-5.6-luna and the rest of the current OpenAI family) ------

    @Test
    fun `openai reasoning wire sends max_completion_tokens and reasoning_effort, never max_tokens or temperature`() =
        runBlocking {
            var seen: HttpRequestData? = null
            translator(ChatProtocol.OPENAI_REASONING) { request ->
                seen = request
                ok("""{"translations":[{"id":1,"en":"Can you see the smoke?"}]}""")
            }.use { it.translatePage(regions) }

            val body = seen!!.bodyText()
            // 2 bubbles -> 256 + 96*2 = 448.
            assertTrue(
                "the computed budget must reach the wire as max_completion_tokens",
                body.contains("\"max_completion_tokens\":448"),
            )
            assertFalse(
                "gpt-5.6-luna 400s on the classic spelling -- it must never be sent",
                body.contains("\"max_tokens\""),
            )
            assertFalse(
                "gpt-5.6-luna 400s on any temperature other than 1 -- omit it entirely",
                body.contains("temperature"),
            )
            assertTrue(
                "reasoning_effort keeps hidden reasoning inside the budget instead of " +
                    "hoping the budget is big enough (measured: 853 -> 235 reasoning " +
                    "tokens on the same real page)",
                body.contains("\"reasoning_effort\":\"low\""),
            )
        }

    @Test
    fun `openai reasoning wire still uses bearer auth on chat_completions`() = runBlocking {
        var seen: HttpRequestData? = null
        translator(ChatProtocol.OPENAI_REASONING) { request ->
            seen = request
            ok("""{"translations":[{"id":1,"en":"Hi."}]}""")
        }.use { it.translatePage(regions) }

        assertTrue(seen!!.url.encodedPath.endsWith("/chat/completions"))
        assertEquals("Bearer $key", seen!!.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `openai reasoning wire parses choices content the same way the classic wire does`() = runBlocking {
        val result = translator(ChatProtocol.OPENAI_REASONING) {
            ok("""{"translations":[{"id":1,"en":"Can you see the smoke?"},{"id":2,"en":"OK?"}]}""")
        }.use { it.translatePage(regions) }

        assertEquals("Can you see the smoke?", result[7L])
        assertEquals("OK?", result[3L])
    }

    @Test
    fun `id carries the openai_reasoning segment, distinct from plain openai, never the key`() {
        val id = RemoteTranslator(
            "https://api.openai.com/v1",
            key,
            "gpt-5.6-luna",
            ChatProtocol.OPENAI_REASONING,
        ).id

        assertEquals("remote:openai_reasoning:api.openai.com:gpt-5.6-luna", id)
        assertFalse(id.contains(key))
    }

    // -- DEEPSEEK (deepseek-v4-flash) ---------------------------------------------------

    @Test
    fun `deepseek wire sends max_tokens, temperature 0, and thinking disabled`() = runBlocking {
        var seen: HttpRequestData? = null
        translator(ChatProtocol.DEEPSEEK) { request ->
            seen = request
            ok("""{"translations":[{"id":1,"en":"Can you see the smoke?"}]}""")
        }.use { it.translatePage(regions) }

        val body = seen!!.bodyText()
        // 2 bubbles -> 256 + 96*2 = 448.
        assertTrue(
            "deepseek accepts the classic cap spelling",
            body.contains("\"max_tokens\":448"),
        )
        assertTrue(
            "structured output stays deterministic here too",
            body.contains("\"temperature\":0"),
        )
        assertTrue(
            "without this, deepseek-v4-flash spent an entire 4096-token cap on hidden " +
                "reasoning and returned zero visible characters on every one of four " +
                "real pages -- this is not an optional tuning knob",
            body.contains(""""thinking":{"type":"disabled"}"""),
        )
    }

    @Test
    fun `deepseek wire uses bearer auth like the classic openai wire, not x-api-key`() = runBlocking {
        var seen: HttpRequestData? = null
        translator(ChatProtocol.DEEPSEEK) { request ->
            seen = request
            ok("""{"translations":[{"id":1,"en":"Hi."}]}""")
        }.use { it.translatePage(regions) }

        assertEquals("Bearer $key", seen!!.headers[HttpHeaders.Authorization])
        assertFalse(seen!!.headers.contains("x-api-key"))
    }

    @Test
    fun `id carries the deepseek segment, distinct from plain openai, never the key`() {
        val id = RemoteTranslator(
            "https://api.deepseek.com/v1",
            key,
            "deepseek-v4-flash",
            ChatProtocol.DEEPSEEK,
        ).id

        assertEquals("remote:deepseek:api.deepseek.com:deepseek-v4-flash", id)
        assertFalse(id.contains(key))
    }

    // -- plumbing -----------------------------------------------------------------

    private fun translator(
        protocol: ChatProtocol,
        apiKey: String = key,
        handler: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = RemoteTranslator(
        baseUrl = if (protocol == ChatProtocol.DEEPSEEK) "https://api.deepseek.com/v1" else "https://api.openai.com/v1",
        apiKey = apiKey,
        model = if (protocol == ChatProtocol.DEEPSEEK) "deepseek-v4-flash" else "gpt-5.6-luna",
        protocol = protocol,
        engine = MockEngine { request -> handler(request) },
    )

    private fun MockRequestHandleScope.ok(json: String) = respond(
        content = ByteReadChannel(
            """{"choices":[{"message":{"role":"assistant","content":${quoted(json)}}}]}""",
        ),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun quoted(value: String) =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private suspend fun HttpRequestData.bodyText(): String {
        val content = body as io.ktor.http.content.OutgoingContent.ByteArrayContent
        return String(content.bytes(), Charsets.UTF_8)
    }

    private fun region(id: Long, kind: RegionKind, readingIndex: Int, text: String) = Region(
        id = id,
        kind = kind,
        box = Box(0f, 0f, 0f, 0f),
        score = 1f,
        readingIndex = readingIndex,
        ocrText = text,
    )
}
