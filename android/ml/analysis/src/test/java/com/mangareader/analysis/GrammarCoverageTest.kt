package com.mangareader.analysis

import com.mangareader.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class GrammarCoverageTest {
    private fun asset(name: String) = File("../../../tools/$name").readText(Charsets.UTF_8)
    private val ledger = JSONObject(asset("grammar_coverage.json"))
    private val lexicon = GrammarLexicon.parse(asset("grammar_lexicon.json"))
    private val rulesJson = JSONObject(asset("grammar_rules.json"))
    private val rules = GrammarRules.parse(rulesJson.toString())
    private val analyser = Analyser(null, SfxDictionary.parse("{\"entries\":[]}"))

    @Test fun `every reviewed lemma has a meaningful grammatical explanation`() {
        val inventory = ledger.getJSONArray("inventory")
        for (i in 0 until inventory.length()) {
            val item = inventory.getJSONObject(i)
            val form = item.getJSONArray("forms").getJSONObject(0)
            val token = Token(1, item.getString("lemma"), Kana.toHiragana(form.getString("reading")),
                item.getString("lemma"), item.getString("pos"), "", conjugationType = item.getString("conjugationType"),
                conjugationForm = form.getString("conjugationForm"), start = 1, end = 2)
            val previous = Token(0, "食べ", "たべ", "食べる", "動詞,自立,*,*", "verb", start = 0, end = 1)
            val next = Token(2, "事", "こと", "事", "名詞,一般,*,*", "noun", start = 2, end = 3)
            val explanation = Relations.components(listOf(previous, token, next), PhraseRole.PREDICATE, true)[1].explanation
            assertTrue("${token.surface}/${token.partOfSpeech}: $explanation", explanation.length > 20)
            assertFalse("Missing grammar: ${token.surface}/${token.partOfSpeech}", explanation.startsWith("Its role is unclear"))
            if (token.partOfSpeech.startsWith("形容詞,接尾"))
                assertEquals(Relation.SUFFIX,Relations.components(listOf(previous,token,next),PhraseRole.PREDICATE,true)[1].relation)
            if (item.getJSONObject("review").getString("disposition") == "context-dependent")
                assertTrue(item.getJSONObject("review").getString("reason").length > 40)
            if (item.getJSONObject("review").getString("disposition") == "dictionary") {
                val forms = item.getJSONArray("forms")
                assertTrue("Dictionary review has no compatible source morphology: ${token.surface}/${token.partOfSpeech}",
                    (0 until forms.length()).any { j ->
                        val f = forms.getJSONObject(j)
                        lexicon.meanings(token.copy(surface=f.getString("surface"),
                            reading=Kana.toHiragana(f.getString("reading")),
                            conjugationForm=f.getString("conjugationForm"))).isNotEmpty()
                    })
            }
        }
    }

    @Test fun `every curated construction has positive and independent word fixtures`() {
        val rows = rulesJson.getJSONArray("rules")
        val byId = (0 until rows.length()).associate { rows.getJSONObject(it).let { r -> r.getString("id") to r } }
        val fixtures = ledger.getJSONArray("constructions")
        val covered = mutableSetOf<String>()
        for (i in 0 until fixtures.length()) {
            val f = fixtures.getJSONObject(i)
            val id = f.getString("ruleId")
            covered += id
            val single = GrammarRules.parse(JSONObject().put("rules", org.json.JSONArray().put(byId.getValue(id))).toString())
            val tokens = analyser.tokenize(f.getString("positive"))
            val expected = single.notes(tokens)
            assertTrue("$id did not fire on ${f.getString("positive")}", expected.isNotEmpty())
            val actual = rules.notes(tokens)
            assertTrue("$id was swallowed on ${f.getString("positive")}", expected.any { e ->
                actual.any { a -> a.from == e.from && a.to == e.to && a.explanation == e.explanation } })
            assertTrue("$id false positive on ${f.getString("negative")}", single.notes(analyser.tokenize(f.getString("negative"))).isEmpty())
            if (f.has("homonym")) {
                val h = f.getJSONObject("homonym")
                val token = Token(0, h.getString("surface"), "", h.getString("surface"), h.getString("pos"), "noun")
                assertTrue("$id accepted independent spelling", single.notes(listOf(token)).isEmpty())
            }
            assertEquals(actual.size, actual.distinctBy { Triple(it.from, it.to, it.explanation) }.size)
        }
        assertEquals(byId.keys, covered)
    }

    @Test fun `every verb and adjective conjugation has a reviewed family explanation`() {
        val families = ledger.getJSONArray("inflectionFamilies")
        for (i in 0 until families.length()) {
            val f = families.getJSONObject(i)
            assertFalse(f.toString(), Inflection.type(f.getString("conjugationType")).isEmpty())
            assertFalse(f.toString(), Inflection.form(f.getString("conjugationForm")).isEmpty())
        }
        val sentence = analyser.sentence("勉強する", Grammar(rules))
        assertTrue(sentence.grammar.components.any { it.relation == Relation.VERBALISER })
        assertFalse(analyser.sentence("何をする", Grammar(rules)).grammar.components.any { it.relation == Relation.VERBALISER })
    }

    @Test fun `dictionary means grammar only and does not change persisted tokens or model provenance`() {
        for (text in listOf("三人", "ハナちゃん", "俺たち", "お手紙", "負けっぷりに", "てる", "ない", "食べやすい")) {
            val sentence = analyser.sentence(text, Grammar(rules))
            val original = sentence.tokens.toList()
            val merged = Grammar(rules).merge(sentence.grammar,
                sentence.tokens.associate { it.position to "Model context ${it.position}" }, modelId = "test-model")
            val entries = LearnerBreakdown.entries(sentence.words, merged, lexicon)
            assertEquals(original, sentence.tokens)
            entries.flatMap { it.grammar }.filterIsInstance<LearnerGrammar.TokenNote>().forEach {
                assertTrue(it.component.fromModel)
                assertEquals("test-model", it.component.modelId)
                assertEquals("Model context ${it.component.token.position}", it.component.explanation)
                assertEquals(sentence.grammar.components.first { c -> c.token.position == it.from }.explanation,
                    it.attachmentExplanation)
            }
            assertEquals(entries.flatMap { it.grammar }.size, entries.flatMap { it.grammar }.distinctBy { it.from }.size)
        }
        for (text in listOf("人", "手紙", "負ける", "照る", "見る", "いる")) {
            analyser.tokenize(text).filter { PartOfSpeech.isIndependent(it.partOfSpeech) }.forEach {
                assertTrue("Vocabulary became grammar: $text", lexicon.meanings(it).isEmpty())
            }
        }
        val suffix = analyser.tokenize("三人").last()
        assertTrue(lexicon.meanings(suffix).any { "counter" in it.text })
        assertTrue(GrammarLexicon.EMPTY.meanings(suffix).isEmpty())
        assertTrue(lexicon.meanings(suffix.copy(surface = "unmatched", baseForm = "unmatched", reading = "にん")).isEmpty())
    }

    @Test fun `sentences cannot share a construction and attempt is not role marking`() {
        val fragments = rules.notes(analyser.tokenize("先生として働く。行こうとしている。")).map { it.fragment }
        assertEquals(1, fragments.count { it == "として" })
        val isolated = rules.notes(analyser.tokenize("食べて。いる。"))
        assertFalse(isolated.any { it.fragment == "〜ている" })
        val first = rules.notes(analyser.tokenize("行くな。行く。"))
        assertTrue(first.any { it.fragment.contains("な") })
    }
}
