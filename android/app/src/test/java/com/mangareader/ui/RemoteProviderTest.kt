package com.mangareader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteProviderTest {
    @Test
    fun `self hosted presets require an address and do not require a key`() {
        listOf(RemoteProvider.OLLAMA, RemoteProvider.VLLM).forEach {
            assertEquals("", it.baseUrl)
            assertEquals("", it.model)
            assertFalse(it.needsKey)
        }
    }

    @Test
    fun `blank and remote self hosted addresses infer custom`() {
        listOf("", "   ", "https://manga.example:8443/proxy/v1/").forEach {
            assertEquals(RemoteProvider.CUSTOM, RemoteProvider.of(it))
        }
    }

    @Test
    fun `legacy localhost addresses still infer their original provider`() {
        assertEquals(RemoteProvider.OLLAMA, RemoteProvider.of(" http://localhost:11434/v1/ "))
        assertEquals(RemoteProvider.VLLM, RemoteProvider.of("http://localhost:8000/v1"))
    }

    @Test
    fun `commercial provider inference is preserved`() {
        listOf(RemoteProvider.OPENAI, RemoteProvider.ANTHROPIC, RemoteProvider.DEEPSEEK).forEach {
            assertEquals(it, RemoteProvider.of(" ${it.baseUrl}/ "))
        }
    }
}
