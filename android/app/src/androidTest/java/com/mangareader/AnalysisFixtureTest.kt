package com.mangareader

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangareader.analysis.Grammar
import com.mangareader.model.PhraseRole
import com.mangareader.model.RegisterLevel
import com.mangareader.pipeline.DictionaryStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 6 on the device, against the harness, **with JMdict**.
 *
 * `ml/analysis/src/test/.../AnalysisParityTest.kt` already covers segmentation,
 * furigana, romaji and the SFX glossary on the JVM, where Kuromoji runs happily and
 * `android.database.sqlite` does not exist. This covers the half that only a device can
 * run: unpacking the 53 MB asset out of the APK, opening it read-only, and walking the
 * lookup section 6.3 specifies -- part of speech first, then commonness, then a kanji
 * headword over a kana one.
 *
 * Both sides read the same frozen file, `tools/export_analysis_fixture.py`'s output, so
 * a disagreement is the port and not the fixture drifting.
 *
 * Run it without letting the runner uninstall the app afterwards -- an uninstall takes
 * the staged models and the library with it:
 *
 *     adb shell am instrument -w -e class com.mangareader.AnalysisFixtureTest \
 *       com.mangareader.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class AnalysisFixtureTest {

    private val tag = "AnalysisFixtureTest"

    @Test
    fun analysisMatchesHarnessWithDictionary(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext

        val dictionaries = DictionaryStore(target)
        assertTrue(
            "the APK carries no jmdict.sqlite -- run tools/build_jmdict_mobile.py and rebuild",
            dictionaries.isPackaged,
        )
        val unpacked = dictionaries.install { progress ->
            Log.i(tag, "unpack " + progress.bytesDone + "/" + progress.bytesTotal)
        }
        assertTrue("dictionary did not unpack", unpacked)

        val fixture = JSONObject(
            instrumentation.context.assets.open("analysis_fixture.json")
                .use { it.readBytes().toString(Charsets.UTF_8) },
        )

        dictionaries.analyser().use { analyser ->
            assertTrue("JMdict did not open after unpacking", analyser.hasDictionary)

            val regions = fixture.getJSONArray("regions")
            val failures = mutableListOf<String>()
            var tokens = 0
            var glossed = 0
            var checked = 0

            for (index in 0 until regions.length()) {
                val region = regions.getJSONObject(index)
                if (region.getString("route") != "tokens") continue
                val text = region.getString("text")
                val expected = region.getJSONArray("tokens")
                val actual = analyser.tokenize(text)
                checked++

                if (expected.length() != actual.size) {
                    failures += text + ": " + expected.length() + " tokens expected, " +
                        actual.size + " produced"
                    continue
                }
                for (position in 0 until expected.length()) {
                    val want = expected.getJSONObject(position)
                    val got = actual[position]
                    tokens++
                    if (want.getJSONArray("glosses").length() > 0) glossed++

                    fun check(field: String, wanted: Any?, produced: Any?) {
                        if (wanted != produced) {
                            failures += text + " [" + position + "] " + field +
                                ": expected <" + wanted + ">, got <" + produced + ">"
                        }
                    }
                    check("surface", want.getString("surface"), got.surface)
                    check("reading", want.getString("reading"), got.reading)
                    check("baseForm", want.getString("baseForm"), got.baseForm)
                    check("baseReading", want.getString("baseReading"), got.baseReading)
                    check("partOfSpeech", want.getString("partOfSpeech"), got.partOfSpeech)
                    check("posLabel", want.getString("posLabel"), got.posLabel)
                    check("romaji", want.getString("romaji"), got.romaji)
                    check(
                        "jmdictEntryId",
                        if (want.isNull("jmdictEntryId")) null else want.getLong("jmdictEntryId"),
                        got.jmdictEntryId,
                    )
                    check("glosses", glosses(want.getJSONArray("glosses")), glosses(got.glosses))
                }
            }

            // SFX takes the same route here as on the JVM, but running it again on the
            // device costs nothing and catches a platform difference in the fold.
            val probes = fixture.getJSONArray("sfx_probes")
            for (index in 0 until probes.length()) {
                val probe = probes.getJSONObject(index)
                val expected = probe.optJSONObject("sfx")
                val got = analyser.analyseSfx(probe.getString("text"))
                val wanted = expected?.getString("text")
                if (wanted != got?.text || expected?.getString("match") != got?.match) {
                    failures += "sfx " + probe.getString("text") + ": expected <" + wanted +
                        "/" + expected?.optString("match") + ">, got <" + got?.text +
                        "/" + got?.match + ">"
                }
            }

            Log.i(
                tag,
                checked.toString() + " regions, " + tokens + " tokens, " + glossed +
                    " with glosses, " + failures.size + " mismatches",
            )
            failures.take(25).forEach { Log.w(tag, it) }
            assertTrue(
                failures.size.toString() + " mismatches over " + tokens + " tokens:\n" +
                    failures.take(15).joinToString("\n"),
                failures.isEmpty(),
            )
            assertTrue("no dialogue regions in the fixture", checked > 0)
            assertTrue("no glosses in the fixture -- was JMdict built?", glossed > 0)
        }
    }

    /** Section 6.3's ranking, on the three probes `tools/build_jmdict.py` also runs. */
    @Test
    fun lookupPrefersThePartOfSpeechTheTokenizerFound(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionaries = DictionaryStore(target)
        dictionaries.install()

        dictionaries.analyser().use { analyser ->
            val tokens = analyser.tokenize("化け物を見る")
            val bakemono = tokens.first { it.surface == "化け物" }
            assertTrue(
                "expected a goblin: " + bakemono.glosses.map { it.text },
                bakemono.glosses.any { it.text.contains("monster") },
            )
            val miru = tokens.first { it.baseForm == "見る" }
            assertEquals("みる", miru.baseReading)
            assertTrue(
                "expected a verb sense: " + miru.glosses.map { it.pos },
                miru.glosses.any { it.pos.startsWith("v1") },
            )
        }
    }

    /**
     * Section 3.3 tier 4 on the device, and specifically the asset the JVM tests cannot
     * see.
     *
     * `ml/analysis`'s `GrammarTest` already covers the chunker, the register classifier
     * and the matcher against a hand-built rule table. What only a device run can check
     * is that the table the **APK actually ships** parses and fires: the JVM tests read
     * `tools/grammar_rules.json` off the filesystem, while the app reads whatever
     * `stageDictionaryAssets` copied into `assets/`. A build that staged nothing, or
     * staged a stale copy, passes every JVM test and produces an empty section 4 on the
     * phone.
     *
     * Deliberately does **not** unpack JMdict first. Tier 4 is a pure function of the
     * tokens and the bundled table, and the section has to be complete on a fresh install
     * with nothing downloaded -- that is the tier-0 guarantee, and asserting it here is
     * cheaper than remembering to check it by hand.
     */
    @Test
    fun grammarRulesShipInTheApkAndFire(): Unit = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionaries = DictionaryStore(target)

        val rules = dictionaries.grammar()
        assertTrue(
            "no grammar rules in the APK -- stageDictionaryAssets did not run",
            rules.size > 0,
        )
        assertTrue("the shipped table has no register rules", rules.hasRegisterRules)

        val grammar = Grammar(rules)
        dictionaries.analyser().use { analyser ->
            val tokens = analyser.tokenize("まぁ見る人によって色々呼ばれてて統一されてないけど")
            val breakdown = grammar.analyse(tokens)

            assertEquals(RegisterLevel.CASUAL, breakdown.register.level)
            assertTrue("no phrases", breakdown.phrases.isNotEmpty())
            assertTrue("no notes", breakdown.notes.isNotEmpty())

            // The one the design review called out: 統一 is tagged a noun by IPADIC, and
            // labelling the sentence's main predicate "noun" would be worse than saying
            // nothing. `Bunsetsu.posOf` corrects the suru-compound.
            val predicate = breakdown.phrases.last()
            assertEquals("統一されてないけど", predicate.text)
            assertEquals(PhraseRole.PREDICATE, predicate.role)
            assertEquals("動詞", predicate.pos)

            // Every token carries a relation and a sentence about it, on the device, with
            // the real dictionary loaded and no model configured. This is the tier-0
            // guarantee for the rebuilt section: the whole tree, offline.
            breakdown.phrases.flatMap { it.components }.forEach { component ->
                assertTrue(
                    "no explanation for " + component.token.surface,
                    component.explanation.isNotBlank(),
                )
            }

            // 見る is attributive and attaches to 人によって, not to the predicate.
            assertEquals(listOf(5, 2, 4, 4, 5, -1), breakdown.phrases.map { it.head })

            // conjugationForm survives the round trip through Room from v8 on. Read here
            // from a live tokenize, which is the value the migration has to preserve.
            assertTrue(
                "no conjugation data reached the device",
                tokens.any { it.conjugationForm.isNotEmpty() && it.conjugationType.isNotEmpty() },
            )
        }
    }

    private fun glosses(array: JSONArray): String = (0 until array.length()).joinToString("|") {
        array.getJSONObject(it).getString("text")
    }

    private fun glosses(list: List<com.mangareader.model.Gloss>): String =
        list.joinToString("|") { it.text }
}
