package com.mangareader.translate

import com.mangareader.model.Box
import com.mangareader.model.Region
import com.mangareader.model.RegionKind
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2 over a mock transport.
 *
 * What is worth asserting here is not that Ktor can POST. It is the three things that
 * would be quietly wrong in production and invisible in a manual test: that the request
 * carries section 7's shape, that a failing endpoint produces a message a user can act on
 * *without* the API key in it, and that the 1..N indices come back attached to the right
 * region ids.
 */
class RemoteTranslatorTest {

    private val key = "sk-secret-value-do-not-leak-9999"

    /** Region ids deliberately out of order and non-contiguous, as a real page's are. */
    private val regions = listOf(
        region(7, RegionKind.TEXT_BUBBLE, readingIndex = 0, text = "そっちから煙見える？"),
        region(3, RegionKind.TEXT_BUBBLE, readingIndex = 1, text = "合流地点変えていい？"),
        region(5, RegionKind.TEXT_SFX, readingIndex = 2, text = "ドドド"),
    )

    @Test
    fun `sends section 7's request and maps the answer back onto region ids`() = runBlocking {
        var seen: HttpRequestData? = null
        val translator = translator { request ->
            seen = request
            ok(
                """{"translations":[{"id":1,"en":"Can you see the smoke?"},""" +
                    """{"id":2,"en":null},{"id":3,"en":"FWOOSH"}]}""",
            )
        }

        val result = translator.use {
            it.translatePage(regions, pageContext = "Volume 3, night scene")
        }

        val body = seen!!.bodyText()
        assertTrue("the system prompt must be sent", body.contains("manga reading order"))
        assertTrue("the page context must be sent", body.contains("Volume 3, night scene"))
        assertTrue("bubbles are numbered from 1", body.contains("""\"id\": 1"""))
        assertTrue("sound effects are sent too now (section 2.6)", body.contains("ドドド"))
        assertTrue("temperature must be 0 for structured output", body.contains("\"temperature\":0"))
        // The bug this pins: OpenAiWire used to accept `maxTokens` and drop it, sending an
        // uncapped request to every OpenAI, DeepSeek, Ollama and vLLM endpoint. 3 bubbles
        // (the sound effect counts, per section 2.6) -> 256 + 96*3 = 544.
        assertTrue(
            "the computed per-page budget must actually reach the wire as max_tokens",
            body.contains("\"max_tokens\":544"),
        )

        // Request index 1 was region 7, index 2 was region 3 -- not 1 and 2.
        assertEquals("Can you see the smoke?", result[7L])
        assertTrue("an explicit decline is stored as a null, not dropped", result.containsKey(3L))
        assertNull(result[3L])
        // The sound effect is bubble 3 and comes back like any other line. Under the
        // old section 2.6 it was never in the request at all.
        assertEquals("FWOOSH", result[5L])
    }

    @Test
    fun `sends the key as a bearer token when there is one`() = runBlocking {
        var seen: HttpRequestData? = null
        translator { request ->
            seen = request
            ok("""{"translations":[{"id":1,"en":"Hi."}]}""")
        }.use { it.translatePage(regions) }

        assertEquals("Bearer $key", seen!!.headers[HttpHeaders.Authorization])
    }

    /** Section 5's self-hosted vLLM / llama.cpp case: no account, so no key. */
    @Test
    fun `omits the authorization header entirely when there is no key`() = runBlocking {
        var seen: HttpRequestData? = null
        translator(apiKey = "") { request ->
            seen = request
            ok("""{"translations":[{"id":1,"en":"Hi."}]}""")
        }.use { it.translatePage(regions) }

        assertNull(seen!!.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `configured needs a base url and a model but not a key`() {
        assertTrue(RemoteTranslator("https://api.openai.com/v1", "", "gpt-4o-mini").configured)
        assertFalse(RemoteTranslator("https://api.openai.com/v1", key, "").configured)
        assertFalse(RemoteTranslator("", key, "gpt-4o-mini").configured)
    }

    /**
     * The cache key is built from the translator id, so two models on one host and one
     * model on two hosts both have to be distinguishable.
     */
    @Test
    fun `id distinguishes host and model`() {
        val openai = RemoteTranslator("https://api.openai.com/v1", key, "gpt-4o-mini").id
        val deepseek = RemoteTranslator("https://api.deepseek.com/v1", key, "gpt-4o-mini").id
        val other = RemoteTranslator("https://api.openai.com/v1", key, "gpt-4o").id

        assertFalse(openai == deepseek)
        assertFalse(openai == other)
        assertFalse("the key must never be part of the id", openai.contains(key))
    }

    /**
     * [ChatProtocol] must not change the id format for the wire that predates it -- every
     * OpenAI and DeepSeek configuration a user already has feeds a page translation cache
     * keyed on this string, and a format change silently orphans it.
     */
    @Test
    fun `id is unchanged for an openai configuration`() {
        assertEquals(
            "remote:api.openai.com:gpt-4o-mini",
            RemoteTranslator("https://api.openai.com/v1", key, "gpt-4o-mini").id,
        )
    }

    /**
     * The rule from section 5 that nothing else can enforce: a failure message reaches
     * the UI, so it must be built from the status code and never from an upstream body
     * that may echo the request -- and the request carries the key.
     */
    @Test
    fun `http failures become actionable messages that never contain the key`() = runBlocking {
        val cases = mapOf(
            HttpStatusCode.Unauthorized to "rejected the API key",
            HttpStatusCode.NotFound to "base URL",
            HttpStatusCode.TooManyRequests to "rate-limiting",
            HttpStatusCode.InternalServerError to "server error",
        )

        cases.forEach { (status, expected) ->
            val thrown = runCatching {
                translator { respondError(status, "upstream said: Bearer $key") }
                    .use { it.translatePage(regions) }
            }.exceptionOrNull()

            val message = (thrown as? TranslatorException)?.message.orEmpty()
            assertTrue(status.toString() + ": got " + message, message.contains(expected))
            assertFalse(status.toString() + " leaked the key", message.contains(key))
        }
    }

    @Test
    fun `a page with nothing translatable never calls out`() = runBlocking {
        var called = false
        val blank = listOf(region(1, RegionKind.TEXT_BUBBLE, 0, "   "))
        val result = translator { called = true; ok("""{"translations":[]}""") }
            .use { it.translatePage(blank) }

        assertTrue(result.isEmpty())
        assertFalse("a page OCR read nothing on is not worth a request", called)
    }

    @Test
    fun `a page of pure sound effects does call out`() = runBlocking {
        var called = false
        val sfxOnly = listOf(region(1, RegionKind.TEXT_SFX, 0, "ゴゴゴ"))
        translator { called = true; ok("""{"translations":[{"id":1,"en":"RRRUMBLE"}]}""") }
            .use { it.translatePage(sfxOnly) }

        assertTrue("sound effects are worth translating (section 2.6)", called)
    }

    // -- plumbing -----------------------------------------------------------------

    private fun translator(
        apiKey: String = key,
        handler: MockRequestHandlerScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = RemoteTranslator(
        baseUrl = "https://api.example.test/v1",
        apiKey = apiKey,
        model = "test-model",
        engine = MockEngine { request -> handler(request) },
    )

    private fun MockRequestHandlerScope.ok(json: String) = respond(
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

private typealias MockRequestHandlerScope = io.ktor.client.engine.mock.MockRequestHandleScope
