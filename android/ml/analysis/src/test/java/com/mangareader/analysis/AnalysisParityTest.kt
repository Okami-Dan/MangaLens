package com.mangareader.analysis

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage 6 against the harness, on the JVM.
 *
 * `tools/export_analysis_fixture.py` runs `harness/pipeline/analyse.py` over the text
 * the *phone* read off the fixture photographs and freezes the result. This replays the
 * same strings through the Kotlin port and demands they agree.
 *
 * What is checkable here is everything that does not need a device: Kuromoji is pure
 * Java, so segmentation, readings, base forms, furigana, romaji and part-of-speech
 * labels are all covered, as is the SFX glossary. The JMdict glosses are not -- they
 * need `android.database.sqlite` -- and are checked by
 * `app/src/androidTest/.../AnalysisFixtureTest.kt` instead. This test asserts the
 * *entry ids* are absent rather than wrong, so a null dictionary here cannot be
 * mistaken for agreement.
 *
 * The tokenizers differ by name -- janome there, Kuromoji here -- and are the same
 * IPADIC underneath, which is exactly the claim being tested.
 */
class AnalysisParityTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("analysis_fixture.json")
        assertNotNull(
            "analysis_fixture.json missing -- run tools/export_analysis_fixture.py",
            stream,
        )
        JSONObject(stream!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private val analyser: Analyser by lazy {
        // No dictionary: this test is about the tokenizer and the derived display
        // fields. Analyser is built to run without one, and that is also what a fresh
        // install looks like before the asset finishes unpacking.
        Analyser(dictionary = null, sfx = sfxDictionary())
    }

    private fun sfxDictionary(): SfxDictionary {
        val file = File("../../../tools/sfx_dict.json").let {
            if (it.isFile) it else File("../../tools/sfx_dict.json")
        }
        assertTrue("tools/sfx_dict.json not found from " + File(".").absolutePath, file.isFile)
        return SfxDictionary.parse(file.readText(Charsets.UTF_8))
    }

    @Test
    fun `segmentation and derived fields match the harness`() {
        val regions = fixture.getJSONArray("regions")
        var checked = 0
        var tokens = 0
        val failures = mutableListOf<String>()

        for (index in 0 until regions.length()) {
            val region = regions.getJSONObject(index)
            if (region.getString("route") != "tokens") continue
            checked++
            tokens += compareTokens(
                region.getString("text"),
                region.getJSONArray("tokens"),
                failures,
            )
        }

        assertTrue(
            "" + failures.size + " mismatches over " + tokens + " tokens:\n" +
                failures.take(25).joinToString("\n"),
            failures.isEmpty(),
        )
        assertTrue("no dialogue regions in the fixture", checked > 0)
        println("[parity] $checked regions, $tokens tokens identical to the harness")
    }

    /**
     * Words IPADIC does not carry, which the fixture photographs barely contain.
     *
     * IPADIC answers `*` when it is asked for the reading of a word it has never seen,
     * and stage 6 used to pass that straight through as "no reading" -- so an unknown
     * katakana run reached the sheet with no reading row, no romaji and no furigana,
     * which is the reported bug. [Kana.kanaReading] recovers the reading from the
     * surface when the surface is kana, and refuses to guess when it is not; the
     * harness pins both halves, including the kanji case that must stay empty.
     */
    @Test
    fun `unknown words keep the reading their kana already spell`() {
        val probes = fixture.getJSONArray("reading_probes")
        val failures = mutableListOf<String>()
        var tokens = 0
        for (index in 0 until probes.length()) {
            val probe = probes.getJSONObject(index)
            tokens += compareTokens(
                probe.getString("text"),
                probe.getJSONArray("tokens"),
                failures,
            )
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("no reading probes in the fixture", probes.length() > 0)
        println("[parity] ${probes.length()} reading probes, $tokens tokens identical")
    }

    /**
     * Diffs one string's tokens against the harness's, appending anything that differs.
     * Returns the number of tokens compared.
     */
    private fun compareTokens(
        text: String,
        expected: JSONArray,
        failures: MutableList<String>,
    ): Int {
        val actual = analyser.tokenize(text)
        if (expected.length() != actual.size) {
            failures += "$text: ${expected.length()} tokens expected, ${actual.size} produced" +
                "\n  expected " + (0 until expected.length())
                    .joinToString("|") { expected.getJSONObject(it).getString("surface") } +
                "\n  actual   " + actual.joinToString("|") { it.surface }
            return 0
        }
        for (position in 0 until expected.length()) {
            val want = expected.getJSONObject(position)
            val got = actual[position]
            fun check(field: String, wanted: Any?, produced: Any?) {
                if (wanted != produced) {
                    failures += "$text [$position] $field: expected <$wanted>, got <$produced>"
                }
            }
            check("surface", want.getString("surface"), got.surface)
            check("reading", want.getString("reading"), got.reading)
            check("baseForm", want.getString("baseForm"), got.baseForm)
            check("partOfSpeech", want.getString("partOfSpeech"), got.partOfSpeech)
            check("posLabel", want.getString("posLabel"), got.posLabel)
            check("romaji", want.getString("romaji"), got.romaji)
            check("isContentWord", want.getBoolean("isContentWord"), got.isContentWord)
            check("furigana", furigana(want.getJSONArray("furigana")), furigana(got.furigana))
            // Without a dictionary there is no entry id and no base reading beyond the
            // surface reading; anything else means the null path is lying.
            check("jmdictEntryId", null, got.jmdictEntryId)
            check("glosses", 0, got.glosses.size)
            check("baseReading", got.reading, got.baseReading)
        }
        return expected.length()
    }

    @Test
    fun `sfx lookups match the harness`() {
        val failures = mutableListOf<String>()
        var checked = 0

        fun compare(text: String, expected: JSONObject?) {
            checked++
            val got = analyser.analyseSfx(text)
            if (expected == null) {
                if (got != null) failures += "$text: expected no match, got ${got.text}"
                return
            }
            if (got == null) {
                failures += "$text: expected ${expected.getString("text")}, got no match"
                return
            }
            listOf(
                "text" to got.text, "reading" to got.reading, "kind" to got.kind,
                "gloss" to got.gloss, "match" to got.match,
            ).forEach { (field, produced) ->
                val wanted = expected.getString(field)
                if (wanted != produced) {
                    failures += "$text $field: expected <$wanted>, got <$produced>"
                }
            }
        }

        val regions = fixture.getJSONArray("regions")
        for (index in 0 until regions.length()) {
            val region = regions.getJSONObject(index)
            if (region.getString("route") != "sfx") continue
            compare(region.getString("text"), region.optJSONObject("sfx"))
        }
        val probes = fixture.getJSONArray("sfx_probes")
        for (index in 0 until probes.length()) {
            val probe = probes.getJSONObject(index)
            compare(probe.getString("text"), probe.optJSONObject("sfx"))
        }

        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue("no SFX cases in the fixture", checked > 0)
        println("[parity] $checked SFX lookups identical to the harness")
    }

    /**
     * The kana fold, which everything above is downstream of. These are the exact
     * edges `jaconv` has and a naive `char - 0x60` does not.
     */
    @Test
    fun `kana normalisation matches jaconv`() {
        val cases = listOf(
            "ア" to "あ", "ン" to "ん", "ヴ" to "ゔ", "ヵ" to "ゕ", "ヶ" to "ゖ",
            "ヽ" to "ゝ", "ヾ" to "ゞ",
            // No hiragana form; jaconv leaves these alone and so does the port.
            "ヷ" to "ヷ", "ヸ" to "ヸ", "ヹ" to "ヹ", "ヺ" to "ヺ",
            "ー" to "ー", "・" to "・", "あ" to "あ", "A" to "A", "1" to "1",
            // Half-width, including the two voicing marks that compose and the two
            // that do not.
            "ｱｲｳ" to "あいう", "ｶﾞ" to "が", "ﾊﾟ" to "ぱ", "ｳﾞ" to "ゔ",
            "ｰ" to "ー", "｡" to "。", "｢" to "「",
            "ﾄﾞｷﾄﾞｷ" to "どきどき",
        )
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, Kana.toHiragana(input))
        }
    }

    /** Section 6.2's okurigana handling, and its documented failure mode. */
    @Test
    fun `furigana alignment covers okurigana and gives up visibly`() {
        assertEquals(listOf(ruby("見", "み"), ruby("る", "")), Furigana.align("見る", "みる"))
        assertEquals(listOf(ruby("食", "た"), ruby("べる", "")), Furigana.align("食べる", "たべる"))
        assertEquals(
            listOf(ruby("お", ""), ruby("茶", "ちゃ")),
            Furigana.align("お茶", "おちゃ"),
        )
        assertEquals(listOf(ruby("東京", "とうきょう")), Furigana.align("東京", "とうきょう"))
        // Kanji runs separated by kana: each separator is located in the remaining
        // reading in order.
        assertEquals(
            listOf(ruby("取", "と"), ruby("り", ""), ruby("引", "ひ"), ruby("き", "")),
            Furigana.align("取り引き", "とりひき"),
        )
        // 々 counts as kanji, so 人々 takes one ruby over the pair.
        assertEquals(listOf(ruby("人々", "ひとびと")), Furigana.align("人々", "ひとびと"))
        // All kana, or no reading: one segment, no ruby.
        assertEquals(listOf(ruby("ひらがな", "")), Furigana.align("ひらがな", "ひらがな"))
        assertEquals(listOf(ruby("見る", "")), Furigana.align("見る", ""))
        // Alignment is positional, not semantic: a wrong reading that still ends in
        // the surface's okurigana aligns, and shows a wrong ruby rather than none.
        assertEquals(listOf(ruby("見", "たべ"), ruby("る", "")), Furigana.align("見る", "たべる"))
        // Section 6.2: when it genuinely cannot line up -- here the trailing る is
        // nowhere in the reading -- the whole surface takes the whole reading.
        // Wrong-but-visible beats silently dropping the aid.
        assertEquals(listOf(ruby("見る", "たべ")), Furigana.align("見る", "たべ"))
    }

    /** Section 6.4's Hepburn, including the three rules that are easy to get wrong. */
    @Test
    fun `romaji follows the harness`() {
        assertEquals("kya", Romaji.of("きゃ"))
        assertEquals("kitte", Romaji.of("きって"))
        assertEquals("matcha", Romaji.of("まっちゃ"))
        assertEquals("shin'ya", Romaji.of("しんや"))
        assertEquals("sanpo", Romaji.of("さんぽ"))
        // Kana-written long vowels stay as written; only the katakana long mark
        // becomes a macron.
        assertEquals("toukyou", Romaji.of("とうきょう"))
        assertEquals("rāmen", Romaji.of("ラーメン"))
        // The small-vowel combinations katakana spells foreign sounds with. Every one
        // of these came apart into two syllables while the table's only ゔ row was
        // keyed on katakana, which the fold means it never sees.
        assertEquals("varukyuria", Romaji.of("ヴァルキュリア"))
        assertEquals("chekku", Romaji.of("チェック"))
        assertEquals("wōtā", Romaji.of("ウォーター"))
        assertEquals("fyūjon", Romaji.of("フュージョン"))
        // A word IPADIC does not know spells its own reading, and now keeps it.
        assertEquals("tsugai", Romaji.of(Kana.kanaReading("ツガイ")))
        assertEquals("", Kana.kanaReading("逞"))
    }

    @Test
    fun `a null dictionary costs meanings and nothing else`() {
        val tokens = analyser.tokenize("見る人によって")
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.all { it.glosses.isEmpty() })
        assertNull(tokens.first().jmdictEntryId)
        assertEquals("みる", tokens.first().reading)
        assertEquals(listOf(ruby("見", "み"), ruby("る", "")), tokens.first().furigana)
    }

    private fun ruby(text: String, ruby: String) = com.mangareader.model.Ruby(text, ruby)

    private fun furigana(segments: JSONArray): String = (0 until segments.length())
        .joinToString("|") {
            segments.getJSONObject(it).getString("text") + ":" +
                segments.getJSONObject(it).getString("ruby")
        }

    private fun furigana(segments: List<com.mangareader.model.Ruby>): String =
        segments.joinToString("|") { it.text + ":" + it.ruby }
}
