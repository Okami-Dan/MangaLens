package com.mangareader.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays a harness run's detected boxes through [Order] and checks that the Kotlin
 * port lands on the same reading order as `harness/pipeline/order.py`.
 *
 * Runs on the JVM -- no device, no emulator. Stage 4 is the one stage in the pipeline
 * that is pure geometry over plain data, so it is the one that can be held to exact
 * parity for free. Regenerate the fixture with `tools/export_order_fixture.py`.
 */
class OrderParityTest {

    private data class Row(
        val page: String,
        val kind: String,
        val id: Long,
        val box: Box,
        val readingIndex: Int,
        val panelIndex: Int,
    )

    private fun fixture(): List<Row> {
        val stream = checkNotNull(javaClass.getResourceAsStream("/order_fixture.tsv")) {
            "order_fixture.tsv missing -- run tools/export_order_fixture.py"
        }
        return stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val f = line.split("\t")
                Row(
                    page = f[0],
                    kind = f[1],
                    id = f[2].toLong(),
                    box = Box(f[3].toFloat(), f[4].toFloat(), f[5].toFloat(), f[6].toFloat()),
                    readingIndex = f[7].toInt(),
                    panelIndex = f[8].toInt(),
                )
            }
    }

    /** Rows are stored in stage 3 detection order, which is what stage 4 consumes. */
    private fun regionsOf(rows: List<Row>) = rows.map {
        Region(id = it.id, kind = RegionKind.TEXT_BUBBLE, box = it.box, score = 1f)
    }

    @Test
    fun readingOrderMatchesHarness() {
        val pages = fixture().groupBy { it.page }
        assertTrue("no fixture pages", pages.isNotEmpty())

        var checked = 0
        for ((page, rows) in pages) {
            val cover = rows.first().kind == "COVER"
            val ordered = if (cover) {
                Order.assignCoverOrder(regionsOf(rows))
            } else {
                Order.assignReadingOrder(regionsOf(rows))
            }

            assertEquals("$page: region count", rows.size, ordered.size)
            val byId = ordered.associateBy { it.id }
            for (row in rows) {
                val actual = checkNotNull(byId[row.id]) { "$page: region ${row.id} lost" }
                assertEquals(
                    "$page region ${row.id} readingIndex",
                    row.readingIndex,
                    actual.readingIndex,
                )
                if (!cover) {
                    assertEquals(
                        "$page region ${row.id} panelIndex",
                        row.panelIndex,
                        actual.panelIndex,
                    )
                }
                checked++
            }
        }
        assertEquals("every fixture region checked", fixture().size, checked)
    }

    @Test
    fun readingOrderIsAPermutation() {
        val pages = fixture().groupBy { it.page }
        for ((page, rows) in pages) {
            val ordered = Order.assignReadingOrder(regionsOf(rows))
            assertEquals(
                "$page: reading indices must be 0 until n",
                (rows.indices).toList(),
                ordered.map { it.readingIndex }.sorted(),
            )
        }
    }

    @Test
    fun emptyPageIsHandled() {
        assertEquals(emptyList<Region>(), Order.assignReadingOrder(emptyList()))
    }

    @Test
    fun manualReorderPutsNamedRegionsFirst() {
        val rows = fixture().first().let { fixture().filter { r -> r.page == it.page } }
        val regions = regionsOf(rows)
        val wanted = regions.takeLast(2).map { it.id }

        val reordered = Order.reorder(regions, wanted)

        assertEquals(wanted, reordered.take(2).map { it.id })
        assertEquals(regions.size, reordered.size)
        assertEquals(
            "indices stay contiguous",
            regions.indices.toList(),
            reordered.map { it.readingIndex },
        )
    }
}
