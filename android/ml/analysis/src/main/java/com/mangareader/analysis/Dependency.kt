package com.mangareader.analysis

import com.mangareader.model.Phrase
import com.mangareader.model.PhraseRole

/**
 * Which phrase each 文節 modifies -- the reference JSON's `target_dependency`.
 *
 * **This is a heuristic, not a parse, and the file should keep saying so.** `Bunsetsu.kt`
 * opens with the reason no real dependency parser is in this APK: J.DepP, CaboCha and the
 * UD pipelines all attach chunks with a second trained model, and none of them ships in a
 * few megabytes. That has not changed. What has changed is the question being asked.
 *
 * A parser has to be right about arbitrary text. This has to be right about one line of
 * manga dialogue, and it gets to lean on two facts that hold for nearly all of them:
 *
 *  - **Japanese is head-final.** A modifier precedes what it modifies, always. So every
 *    edge points rightward, which makes cycles and crossing edges structurally impossible
 *    rather than something to check for afterwards.
 *  - **A bubble is short.** Six 文節 is a long line. Most ambiguity a real parser exists to
 *    resolve -- which of four candidate verbs a phrase eight chunks back attaches to --
 *    does not arise when there are two verbs and one of them is the last word.
 *
 * Where it is wrong, it is wrong in a bounded way: it attaches to the nearest plausible
 * head rather than a distant one, so the error is local. And "Explain in depth" can
 * correct it -- the model is sent the current heads and may return replacements.
 *
 * The one case worth naming, because getting it wrong changes what the sentence *means*:
 * an attributive verb attaches to the noun on its right, not to the sentence's predicate.
 * 見る in 見る人によって is "the person who looks", and a rule that sent everything to the
 * nearest predicate would read it as a second main verb.
 */
object Dependency {

    /** Anything that can head a predicate. Mirrors `Bunsetsu.VERBAL`. */
    private val VERBAL = setOf("動詞", "形容詞", "助動詞")

    /** Fill in [Phrase.head] for every phrase, in place of the -1 `Bunsetsu` leaves. */
    fun attach(phrases: List<Phrase>): List<Phrase> {
        if (phrases.size <= 1) return phrases.map { it.copy(head = -1) }
        val root = phrases.lastIndex
        return phrases.mapIndexed { index, phrase ->
            phrase.copy(head = headOf(phrases, index, root))
        }
    }

    private fun headOf(phrases: List<Phrase>, index: Int, root: Int): Int {
        if (index == root) return -1

        return when (phrases[index].role) {
            // Modifies a noun, and the noun it modifies is the very next phrase. 見る人:
            // there is no gap between an attributive and its head.
            PhraseRole.ADNOMINAL -> index + 1

            // An interjection is not attached to anything inside the sentence -- it sits
            // in front of the whole line. Pointing it at the first verb would claim まぁ
            // modifies 見る, which is not what it does.
            PhraseRole.INTERJECTION -> root

            // Everything else -- subjects, objects, topics, adverbials, connectives --
            // attaches to the next thing that can be predicated.
            else -> nextPredicative(phrases, index) ?: root
        }
    }

    /**
     * The nearest phrase to the right that could serve as a head.
     *
     * "Could serve" means it contains a verb, an adjective or an auxiliary. A phrase of
     * bare nouns cannot govern a subject, so 色々 skips over nothing here but would skip
     * over a noun phrase if one sat between it and the verb.
     */
    private fun nextPredicative(phrases: List<Phrase>, from: Int): Int? {
        for (i in from + 1..phrases.lastIndex) {
            val verbal = phrases[i].components.any { component ->
                component.token.partOfSpeech.substringBefore(",") in VERBAL
            }
            // A suru-compound reports 動詞 at the phrase level even though its head token
            // is tagged a noun -- the same correction `Bunsetsu.posOf` makes. Without this
            // 統一されてないけど would not look predicative to its own dependents.
            if (verbal || phrases[i].pos == "動詞") return i
        }
        return null
    }
}
