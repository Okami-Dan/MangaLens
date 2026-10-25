package com.mangareader.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Checks [PilResize] against output captured from Pillow itself.
 *
 * Pillow's 8-bit path accumulates in fixed point and this one accumulates in double, so
 * exact equality is not the bar -- but the *filter* has to match, which is the thing
 * that actually changes OCR output. A plain bilinear resize would be off by far more
 * than a grey level on the downscale cases.
 */
class PilResizeParityTest {

    private data class Case(
        val name: String,
        val inWidth: Int,
        val inHeight: Int,
        val outWidth: Int,
        val outHeight: Int,
        val filter: PilResize.Filter,
        val input: ByteArray,
        val expected: ByteArray,
    )

    private fun hex(s: String) = ByteArray(s.length / 2) {
        ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
    }

    private fun cases(): List<Case> {
        val stream = checkNotNull(
            javaClass.getResourceAsStream("/pil_resize_vectors.txt"),
        ) { "pil_resize_vectors.txt missing" }
        return stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.split("\t")
                Case(
                    f[0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(),
                    PilResize.Filter.valueOf(f[5]), hex(f[6]), hex(f[7]),
                )
            }
    }

    @Test
    fun matchesPillow() {
        val cases = cases()
        assertTrue("no vectors loaded", cases.size >= 6)
        assertTrue(
            "both filters must be covered",
            cases.map { it.filter }.toSet() == PilResize.Filter.entries.toSet(),
        )

        for (case in cases) {
            val actual = PilResize.resize(
                case.input, case.inWidth, case.inHeight, case.outWidth, case.outHeight,
                channels = 3, filter = case.filter,
            )
            assertEquals("${case.name}: byte count", case.expected.size, actual.size)

            var worst = 0
            var offByOne = 0
            for (i in actual.indices) {
                val delta = abs(
                    (actual[i].toInt() and 0xFF) - (case.expected[i].toInt() and 0xFF),
                )
                if (delta > worst) worst = delta
                if (delta > 0) offByOne++
            }
            assertTrue(
                "${case.name}: max |delta| was $worst (want <= 1); " +
                    "$offByOne/${actual.size} samples differ",
                worst <= 1,
            )
        }
    }

    @Test
    fun aFlatImageStaysFlat() {
        // Guards the weight normalisation: if the kernel did not sum to 1, a constant
        // image would drift, and the drift would be invisible on random data.
        val input = ByteArray(37 * 53 * 3) { 200.toByte() }
        val out = PilResize.resize(input, 37, 53, 224, 224)
        assertTrue(
            "flat input must stay flat",
            out.all { (it.toInt() and 0xFF) == 200 },
        )
    }
}
