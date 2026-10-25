package com.mangareader.analysis

import com.mangareader.model.Component
import com.mangareader.model.GrammarBreakdown
import com.mangareader.model.GrammarNote
import com.mangareader.model.NoteSource
import com.mangareader.model.Phrase
import com.mangareader.model.Relation
import com.mangareader.model.Token
import com.mangareader.model.LexicalUnit

/**
 * Section 3.3 tier 4 -- the grammar breakdown, offline.
 *
 * It combines morphology, dictionary-verified lexical units and the bundled rules,
 * without network or inference. Optional model explanations can suggest validated
 * phrase heads and contextual meanings; the offline analysis remains available.
 *
 * Five layers, each doing one thing, composed here:
 *
 *  1. `Bunsetsu` groups the tokens into 文節 and gives each phrase a role.
 *  2. `Relations` says what every token inside a phrase is doing, with a sentence about it.
 *  3. `Dependency` attaches the phrases to each other.
 *  4. `Sentence` reads the sentence type and clause structure off the result.
 *  5. `GrammarRules` matches the 98 multi-token patterns and they are filed under the
 *     phrase that contains them.
 *
 * **Not persisted.** Unlike stage 6's tokens, the breakdown is recomputed when the sheet
 * opens. It is microseconds over the thirty-odd tokens of a bubble, and recomputing means
 * a rule added in a later version applies to the whole existing library at once rather
 * than to pages captured after the update. The LLM's explanations are the ones worth
 * storing, because those cost money or a minute of inference.
 */
class Grammar(private val rules: GrammarRules) {

    val hasRules: Boolean get() = rules.size > 0

    fun analyse(tokens: List<Token>, words: List<LexicalUnit> = emptyList()): GrammarBreakdown {
        if (tokens.isEmpty()) return GrammarBreakdown.NONE

        // Repair learner-visible boundaries without destroying the original morphology.
        val effective = if (words.isEmpty()) tokens else words.map { word ->
            val original = tokens.first { it.position == word.from }
            original.copy(surface = word.surface, baseForm = word.lemma,
                partOfSpeech = word.partOfSpeech, posLabel = word.posLabel,
                reading = word.reading, romaji = Romaji.of(word.reading), start = word.start, end = word.end)
        }
        val sentences = mutableListOf<MutableList<Token>>(mutableListOf())
        effective.forEach { token ->
            sentences.last() += token
            if (token.surface in setOf("。", "！", "？", "!", "?")) sentences += mutableListOf<Token>()
        }
        val phrases = mutableListOf<Phrase>()
        sentences.filter { it.isNotEmpty() }.forEach { sentence ->
            val chunks = Bunsetsu.chunk(sentence)
            val grouped = chunks.map { phrase ->
                val lastWord = words.lastOrNull { it.from <= phrase.to && it.from >= phrase.from }
                val end = lastWord?.to ?: phrase.to
                val constituents = tokens.filter { it.position in phrase.from..end && it.head != "記号" }
                phrase.copy(to = end, components = Relations.components(constituents, phrase.role,
                    phrase.id == chunks.lastIndex))
            }
            val offset = phrases.size
            phrases += Dependency.attach(grouped).map { it.copy(id = it.id + offset,
                head = if (it.head < 0) -1 else it.head + offset) }
        }
        val register = Register.classify(rules, tokens, phrases)

        return GrammarBreakdown(
            register = register,
            sentenceType = Sentence.type(tokens, phrases),
            clauseStructure = Sentence.structure(phrases),
            phrases = phrases,
            notes = fileUnderPhrases(rules.notes(tokens), phrases),
        )
    }

