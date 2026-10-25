package com.mangareader.analysis

import com.mangareader.model.PageKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage 6's onomatopoeia test, against the harness.
 *
 * The coverage that did not exist before this change: nothing tested classification at
 * all, because classification was not a decision -- it was the detector's class head
 * read back out. `android/README.md`'s "Not verified" list flagged the gap, and the
 * fixture photographs cannot close it on their own, because between them they contain
 * no real onomatopoeia and two pieces of ordinary dialogue that were frozen in *as*
 * onomatopoeia.
 *
 * So the truth table is hand-written, lives in `tools/export_analysis_fixture.py`, and
 * is replayed here against the harness's own verdicts rather than a second copy of the
 * expectations -- two hand-written tables would drift, and the one thing this project
 * cannot afford is the phone and the desktop reference disagreeing about what a sound
 * effect is.
 */
class SfxClassifierTest {

    private val fixture: JSONObject by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("analysis_fixture.json")
        assertNotNull(
            "analysis_fixture.json missing -- run tools/export_analysis_fixture.py",
            stream,
        )
        JSONObject(stream!!.use { it.readBytes().toString(Charsets.UTF_8) })
    }

    private val dictionary: SfxDictionary by lazy { sfxDictionary() }

    private val analyser: Analyser by lazy { Analyser(dictionary = null, sfx = dictionary) }

    private fun sfxDictionary(): SfxDictionary {
        val file = File("../../../tools/sfx_dict.json").let {
            if (it.isFile) it else File("../../tools/sfx_dict.json")
        }
        assertTrue("tools/sfx_dict.json not found from " + File(".").absolutePath, file.isFile)
        return SfxDictionary.parse(file.readText(Charsets.UTF_8))
    }

    private fun verdict(text: String, inBubble: Boolean, pageKind: PageKind): Verdict =
        analyser.classify(text, analyser.tokenize(text), inBubble, pageKind)

    @Test
    fun `classification matches the harness`() {
        val cases = fixture.getJSONArray("classification")
        assertTrue("no classification probes in the fixture", cases.length() > 0)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val text = case.getString("text")
            val inBubble = case.getBoolean("inBubble")
            val pageKind = PageKind.valueOf(case.getString("pageKind"))
            val got = verdict(text, inBubble, pageKind)

            assertEquals(
                "$text (inBubble=$inBubble, $pageKind) -- Kotlin said ${got.reasons}",
                case.getBoolean("isSfx"),
                got.isSfx,
            )
            // A veto exports as null; anything else must agree to the point.
            if (!case.isNull("score")) {
                assertEquals(
                    "$text scored differently: ${got.reasons}",
                    case.getDouble("score").toFloat(),
                    got.score,
                    0.001f,
                )
            }
        }
        println("classification: " + cases.length() + " probes agree with the harness")
    }

    /**
     * The bug this change exists to fix, named so a regression is unmistakable.
     *
     * These are the four unboxed monologue lines from the Beastars page in the report.
     * Every one of them is drawn on the artwork with no bubble, so the old geometric
     * rule called all four sound effects and withheld all four from the translator.
     */
    @Test
    fun `unboxed monologue is dialogue, not a sound effect`() {
        val lines = listOf("勝たなきゃ", "勝たなきゃダメだ", "僕はダメ犬じゃない", "僕はやってないよお父さん")
        for (line in lines) {
            val got = verdict(line, inBubble = false, pageKind = PageKind.INTERIOR)
            assertEquals("$line was read as a sound effect: ${got.reasons}", false, got.isSfx)
        }
    }

    /** Both were frozen into the analysis fixture as SFX with no tokens at all. */
    @Test
    fun `the fixture's own false positives are dialogue`() {
        for (text in listOf("おはようございます", "黄泉のツガイロ")) {
            val got = verdict(text, inBubble = false, pageKind = PageKind.INTERIOR)
            assertEquals("$text: ${got.reasons}", false, got.isSfx)
        }
    }

    @Test
    fun `real onomatopoeia is still recognised`() {
        for (text in listOf("ドン", "ハッ…", "ドキドキ", "ゴゴゴゴゴゴ", "ザザザ…", "パカ", "ドドドド")) {
            val got = verdict(text, inBubble = false, pageKind = PageKind.INTERIOR)
            assertEquals("$text: ${got.reasons}", true, got.isSfx)
        }
    }

    /**
     * A sentence that merely opens on a glossary key must never be glossed.
     *
     * ざわ is a dictionary entry, so `SfxDictionary.lookup`'s prefix step will happily
     * put a confident heartbeat gloss on ざわざわしてきた. That was cosmetic while the
     * class came from the detector; now that the glossary is a classification input it
     * would be causal, which is why the classifier only ever sees `lookupConfident`.
     */
    @Test
    fun `a sentence opening on a glossary key is not a sound effect`() {
        for (text in listOf("ざわざわしてきた", "どきどきするなあ", "ぱちぱち手を叩く")) {
            val got = verdict(text, inBubble = false, pageKind = PageKind.INTERIOR)
            assertEquals("$text: ${got.reasons}", false, got.isSfx)
            assertNull(
                "$text must not reach the classifier with a glossary hit",
                dictionary.lookupConfident(text),
            )
        }
    }

    /** Observed on the fixture photographs: short hiragana interjections are speech. */
    @Test
    fun `short hiragana interjections are dialogue`() {
        for (text in listOf("そう", "うん", "ん？", "あ")) {
            val got = verdict(text, inBubble = false, pageKind = PageKind.INTERIOR)
            assertEquals("$text: ${got.reasons}", false, got.isSfx)
        }
    }

    @Test
    fun `the bubble tiebreak cannot outvote strong content evidence`() {
        val got = verdict("ドキドキ", inBubble = true, pageKind = PageKind.INTERIOR)
        assertTrue("a detected bubble is worth one point, not a veto: " + got.reasons, got.isSfx)
    }

    @Test
    fun `a cover never produces a sound effect`() {
        for (text in listOf("ビースターズ", "ドキドキ")) {
            val got = verdict(text, inBubble = false, pageKind = PageKind.COVER)
            assertEquals("$text on a cover: ${got.reasons}", false, got.isSfx)
        }
    }

    /** Symbol runs and OCR garbage are not sound effects; see the "no kana" veto. */
    @Test
    fun `symbol runs are not sound effects`() {
        val got = verdict("＠＠＠", inBubble = false, pageKind = PageKind.INTERIOR)
        assertEquals(false, got.isSfx)
        assertTrue("the veto should say why: " + got.reasons, got.reasons.contains("no kana"))
    }
}
