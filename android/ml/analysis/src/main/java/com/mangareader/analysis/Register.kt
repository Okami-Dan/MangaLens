package com.mangareader.analysis

import com.mangareader.model.Phrase
import com.mangareader.model.Register
import com.mangareader.model.RegisterLevel
import com.mangareader.model.Token

/**
 * How politely the speaker is talking, ported from `tools/check_grammar_rules.py`.
 *
 * This closes a loop the app has been leaving open. `Prompts.kt` tells every translator
 * to "preserve register and speech style -- casual stays casual, keigo stays polite,
 * rough speech stays rough", and then nothing in the UI ever says what the register was.
 * A learner reading the English has no way to tell whether the flatness they are looking
 * at is the character or the model. Now the sheet says.
 *
 * The levels themselves live in `grammar_rules.json` rather than here, so adding 〜ますまい
 * is a data edit. What lives here is the arbitration:
 *
 *  - **The strongest level present wins.** [RegisterLevel] is declared weakest-first and
 *    the comparison is ordinal, so a humble verb inside a です sentence reads as HUMBLE
 *    rather than POLITE -- which is right, because 伺います is not merely polite, and a
 *    bubble that mixes registers is characterised by the highest note it reaches.
 *  - **Evidence is the enclosing 文節, not the trigger.** The matcher fires on て, on ない,
 *    on ます. Telling a reader the line is casual "because て" teaches nothing -- a bare
 *    auxiliary is not something you can point at on the page. Telling them it is casual
 *    because 呼ばれてて, they can see it. The trigger is what the matcher needs; the phrase
 *    is what the sheet shows.
 *
 * **The verdict is no longer a note.** It used to be emitted as a [GrammarNote] pinned to
 * the top of the list, which was the only way to say it when the section was a list. It is
 * a property of the whole sentence rather than of a span of it, so it now rides on
 * [Register] itself and the sheet renders it in the metadata line beside the sentence type
 * -- which is also where the mockup puts it.
 */
object Register {

    /** Evidence is quoted back to someone looking at the line. Three makes the case. */
    private const val MAX_EVIDENCE = 3

    fun classify(
        rules: GrammarRules,
        tokens: List<Token>,
        phrases: List<Phrase>,
    ): Register {
        val hits = rules.registerHits(tokens)
        if (hits.isEmpty()) {
            // No hits has two causes and they are not the same claim. With a table
            // loaded, nothing matching means the line carries no politeness marking and
            // [note] says so. With no table -- a missing or unreadable asset -- nothing
            // was ever asked, and asserting "this bubble has no predicate" over 行きます
            // would be the analyser inventing a finding out of its own failure. Stay
            // silent instead: the phrases and the tokens still stand.
            val note = if (rules.hasRegisterRules && tokens.isNotEmpty()) {
                "The bubble has no predicate, so there is nothing here that marks " +
                    "politeness either way."
            } else {
                ""
            }
            return Register(RegisterLevel.NEUTRAL, note = note)
        }

        val level = hits.maxOf { it.level }
        val winning = hits.filter { it.level == level }

        val evidence = winning
            .sortedBy { it.from }
            .map { hit -> enclosing(hit, tokens, phrases) }
            .distinct()
            .take(MAX_EVIDENCE)

        return Register(level = level, evidence = evidence, note = winning.first().note)
    }

    private fun enclosing(
        hit: GrammarRules.Hit,
        tokens: List<Token>,
        phrases: List<Phrase>,
    ): String =
        phrases.firstOrNull { hit.from >= it.from && hit.to <= it.to }?.text
            ?: tokens.subList(hit.from, hit.to + 1).joinToString("") { it.surface }

    /** What the metadata chip reads: one lower-case word. */
    fun label(level: RegisterLevel): String = when (level) {
        RegisterLevel.NEUTRAL -> "no register"
        RegisterLevel.CASUAL -> "casual"
        RegisterLevel.ROUGH -> "rough"
        RegisterLevel.ARCHAIC -> "archaic"
        RegisterLevel.POLITE -> "polite"
        RegisterLevel.HUMBLE -> "humble"
        RegisterLevel.HONORIFIC -> "honorific"
    }
}
