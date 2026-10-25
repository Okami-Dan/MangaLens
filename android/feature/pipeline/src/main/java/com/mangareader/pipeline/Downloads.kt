package com.mangareader.pipeline

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Fetching a large file onto the phone and being sure of what arrived.
 *
 * Extracted from [ModelStore] when [LlmStore] needed the same thing for a gigabyte of
 * GGUF. Both stores download something too big to re-fetch casually, from a host that
 * redirects, onto a device that can lose the connection halfway -- so both write through
 * a `.part` file and verify SHA-256 before anything is allowed to look like a model.
 */
internal object Downloads {

    /**
     * Streams [url] into [into], following redirects by hand and hashing as it goes.
     *
     * @return the hex SHA-256 of what was actually written, for the caller to check
     */
    suspend fun download(url: String, into: File, report: (Long) -> Unit): String {
        var address = URL(url)
        var redirects = 0
        while (true) {
            val connection = (address.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            // Hugging Face answers /resolve/ with a redirect to a signed CDN URL, and
            // HttpURLConnection will not follow one that changes protocol. Following by
            // hand rather than depending on which scheme the CDN happens to answer on.
            val code = connection.responseCode
            if (code in 300..399 && redirects++ < 5) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                address = URL(address, location ?: error("redirect with no Location"))
                continue
            }
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                error("HTTP " + code + " fetching " + url)
            }
            return connection.inputStream.use { copy(it, into, report) }
        }
    }

    /**
     * HEADs [url] once, without following a redirect, and returns the response headers,
     * lowercased and first-value-only.
     *
     * Hugging Face's `/resolve/main/...` URLs answer a HEAD with a 302 whose own headers
     * already carry `x-linked-etag` and `x-linked-size` for the file the redirect points
     * to -- verified by hand while writing [LlmStore.resolve]. So there is no need to
     * follow the redirect chain [download] does, just to read two headers off it.
     */
    suspend fun head(url: String): Map<String, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        return try {
            connection.responseCode // forces the request to actually happen
            connection.headerFields.entries
                .mapNotNull { (key, values) -> key?.let { it.lowercase() to values.firstOrNull().orEmpty() } }
                .toMap()
        } finally {
            connection.disconnect()
        }
    }

    /** Streams [source] into [into], hashing as it goes. Returns the hex SHA-256. */
    suspend fun copy(source: InputStream, into: File, report: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        var reported = 0L
        DigestInputStream(source.buffered(1 shl 16), digest).use { input ->
            into.outputStream().buffered(1 shl 16).use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                    // Reporting every 64 KB would recompose the progress bar thousands
                    // of times for one file; 2 MB is smooth enough at these sizes.
                    if (copied - reported >= 2L shl 20) {
                        reported = copied
                        report(copied)
                    }
                }
            }
        }
        report(copied)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
