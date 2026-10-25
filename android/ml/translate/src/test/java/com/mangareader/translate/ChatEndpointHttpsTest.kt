package com.mangareader.translate

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class ChatEndpointHttpsTest {
    @Test
    fun `remote https preserves port and path and appends completion endpoint once`() = runBlocking {
        for (base in listOf("https://manga.example/v1", "https://manga.example:8443/proxy/v1/",
            " https://manga.example:8443/proxy/v1/// ")) {
            for (key in listOf("", "test-secret")) {
                val expected = base.trim().trimEnd('/') + "/chat/completions"
                val engine = MockEngine { request ->
                    assertEquals(expected, request.url.toString())
                    assertEquals(key.takeIf { it.isNotEmpty() }?.let { "Bearer $it" },
                        request.headers[HttpHeaders.Authorization])
                    respond("""{"choices":[{"message":{"content":"translated"}}]}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
                ChatEndpoint(base, key, "model", engine = engine).use {
                    assertEquals("translated", it.complete("system", "user", 128))
                }
            }
        }
    }

    @Test
    fun `tls failures are actionable and do not expose underlying secrets`() = runBlocking {
        val secret = "test-secret"
        val cases = listOf(
            SSLHandshakeException("untrusted certificate $secret") to "TLS certificate",
            SSLPeerUnverifiedException("hostname mismatch $secret") to "matches the hostname",
            IOException("request body $secret") to "Check the base URL and the network",
        )
        for ((failure, expected) in cases) {
            val engine = MockEngine { throw failure }
            val thrown = runCatching {
                ChatEndpoint("https://manga.example/v1", secret, "model", engine = engine).use {
                    it.complete("system", "user", 128)
                }
            }.exceptionOrNull()
            assertTrue(thrown is TranslatorException)
            val message = thrown!!.message.orEmpty()
            assertTrue(message, message.contains(expected))
            assertTrue(message.contains("manga.example"))
            assertFalse(message.contains(secret))
        }
    }
}
