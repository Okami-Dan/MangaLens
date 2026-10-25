package com.mangareader.translate

import com.mangareader.model.Box
import com.mangareader.model.Region
import com.mangareader.model.RegionKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 7 against the harness, on the JVM.
 *
 * `tools/export_translate_fixture.py` runs `harness/pipeline/translate.py` over the text
 * the *phone* read off the fixture photographs and freezes what it produced. This
 * replays the same regions through the Kotlin port and demands they agree.
 *
 * All of stage 7 except the transports is checkable here, because none of it touches
 * Android: the request shape, the 1..N renumbering, the cover branch, the cache key and
 * the defensive parse are arithmetic and string handling. What is not checkable is
 * whether a model answers well, which no test can assert anyway.
 *
 * The cache key is the reason this test is strict rather than approximate. It is a hash
 * of a JSON rendering, so "equivalent JSON" is not good enough -- a space after a colon
 * decides whether the phone and the harness agree that they have already translated a
 * page.
 */
class TranslateParityTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("translate_fixture.json")
        assertNotNull(
            "translate_fixture.json missing -- run tools/export_translate_fixture.py",
            stream,
        )
        JSONObject(stream!!.bufferedReader().use { it.readText() })
    }

    private val modelId: String by lazy { fixture.getJSONObject("_meta").getString("model_id") }

    private val pages: List<JSONObject> by lazy {
        fixture.getJSONArray("pages").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }
    }

    private val responses: List<JSONObject> by lazy {
        fixture.getJSONArray("responses").let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }
        }
    }

    /** Section 2.6: everything with text, on an interior page and a cover alike. */
    @Test
    fun `translatable picks the same regions in the same order`() {
        pages.forEach { page ->
            val expected = page.getJSONArray("translatableIds").longs()
            val actual = translatable(page.regions()).map { it.id }
            assertEquals(page.name(), expected, actual)
        }
    }

    /**
     * `cover` used to decide who was translated; now it only decides which prompt they
     * are translated with. The two variants of a page must therefore agree exactly.
     */
    @Test
    fun `the cover flag no longer changes who is translated`() {
        val byName = pages.groupBy { it.getString("page") }
        var compared = 0
        byName.values.filter { it.size > 1 }.forEach { variants ->
            val sets = variants.map { page ->
                page.getJSONArray("translatableIds").longs()
            }.distinct()
            assertEquals("cover and interior disagree: " + sets, 1, sets.size)
            compared++
        }
        assertTrue("no page had both variants in the fixture", compared > 0)
    }

    /**
     * The bubbles must come out numbered 1..N in reading order however the region ids
     * happen to run. On the fixture pages they run 9, 8, 6, 3, ... -- which is exactly
     * the non-contiguous case that made a 1.5B model renumber and mis-attach every line.
     */
    @Test
    fun `bubbles are renumbered 1 to N and map back to region ids`() {
        pages.forEach { page ->
            val regions = page.regions()
            val cover = page.getBoolean("cover")
            val expected = page.getJSONObject("request").getJSONArray("bubbles")
            val actual = buildRequest(regions, cover = cover).bubbles

            assertEquals(page.name(), expected.length(), actual.size)
            actual.forEachIndexed { index, bubble ->
                val reference = expected.getJSONObject(index)
                assertEquals(page.name() + " id", reference.getInt("id"), bubble.id)
                assertEquals(page.name() + " text", reference.getString("text"), bubble.text)
                assertEquals(page.name() + " sequential", index + 1, bubble.id)
            }

            val map = indexMap(regions, cover)
            val reference = page.getJSONObject("indexMap")
            assertEquals(page.name() + " map size", reference.length(), map.size)
            reference.keys().forEach { key ->
                assertEquals(
                    page.name() + " map " + key,
                    reference.getLong(key),
                    map[key.toInt()],
                )
            }
        }
    }

    /** The prompt's JSON: insertion order, page context first, no sorted keys. */
    @Test
    fun `request json matches the harness byte for byte`() {
        pages.forEach { page ->
            val request = buildRequest(page.regions(), cover = page.getBoolean("cover"))
            assertEquals(page.name(), page.getString("requestJson"), requestJson(request))
        }
    }

    /** The cache key's JSON: sorted keys, and a space after every separator. */
    @Test
    fun `canonical json matches python json dumps with sorted keys`() {
        pages.forEach { page ->
            val request = buildRequest(page.regions(), cover = page.getBoolean("cover"))
            assertEquals(page.name(), page.getString("canonicalJson"), canonicalJson(request))
        }
    }

    @Test
    fun `cache key matches the harness`() {
        pages.forEach { page ->
            assertEquals(
                page.name(),
                page.getString("cacheKey"),
                cacheKey(page.regions(), modelId, page.getBoolean("cover")),
            )
        }
    }

    @Test fun `series context serialization and cache match the harness`() {
        pages.forEach { page ->
            val context = page.getString("seriesContext")
            val cover = page.getBoolean("cover")
            val request = buildRequest(page.regions(), context, cover)
            assertEquals(page.getString("contextRequestJson"), requestJson(request))
            assertEquals(page.getString("contextCanonicalJson"), canonicalJson(request))
            assertEquals(page.getString("contextCacheKey"), cacheKey(page.regions(), modelId, cover, context))
        }
    }

    /**
     * The key covers bubble texts, effective context, prompt version, cover flag and model.
     */
    @Test
    fun `cache key separates context models and page kinds`() {
        val page = pages.first { !it.getBoolean("cover") }
        val regions = page.regions()
        val base = cacheKey(regions, modelId, cover = false)

        assertEquals(base, cacheKey(regions, modelId, cover = false))
        assertFalse(base == cacheKey(regions, "some-other-model", cover = false))
        assertFalse(base == cacheKey(regions, modelId, cover = true))
        assertFalse(base == cacheKey(regions, modelId, pageContext = "Series: Example"))

        val edited = regions.map {
            if (it.readingIndex == 0) it.copy(ocrTextEdited = "差し替え") else it
        }
        assertFalse("an OCR correction must invalidate the page", base == cacheKey(edited, modelId))
    }

    /**
     * Every raw response the harness was given, parsed to the same result. The malformed
     * ones are observed output from a real 1.5B model, not invented -- see
     * docs/on-device-llm.md section 5.
     */
    @Test
    fun `parse response recovers what the harness recovered`() {
        responses.forEach { case ->
            val name = case.getString("name")
            val ids = case.getJSONArray("expectedIds").ints()
            val parsed = parseResponse(case.getString("raw"), ids)

            val expected = case.getJSONObject("parsed")
            val declined = case.getJSONArray("declined").ints()

            assertEquals(
                name + ": id count",
                expected.length() + declined.size,
                parsed.size,
            )
            expected.keys().forEach { key ->
                assertEquals(name + " id " + key, expected.getString(key), parsed[key.toInt()])
            }
            declined.forEach { id ->
                assertTrue(name + ": id " + id + " must be present", parsed.containsKey(id))
                assertEquals(
                    name + ": id " + id + " must be an explicit decline",
                    null,
                    parsed[id],
                )
            }
            parsed.keys.forEach { id ->
                assertTrue(name + ": id " + id + " was not requested", id in ids)
            }
        }
    }

    /**
     * A skipped bubble is *absent*, not empty. The distinction is the whole reason the
     * result is a sparse map: absent shows the bubble as untranslated with a retry, an
     * empty string would show a translated bubble that says nothing.
     */
    @Test
    fun `a truncated list leaves the rest absent rather than blank`() {
        val parsed = parseResponse(
            """{"translations":[{"id":1,"en":"One."}]}""",
            listOf(1, 2, 3),
        )
        assertEquals(mapOf(1 to "One."), parsed)
        assertFalse(parsed.containsKey(2))
        assertFalse(parsed.containsKey(3))
    }

    @Test
    fun `only blank regions produce an empty request`() {
        val blank = listOf(
            region(1, RegionKind.TEXT_BUBBLE, 0, ""),
            region(2, RegionKind.TEXT_SFX, 1, "   "),
        )
        assertTrue(buildRequest(blank).isEmpty)
        assertTrue(indexMap(blank).isEmpty())
    }

    /**
     * The reported bug, as an assertion. Section 2.6 used to withhold everything that
     * was not `TEXT_BUBBLE`, which is what cost the reader the unboxed monologue.
     */
    @Test
    fun `a page of pure sound effects is still worth a request`() {
        val sfxOnly = listOf(region(1, RegionKind.TEXT_SFX, 0, "ドドドド"))
        assertFalse(buildRequest(sfxOnly).isEmpty)
        assertEquals(1, indexMap(sfxOnly).size)
        // And a cover reaches the same set -- `cover` no longer filters anything.
        assertEquals(
            buildRequest(sfxOnly).bubbles.map { it.text },
            buildRequest(sfxOnly, cover = true).bubbles.map { it.text },
        )
    }

    @Test
    fun `blank regions are dropped before the model sees them`() {
        val regions = listOf(
            region(1, RegionKind.TEXT_BUBBLE, 0, "こんにちは"),
            region(2, RegionKind.TEXT_BUBBLE, 1, "   "),
            region(3, RegionKind.TEXT_BUBBLE, 2, ""),
        )
        assertEquals(listOf(1L), translatable(regions).map { it.id })
    }

    /** Section 6: a user correction wins over what OCR read, here as everywhere. */
    @Test
    fun `an edited region is sent as edited`() {
        val regions = listOf(
            region(1, RegionKind.TEXT_BUBBLE, 0, "誤読").copy(ocrTextEdited = "訂正"),
        )
        assertEquals("訂正", buildRequest(regions).bubbles.single().text)
    }

    @Test
    fun `tier zero is configured false and translates nothing`() = kotlinx.coroutines.runBlocking {
        assertFalse(NoOpTranslator.configured)
        assertEquals("none", NoOpTranslator.id)
        assertTrue(
            NoOpTranslator.translatePage(
                listOf(region(1, RegionKind.TEXT_BUBBLE, 0, "こんにちは")),
            ).isEmpty(),
        )
    }

    // -- fixture plumbing ---------------------------------------------------------

    private fun JSONObject.name(): String =
        getString("page") + (if (getBoolean("cover")) " [as cover]" else "")

    private fun JSONObject.regions(): List<Region> {
        val array = getJSONArray("regions")
        return (0 until array.length()).map { index ->
            val raw = array.getJSONObject(index)
            Region(
                id = raw.getLong("id"),
                kind = RegionKind.valueOf(raw.getString("kind")),
                box = Box(0f, 0f, 0f, 0f),
                score = 0f,
                readingIndex = raw.getInt("readingIndex"),
                ocrText = raw.getString("ocrText"),
                ocrTextEdited = if (raw.isNull("ocrTextEdited")) {
                    null
                } else {
                    raw.getString("ocrTextEdited")
                },
            )
        }
    }

    private fun org.json.JSONArray.ints(): List<Int> = (0 until length()).map { getInt(it) }

    private fun org.json.JSONArray.longs(): List<Long> = (0 until length()).map { getLong(it) }

    private fun region(id: Long, kind: RegionKind, readingIndex: Int, text: String) = Region(
        id = id,
        kind = kind,
        box = Box(0f, 0f, 0f, 0f),
        score = 1f,
        readingIndex = readingIndex,
        ocrText = text,
    )
}
