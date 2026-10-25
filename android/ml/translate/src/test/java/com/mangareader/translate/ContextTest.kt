package com.mangareader.translate

import com.mangareader.model.*
import org.junit.Assert.*
import org.junit.Test

class ContextTest {
    private val regions = listOf(Region(1L, RegionKind.TEXT_BUBBLE, Box(0f, 0f, 1f, 1f), 1f, ocrText="アサが来た"))
    @Test fun `context changes translation request identity`() {
        assertNotEquals(cacheKey(regions, "model"), cacheKey(regions, "model", pageContext="Series: One"))
        assertNotEquals(cacheKey(regions, "model", pageContext="Series: One"),
            cacheKey(regions, "model", pageContext="Series: Two"))
        assertNotEquals(cacheKey(regions, "model"), cacheKey(regions, "model", cover=true))
    }
    @Test fun `context and structure change explanation identity`() {
        val first = grammarRequest("アサが来た", emptyList(), context=TranslationContext("One", "一", "note"))
        val second = grammarRequest("アサが来た", emptyList(), context=TranslationContext("Two"))
        assertTrue(first.contains("Japanese series title: 一"))
        assertNotEquals(grammarCacheKey("アサが来た", "m", first), grammarCacheKey("アサが来た", "m", second))
        assertNotEquals(grammarCacheKey("x", "m", "tokens0"), grammarCacheKey("x", "m", "tokens1"))
    }
    @Test fun `meaning IDs and dependency corrections are validated`() {
        val token = Token(0,"俺","おれ","俺","名詞","noun")
        val phrases = listOf(Phrase(0,"俺",PhraseRole.NOMINAL,0,0,components=listOf(
            Component(token,Relation.OTHER,"Role unclear"))))
        val words = listOf(LexicalUnit(0,0,1,0,0,"俺","俺","おれ","おれ","名詞","noun"))
        val answer = parseGrammarNotes("""{"notes":[{"t":0,"note":"Speaker"},{"t":9,"note":"bad"}],"heads":[{"p":0,"head":0}],"meanings":[{"w":0,"meaning":"I"},{"w":7,"meaning":"bad"}]}""").validated(phrases,words)
        assertEquals(mapOf(0 to "Speaker"), answer.explanations)
        assertEquals(mapOf(0 to "I"), answer.meanings)
        assertTrue(answer.heads.isEmpty())
        assertTrue(parseGrammarNotes("broken").isEmpty)
    }

    @Test fun `partial meanings remain usable and invalid heads do not cross sentences`() {
        val partial = parseGrammarNotes("""{"meanings":[{"w":0,"meaning":"I"}],"notes":[""")
        assertEquals(mapOf(0 to "I"), partial.meanings)
        val phrases = listOf(
            Phrase(0,"俺",PhraseRole.NOMINAL,0,0,head=-1),
            Phrase(1,"君",PhraseRole.NOMINAL,1,1,head=-1))
        assertTrue(GrammarAnswer(emptyMap(),heads=mapOf(0 to 1)).validated(phrases,emptyList()).heads.isEmpty())
    }
}
