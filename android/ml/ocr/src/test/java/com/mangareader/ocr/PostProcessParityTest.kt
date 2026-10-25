package com.mangareader.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks [OcrText.postProcess] against vectors generated from
 * `harness/pipeline/ocr.py::post_process`.
 *
 * Runs on the JVM. Every manga-ocr result passes through this, so a divergence would
 * show up as a CER difference against `var/run_final` that has nothing to do with the
 * model -- worth pinning exactly rather than eyeballing.
 */
class PostProcessParityTest {

    private data class Vector(val input: String, val expected: String)

    /**
     * The fixture stores both columns JSON-encoded, so tabs, newlines and the
     * empty-string case survive the round trip.
     */
    private fun vectors(): List<Vector> {
        val stream = checkNotNull(
            javaClass.getResourceAsStream("/post_process_vectors.tsv"),
        ) { "post_process_vectors.tsv missing" }
        return stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (input, expected) = line.split("\t")
                Vector(decode(input), decode(expected))
            }
    }

    private fun decode(json: String): String {
        val body = json.removeSurrounding("\"")
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\') {
                out.append(c); i++; continue
            }
            when (val esc = body[i + 1]) {
                'n' -> { out.append('\n'); i += 2 }
                't' -> { out.append('\t'); i += 2 }
                'r' -> { out.append('\r'); i += 2 }
                '"' -> { out.append('"'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                'u' -> {
                    out.append(body.substring(i + 2, i + 6).toInt(16).toChar()); i += 6
                }
                else -> { out.append(esc); i += 2 }
            }
        }
        return out.toString()
    }

    @Test
    fun matchesHarness() {
        val vectors = vectors()
        assertTrue("no vectors loaded", vectors.size >= 30)
        for (vector in vectors) {
            assertEquals(
                "postProcess(${vector.input.map { it.code.toString(16) }})",
                vector.expected,
                OcrText.postProcess(vector.input),
            )
        }
    }

    @Test
    fun isIdempotent() {
        // Full-width output must survive a second pass unchanged; if it did not, a
        // re-analysed region (section 3.3's OCR edit) would drift on every edit.
        for (vector in vectors()) {
            assertEquals(vector.expected, OcrText.postProcess(vector.expected))
        }
    }
}
