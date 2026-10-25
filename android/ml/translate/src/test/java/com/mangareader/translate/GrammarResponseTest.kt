package com.mangareader.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [parseGrammarNotes] against the failure modes `Response.kt` already documents.
 *
 * These are not hypothetical shapes. Every one of them is something a 1.7B model was
 * observed doing to the *translation* response on the fixture pages, and there is no
 * reason a second response shape would be treated better. The point of each case is that
 * the parser degrades to fewer notes, never to an exception and never to the model's
 * discarded first draft.
 *
 * The shape changed with the tier 4 rebuild: a note is keyed by **token index** rather
 * than by a Japanese fragment the model had to copy back. Everything below still exercises
 * the same three passes, because the failure modes belong to the model rather than to the
 * schema.
 */
class GrammarResponseTest {

    @Test
    fun readsTheShapeThePromptAsksFor() {
        val answer = parseGrammarNotes(
            """{"notes":[{"t":3,"note":"Marks what the outcome hinges on."},""" +
                """{"t":14,"note":"Closes the line as an aside."}]}""",
        )
        assertEquals(2, answer.explanations.size)
        assertEquals("Marks what the outcome hinges on.", answer.explanations[3])
        assertEquals("Closes the line as an aside.", answer.explanations[14])
        assertTrue(answer.heads.isEmpty())
    }

    /**
     * The reason the schema changed.
     *
     * The old shape asked the model to copy the fragment back and recovered a position
     * with `text.indexOf`. A model that paraphrased, normalised 〜 away, or quoted a word
     * that occurs twice produced a note pinned to the wrong place or to nothing at all.
     * An integer index cannot be paraphrased, so the note lands on the token it was
     * written about or is dropped.
     */
    @Test
    fun anIndexIsNotAmbiguousTheWayACopiedFragmentWas() {
        val answer = parseGrammarNotes(
            """{"notes":[{"t":7,"note":"The second て, not the first."}]}""",
        )
        assertEquals(mapOf(7 to "The second て, not the first."), answer.explanations)
    }

    @Test
    fun readsHeadCorrectionsWhenTheModelOffersThem() {
        val answer = parseGrammarNotes(
            """{"notes":[{"t":1,"note":"Attributive."}],"heads":[{"p":1,"head":2}]}""",
        )
        assertEquals(mapOf(1 to 2), answer.heads)
        assertEquals(1, answer.explanations.size)
    }

    /**
     * `heads` is a correction offered on top of an answer that is already complete and
     * already on screen. A malformed one must not cost the notes it arrived with.
     */
    @Test
    fun aMangledHeadsArrayDoesNotCostTheNotes() {
        val answer = parseGrammarNotes(
            """{"notes":[{"t":0,"note":"A hedge."}],"heads":[{"p":"who knows"}]}""",
        )
        assertEquals(1, answer.explanations.size)
        assertTrue(answer.heads.isEmpty())
    }

    @Test
    fun stripsMarkdownFences() {
        val answer = parseGrammarNotes(
            "```json\n{\"notes\":[{\"t\":2,\"note\":\"Polite.\"}]}\n```",
        )
        assertEquals("Polite.", answer.explanations[2])
    }

    /**
     * The one that matters most. Qwen3 deliberates *in JSON*, drafting an answer inside
     * the reasoning block before committing to a different one after it. Locating the
     * array without dropping the block first serves the user the model's first guess.
     */
    @Test
    fun prefersTheAnswerOverADraftInsideAThinkBlock() {
        val answer = parseGrammarNotes(
            "<think>Maybe {\"notes\":[{\"t\":0,\"note\":\"WRONG DRAFT\"}]}</think>\n" +
                "{\"notes\":[{\"t\":4,\"note\":\"Marks the direct object.\"}]}",
        )
        assertEquals(1, answer.explanations.size)
        assertEquals("Marks the direct object.", answer.explanations[4])
        assertTrue(answer.explanations.containsKey(4))
        assertTrue(!answer.explanations.containsKey(0))
    }

    /** The stray `[]` that makes the enclosing object unparseable while the array is fine. */
    @Test
    fun survivesAStrayArrayAfterTheGoodOne() {
        val answer = parseGrammarNotes("""{"notes":[{"t":6,"note":"Seeks agreement."}] []}""")
        assertEquals(mapOf(6 to "Seeks agreement."), answer.explanations)
    }

    /** Prose either side, and no array key at all -- the third pass scrapes the objects. */
    @Test
    fun scrapesLooseObjectsWhenTheArrayKeyIsMissing() {
        val answer = parseGrammarNotes(
            "Sure! Here is the breakdown:\n" +
                "{\"t\":9,\"note\":\"Explanatory.\"}\n" +
                "{\"t\":10,\"note\":\"Assertive, masculine.\"}\n" +
                "Hope that helps.",
        )
        assertEquals(2, answer.explanations.size)
        assertEquals("Explanatory.", answer.explanations[9])
        assertEquals("Assertive, masculine.", answer.explanations[10])
    }

    /**
     * The shape that separates this from [parseResponse]: a note is keyed on `t` and
     * carries no `id`, so the translation parser's loose pattern finds nothing here.
     */
    @Test
    fun doesNotCollideWithTheTranslationShape() {
        val json = """{"notes":[{"t":0,"note":"Topic."}]}"""
        assertTrue(parseResponse(json, listOf(1)).isEmpty())
        assertEquals(1, parseGrammarNotes(json).explanations.size)
    }

    @Test
    fun dropsAMangledNoteRatherThanTheWholeCall() {
        val answer = parseGrammarNotes(
            """{"notes":[{"t":1,"note":""},{"t":2,"note":"Marks the subject."},""" +
                """{"note":"No index, so nowhere to put it."},{"t":-4,"note":"Negative."}]}""",
        )
        // An empty note goes. A note with no index goes too, and that is the one real
        // behaviour change: the old parser kept it and rendered it with a zero span,
        // because a fragment-anchored note could still be read on its own. A per-token
        // explanation with no token has nowhere to render at all.
        assertEquals(mapOf(2 to "Marks the subject."), answer.explanations)
    }

    @Test
    fun trimsWhitespaceTheModelPadsWith() {
        val answer = parseGrammarNotes("""{"notes":[{"t":0,"note":"  Padded.  "}]}""")
        assertEquals("Padded.", answer.explanations[0])
    }

    @Test
    fun garbageIsEmptyRatherThanAThrow() {
        assertTrue(parseGrammarNotes("").isEmpty)
        assertTrue(parseGrammarNotes("I cannot help with that.").isEmpty)
        assertTrue(parseGrammarNotes("{{{").isEmpty)
    }
}
