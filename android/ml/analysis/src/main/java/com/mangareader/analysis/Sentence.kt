package com.mangareader.analysis

import com.mangareader.model.ClauseStructure
import com.mangareader.model.Phrase
import com.mangareader.model.PhraseRole
import com.mangareader.model.SentenceType
import com.mangareader.model.Token

/**
 * The two whole-sentence facts in the reference JSON's `metadata`, beside the register.
 *
 * **Read off the tokens, not off the punctuation.** Manga punctuates as a design choice
 * rather than as grammar: 殺したの…？ and 殺したの are the same question and only one of them
 * carries a mark, while an exclamation mark lands on plenty of lines that are not
 * exclamations. Where a mark *is* present it is corroboration, never the whole case.
 */
object Sentence {

    private val VERBAL = setOf("動詞", "形容詞", "助動詞")

    /** Endings that make a plain sentence a question without any mark. */
    private val QUESTION_TAIL = setOf("か", "かい", "かな", "の", "のか", "かしら")

    /** Endings that make it an order. な is the prohibitive; ろ / よ ride the verb form. */
    private val COMMAND_TAIL = setOf("な", "ください", "なさい", "たまえ")

    /**
     * What the line is doing.
     *
     * Order matters: a question mark beats a command form (来るのか is a question about an
     * order, not an order), and a fragment is decided last because "no predicate" is only
     * knowable after nothing else has matched.
     */
    fun type(tokens: List<Token>, phrases: List<Phrase>): SentenceType {
        val meaningful = tokens.filter { it.partOfSpeech.substringBefore(",") != "記号" }
        if (meaningful.isEmpty()) return SentenceType.FRAGMENT

        val marks = tokens.filter { it.partOfSpeech.substringBefore(",") == "記号" }
            .joinToString("") { it.surface }
        val tail = meaningful.last()

        if (marks.contains("？") || marks.contains("?")) return SentenceType.QUESTION
        if (tail.surface in QUESTION_TAIL && tail.partOfSpeech.startsWith("助詞")) {
            return SentenceType.QUESTION
        }

        if (tail.conjugationForm.startsWith("命令")) return SentenceType.COMMAND
        if (tail.surface in COMMAND_TAIL && tail.partOfSpeech.startsWith("助詞,終助詞")) {
            return SentenceType.COMMAND
        }

        val predicated = phrases.any { it.role == PhraseRole.PREDICATE }
        if (!predicated) {
            // An interjection on its own is a shout, not a fragment of a sentence. 「幽霊」
            // is a fragment; ハッ！ is an exclamation, and the difference is worth drawing.
            val allInterjection = phrases.isNotEmpty() &&
                phrases.all { it.role == PhraseRole.INTERJECTION }
            return if (allInterjection) SentenceType.EXCLAMATION else SentenceType.FRAGMENT
        }

        if (marks.contains("！") || marks.contains("!")) return SentenceType.EXCLAMATION
        return SentenceType.STATEMENT
    }

    /**
     * How many clauses, and how they are joined.
     *
     * [ClauseStructure.COMPLEX] wins over [ClauseStructure.COMPOUND] when a line is both,
     * because subordination is the harder thing to see and the thing the reader is more
     * likely to have missed. まぁ見る人によって色々呼ばれてて統一されてないけど has an
     * attributive clause *and* a connective, and the attributive is what makes it hard.
     */
    fun structure(phrases: List<Phrase>): ClauseStructure {
        if (phrases.none { it.role == PhraseRole.PREDICATE }) return ClauseStructure.FRAGMENT

        val attributive = phrases.any { phrase ->
            phrase.role == PhraseRole.ADNOMINAL &&
                phrase.components.any { it.token.partOfSpeech.substringBefore(",") in VERBAL }
        }
        if (attributive) return ClauseStructure.COMPLEX

        if (phrases.any { it.role == PhraseRole.CONNECTIVE }) return ClauseStructure.COMPOUND
        return ClauseStructure.SIMPLE
    }
}