    /**
     * Give every pattern note the phrase it belongs under, and drop the ones the
     * per-token layer now says better.
     *
     * A note whose token span sits inside one phrase renders as an extra line in that
     * phrase's block. One that straddles a boundary keeps [GrammarNote.phraseId] at -1 and
     * falls to the short list underneath -- there is no single phrase it is about, and
     * filing it under the first one it touches would be a quiet lie about its scope.
     *
     * 〜てない spans the last two tokens of 統一されてないけど and lands inside it. 〜によって
     * is one token and lands inside 人によって. A rule matching across a phrase break --
     * a 〜ば … 〜のに correlative, say -- goes loose, which is the honest place for it.
     *
     * **The single-particle rules are dropped, and this is the one place the rebuild takes
     * something away.** All 24 of the table's one-token [NoteSource.PARTICLE] rules -- は,
     * が, を, に, で, の, ね, よ -- name exactly what `Relations` now names one line above
     * them, so rendering both printed the same fact twice in adjacent rows. They still
     * fire, so `check_grammar_rules.py --dead` does not suddenly report two dozen dead
     * rules; they are filtered here, at the point where the duplication becomes visible.
     *
     * A particle the relation layer could not place keeps its note: [Relation.OTHER] means
     * the per-token layer had nothing to say, and the table's answer is better than none.
     */
    private fun fileUnderPhrases(
        notes: List<GrammarNote>,
        phrases: List<Phrase>,
    ): List<GrammarNote> = notes.mapNotNull { note ->
        val owner = phrases.firstOrNull { note.from >= it.from && note.to <= it.to }
        if (owner != null && supersededByRelation(note, owner)) return@mapNotNull null
        if (owner == null) note else note.copy(phraseId = owner.id)
    }

    private fun supersededByRelation(note: GrammarNote, owner: Phrase): Boolean {
        if (note.source != NoteSource.PARTICLE || note.from != note.to) return false
        val component = owner.components.firstOrNull { it.token.position == note.from }
        return component != null && component.relation != Relation.OTHER
    }

    /**
     * Fold a model's per-token explanations into an offline breakdown.
     *
     * The model is asked about **tokens by index**, not about fragments it has to copy
     * back, so a returned explanation lands on exactly the component it was written for.
     * The previous version matched on `text.indexOf(fragment)` and could only order the
     * notes with the result; this can replace prose in place.
     *
     * Everything the model did not speak about keeps its template. That is the whole
     * bargain of the offline layer: a configured translator makes the section better and
     * its absence never makes it empty.
     *
     * @param explanations token position -> the model's sentence about that token
     * @param heads phrase id -> a corrected [Phrase.head], for the ones it disagreed with
     */
    fun merge(
        breakdown: GrammarBreakdown,
        explanations: Map<Int, String>,
        heads: Map<Int, Int> = emptyMap(),
        modelId: String = "",
    ): GrammarBreakdown {
        if (explanations.isEmpty() && heads.isEmpty()) return breakdown

        val phrases = breakdown.phrases.map { phrase ->
            val head = heads[phrase.id]?.takeIf { valid(it, phrase.id, breakdown.phrases.size) }
            phrase.copy(
                head = head ?: phrase.head,
                components = phrase.components.map { component ->
                    val written = explanations[component.token.position]
                    if (written.isNullOrBlank()) {
                        component
                    } else {
                        component.copy(
                            explanation = written.trim(),
                            fromModel = true,
                            modelId = modelId,
                        )
                    }
                },
            )
        }
        return breakdown.copy(phrases = phrases)
    }

    /**
     * Whether a model-supplied head is one the renderer can survive.
     *
     * Rightward and in range, or the root. A model that answers `{"p":3,"head":0}` has
     * proposed a leftward edge, which the whole layer is built on not having -- accepting
     * it could produce a cycle and hang the "dim everything but this and its head" walk.
     * Rejecting a bad edge costs one correction; accepting one costs the section.
     */
    private fun valid(head: Int, id: Int, count: Int): Boolean =
        head == -1 || (head in (id + 1) until count)
}

/** Convenience for the sheet: the components of every phrase, flat, in reading order. */
val GrammarBreakdown.components: List<Component>
    get() = phrases.flatMap { it.components }
