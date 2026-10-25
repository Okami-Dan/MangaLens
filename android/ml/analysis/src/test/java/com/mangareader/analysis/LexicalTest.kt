package com.mangareader.analysis

import com.mangareader.model.Gloss
import com.mangareader.model.PhraseRole
import com.mangareader.model.Token
import org.junit.Assert.*
import org.junit.Test

class LexicalTest {
    private val analyser = Analyser(null, SfxDictionary.parse("{\"entries\":[]}"))
    private fun words(text: String) = Lexical.group(text, analyser.tokenize(text))

    @Test fun `colloquial spellings are coherent word units`() {
        assertEquals("なんか", words("なーんか出ちゃったねぇ").first().lemma)
        assertEquals("adverb", words("なーんか出ちゃったねぇ").first().posLabel)
        assertEquals("もしもし", words("もしもーしハナちゃん").first().lemma)
        assertEquals("interjection", words("もしもーしハナちゃん").first().posLabel)
    }

    @Test fun `contraction and passive morphology stays accessible`() {
        val contraction = words("もう読んじゃったの？").first { it.surface.contains("読ん") }
        assertEquals("読んじゃった", contraction.surface)
        assertEquals("読む", contraction.lemma)
        assertTrue(contraction.inflections.any { it.contains("てしまう") })
        assertTrue(contraction.inflections.any { it.contains("past") })
        assertEquals("の", words("もう読んじゃったの？").first { it.surface == "の" }.surface)
        val passive = words("呼ばれてて統一されてないけど")
        assertEquals("呼ぶ", passive.first().lemma)
        assertTrue(passive.first().inflections.any { it.contains("passive") })
        val suru = passive.first { it.surface.startsWith("統一") }
        assertEquals("統一する", suru.lemma)
        assertEquals("verb", suru.posLabel)
        assertTrue(suru.inflections.contains("negative"))
    }

    @Test fun `repeated words and punctuation retain exact offsets`() {
        val texts = listOf("俺俺", "「幽霊」", "なーんか出ちゃったねぇ", "もしもーしハナちゃん", "．．．", "ツガイロ")
        for (text in texts) {
            val result = words(text)
            assertEquals(text, result.joinToString("") { it.surface })
            result.forEach { assertEquals(it.surface, text.substring(it.start, it.end)) }
            assertEquals(result.indices.toList(), result.map { it.id })
            assertTrue(result.zipWithNext().all { (a,b) -> a.end == b.start && a.to < b.from })
        }
        assertEquals(listOf(0, 1), words("俺俺").map { it.start })
    }

    @Test fun `only verified adjacent compounds replace original words`() {
        val tokens = analyser.tokenize("合流地点")
        val dictionary: (String, String, String) -> List<Jmdict.Entry> = { form, _, _ ->
            if (form == "合流地点") listOf(Jmdict.Entry(1L,
                listOf(Gloss(1L, "n", "", "meeting point", true)), "ごうりゅうちてん")) else emptyList()
        }
        val result = Lexical.group("合流地点", tokens, dictionary)
        assertEquals(1, result.size)
        assertEquals("meeting point", result.first().senses.first().text)
        assertEquals(tokens.size, words("合流地点").size)
        val particle = "合流の地点"
        assertTrue(Lexical.group(particle, analyser.tokenize(particle), dictionary).size > 1)
    }

    @Test fun `nominal fragments do not acquire imaginary argument roles`() {
        val text = "「幽霊」"
        val result = Grammar(GrammarRules.EMPTY).analyse(analyser.tokenize(text), words(text))
        assertEquals(PhraseRole.NOMINAL, result.phrases.single().role)
        assertEquals("名詞", result.phrases.single().pos)
        assertEquals(-1, result.phrases.single().head)
    }

    @Test fun `lexical evidence improves colloquial phrase role`() {
        val text = "なーんか出ちゃったねぇ"
        val result = Grammar(GrammarRules.EMPTY).analyse(analyser.tokenize(text), words(text))
        assertEquals(PhraseRole.ADVERBIAL, result.phrases.first().role)
        assertEquals("なーんか", result.phrases.first().text)
        assertEquals(PhraseRole.PREDICATE, result.phrases.last().role)
        assertTrue(result.phrases.all { it.head == -1 || it.head > it.id })
    }

    @Test fun `links do not cross sentence punctuation`() {
        val text = "俺が読む。君が来る。"
        val result = Grammar(GrammarRules.EMPTY).analyse(analyser.tokenize(text), words(text))
        assertEquals(2, result.phrases.count { it.head == -1 })
        val firstRoot = result.phrases.first { it.head == -1 }.id
        assertTrue(result.phrases.filter { it.id <= firstRoot }.all { it.head <= firstRoot })
    }

    @Test fun `ambiguous compounds retain morphology`() {
        val text = "合流地点"
        val original = analyser.tokenize(text)
        val result = Lexical.group(text, original) { _, _, _ -> listOf(
            Jmdict.Entry(1, listOf(Gloss(1,"n","","one",false)), "ごうりゅうちてん"),
            Jmdict.Entry(2, listOf(Gloss(2,"n","","two",false)), "ごうりゅうちてん")) }
        assertEquals(original.size, result.size)
    }

    @Test fun `damaged OCR is not normalized into a confident colloquial word`() {
        assertEquals("なんか", words("なんか").first().lemma)
        assertEquals("もしもし", words("もしもし").first().lemma)
        for (text in listOf("なーソか", "もしも一し", "ツガイロ", "ハナちゃん")) {
            val result = words(text)
            assertEquals(text, result.joinToString("") { it.surface })
            assertTrue(result.all { it.entryId == null && it.senses.isEmpty() })
            assertTrue(result.none { it.inflections.any { label -> label.startsWith("colloquial") } })
        }
    }

    @Test fun `manual reading survives grouping`() {
        val text = "なーんか"
        val tokens = analyser.tokenize(text).mapIndexed { index, token ->
            if (index == 0) token.copy(reading="なおし", readingIsUser=true) else token
        }
        assertEquals(tokens.joinToString("") { it.reading }, Lexical.group(text,tokens).single().reading)
    }
}
