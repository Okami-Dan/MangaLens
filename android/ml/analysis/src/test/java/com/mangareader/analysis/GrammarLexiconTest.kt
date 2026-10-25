package com.mangareader.analysis

import com.mangareader.model.Token
import org.junit.Assert.*
import org.junit.Test

class GrammarLexiconTest {
    private val lexicon = GrammarLexicon.parse("""{
      "forms":[
        {"entryId":1,"text":"御","kind":"kanji","appliesTo":[]},
        {"entryId":1,"text":"お","kind":"kana","appliesTo":[]},
        {"entryId":1,"text":"ご","kind":"kana","appliesTo":["御"]},
        {"entryId":2,"text":"られる","kind":"kana","appliesTo":[]},
        {"entryId":3,"text":"にとって","kind":"kana","appliesTo":[]},
        {"entryId":4,"text":"照る","kind":"kanji","appliesTo":[]},
        {"entryId":4,"text":"てる","kind":"kana","appliesTo":["照る"]}
      ],
      "senses":[
        {"entryId":1,"position":0,"pos":["pref"],"meaning":"respectful prefix","kanjiRestrictions":["御"],"readingRestrictions":["お"]},
        {"entryId":1,"position":1,"pos":["pref"],"meaning":"another prefix meaning","kanjiRestrictions":["御"],"readingRestrictions":["お"]},
        {"entryId":1,"position":2,"pos":["pref"],"meaning":"go prefix","kanjiRestrictions":["御"],"readingRestrictions":["ご"]},
        {"entryId":1,"position":3,"pos":["n"],"meaning":"unrelated noun","kanjiRestrictions":[],"readingRestrictions":[]},
        {"entryId":2,"position":0,"pos":["aux-v"],"meaning":"passive; potential; honorific","kanjiRestrictions":[],"readingRestrictions":[]},
        {"entryId":3,"position":0,"pos":["exp"],"meaning":"from the standpoint of","kanjiRestrictions":[],"readingRestrictions":[]},
        {"entryId":4,"position":0,"pos":["v5r"],"meaning":"shine","kanjiRestrictions":[],"readingRestrictions":[]}
      ]} """)
    private fun token(surface: String, pos: String, reading: String = "", base: String = surface) =
        Token(0,surface,reading,base,pos,"")

    @Test fun `spelling and reading restrictions select compatible senses without choosing a context`() {
        val prefix = token("御","接頭詞,名詞接続,*,*","お")
        assertEquals(listOf("respectful prefix","another prefix meaning"),lexicon.meanings(prefix).map { it.text })
        // A corrected surface reading must override a persisted lemma reading for exact forms.
        assertEquals(listOf("respectful prefix","another prefix meaning"),
            lexicon.meanings(prefix.copy(baseReading="ご",readingIsUser=true)).map { it.text })
        assertEquals(listOf("go prefix"),lexicon.meanings(prefix.copy(reading="ご")).map { it.text })
        assertTrue(lexicon.meanings(prefix.copy(reading="み")).isEmpty())
        assertEquals(listOf(0,1),lexicon.meanings(token("お","接頭詞,名詞接続,*,*","お")).map { it.sensePosition })
        assertTrue(lexicon.meanings(prefix.copy(surface="unmatched",baseForm="unmatched",reading="お")).isEmpty())
        assertTrue(lexicon.meanings(prefix.copy(partOfSpeech="名詞,一般,*,*")).isEmpty())
    }

    @Test fun `lemma fallback requires inflected grammatical morphology and never a homophone`() {
        val inflected = token("られ","動詞,接尾,*,*","られ","られる")
        assertTrue(lexicon.meanings(inflected).isEmpty())
        val grammatical = inflected.copy(conjugationType="一段",conjugationForm="連用形")
        assertEquals("passive; potential; honorific",lexicon.meanings(grammatical).single().text)
        assertTrue(lexicon.meanings(grammatical.copy(partOfSpeech="動詞,自立,*,*")).isEmpty())
        assertTrue(lexicon.meanings(token("てる","動詞,非自立,*,*","てる")).isEmpty())
        assertTrue(lexicon.meanings(token("にとって","助詞,格助詞,一般,*")).isEmpty())
        assertEquals("from the standpoint of",lexicon.meanings(token("にとって","助詞,格助詞,連語,*")).single().text)
    }
}
