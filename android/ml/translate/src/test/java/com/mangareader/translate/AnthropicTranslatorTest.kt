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
 * The Anthropic wire, mirroring [RemoteTranslatorTest] one wire format over. Every
 * dimension [ChatWire] has differs from the OpenAI path -- path, headers, body shape and
 * response shape -- so each gets its own assertion rather than reusing the OpenAI helper.
 */
class AnthropicTranslatorTest {

    private val key = "unit-test-anthropic-key-do-not-leak"

    private val regions = listOf(
        region(7, RegionKind.TEXT_BUBBLE, readingIndex = 0, text = "そっちから煙見える？"),
        region(3, RegionKind.TEXT_BUBBLE, readingIndex = 1, text = "合流地点変えていい？"),
    )

    @Test
    fun `sends a top-level system string, one user message, max_tokens, and no temperature`() =
        runBlocking {
            var seen: HttpRequestData? = null
            val translator = translator { request ->
                seen = request
                ok("""{"translations":[{"id":1,"en":"Can you see the smoke?"},{"id":2,"en":"OK?"}]}""")
            }

            translator.use { it.translatePage(regions, pageContext = "Volume 3, night scene") }

            assertTrue(seen!!.url.encodedPath.endsWith("/messages"))

            val body = seen!!.bodyText()
            assertTrue("system prompt is a top-level field", body.contains("\"system\":"))
            assertTrue("the system prompt content must be sent", body.contains("manga reading order"))
            assertTrue("the page context must still be sent", body.contains("Volume 3, night scene"))
            assertTrue("bubbles are still numbered from 1", body.contains("""\"id\": 1"""))
            assertTrue("max_tokens is required on this wire", body.contains("\"max_tokens\""))
            assertFalse(
                "current Claude models 400 on sampling params -- never send temperature",
                body.contains("temperature"),
            )
            // Exactly one entry in "messages", and it is the user turn -- no separate
            // system-role message the way the OpenAI wire sends one.
            assertTrue(body.contains("\"role\":\"user\""))
            assertFalse(body.contains("\"role\":\"system\""))
        }

    @Test
    fun `sends x-api-key and the version header, not a bearer token`() = runBlocking {
        var seen: HttpRequestData? = null
        translator { request ->
            seen = request
            ok("""{"translations":[{"id":1,"en":"Hi."}]}""")
        }.use { it.translatePage(regions) }

        assertEquals(key, seen!!.headers["x-api-key"])
        assertEquals("2023-06-01", seen!!.headers["anthropic-version"])
        assertNull("Anthropic does not use bearer auth", seen!!.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `omits x-api-key entirely when there is no key`() = runBlocking {
        var seen: HttpRequestData? = null
        translator(apiKey = "") { request ->
            seen = request
            ok("""{"translations":[{"id":1,"en":"Hi."}]}""")
        }.use { it.translatePage(regions) }

        assertNull(seen!!.headers["x-api-key"])
        // The version header is not a credential -- it is sent regardless.
        assertEquals("2023-06-01", seen!!.headers["anthropic-version"])
    }

    /** The response `content` is a block array; only `type == "text"` blocks are text. */
    @Test
    fun `concatenates text blocks and ignores non-text blocks`() = runBlocking {
        // The two text blocks concatenate to a valid translations JSON; a naive
        // `content[0].text` read would see only the thinking block's absence of text
        // and fail, or would see only the first half of the split JSON.
        val firstHalf = quoted("""{"translations":[{"id":1,""")
        val secondHalf = quoted(""""en":"Can you see the smoke?"}]}""")
        val translator = translator {
            respond(
                content = ByteReadChannel(
                    """{"content":[""" +
                        """{"type":"thinking","thinking":"let me consider..."},""" +
                        """{"type":"text","text":$firstHalf},""" +
                        """{"type":"text","text":$secondHalf}""" +
                        """],"stop_reason":"end_turn"}""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val result = translator.use { it.translatePage(regions) }
        assertEquals("Can you see the smoke?", result[7L])
    }

    @Test
    fun `http failures become actionable messages that never contain the key, and name messages not chat_completions`() =
        runBlocking {
            val cases = mapOf(
                HttpStatusCode.Unauthorized to "rejected the API key",
                HttpStatusCode.NotFound to "base URL",
                HttpStatusCode.TooManyRequests to "rate-limiting",
                HttpStatusCode.InternalServerError to "server error",
            )

            cases.forEach { (status, expected) ->
                val thrown = runCatching {
                    translator {
                        respondError(
                            status,
                            """{"type":"error","error":{"type":"authentication_error",""" +
                                """"message":"invalid x-api-key $key"}}""",
                        )
                    }.use { it.translatePage(regions) }
                }.exceptionOrNull()

                val message = (thrown as? TranslatorException)?.message.orEmpty()
                assertTrue(status.toString() + ": got " + message, message.contains(expected))
                assertFalse(status.toString() + " leaked the key", message.contains(key))

                if (status == HttpStatusCode.NotFound) {
                    assertTrue("404 must name this wire's path", message.contains("/messages"))
                    assertFalse(message.contains("/chat/completions"))
                }
            }
        }

    @Test
    fun `refusal and max_tokens stop reasons get their own sentences, never the key`() = runBlocking {
        val refusal = runCatching {
            translator { ok200("""{"content":[],"stop_reason":"refusal"}""") }
                .use { it.translatePage(regions) }
        }.exceptionOrNull() as? TranslatorException
        assertTrue(refusal?.message.orEmpty().contains("declined"))

        val truncated = runCatching {
            translator { ok200("""{"content":[],"stop_reason":"max_tokens"}""") }
                .use { it.translatePage(regions) }
        }.exceptionOrNull() as? TranslatorException
        assertTrue(truncated?.message.orEmpty().contains("ran out of room"))
    }

    @Test
    fun `id carries the anthropic segment and never the key`() {
        val id = RemoteTranslator(
            "https://api.anthropic.com/v1",
            key,
            "claude-opus-5",
            ChatProtocol.ANTHROPIC,
        ).id

        assertEquals("remote:anthropic:api.anthropic.com:claude-opus-5", id)
        assertFalse(id.contains(key))
    }

    // -- plumbing -----------------------------------------------------------------

    private fun translator(
        apiKey: String = key,
        handler: io.ktor.client.engine.mock.MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = RemoteTranslator(
        baseUrl = "https://api.anthropic.com/v1",
        apiKey = apiKey,
        model = "claude-opus-5",
        protocol = ChatProtocol.ANTHROPIC,
        engine = MockEngine { request -> handler(request) },
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.ok(json: String) = ok200(
        """{"content":[{"type":"text","text":${quoted(json)}}],"stop_reason":"end_turn"}""",
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.ok200(json: String) = respond(
        content = ByteReadChannel(json),
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
