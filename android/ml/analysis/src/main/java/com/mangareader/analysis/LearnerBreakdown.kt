package com.mangareader.analysis

import com.mangareader.model.Component
import com.mangareader.model.GrammarBreakdown
import com.mangareader.model.GrammarNote
import com.mangareader.model.LexicalUnit
import com.mangareader.model.Phrase
import com.mangareader.model.PhraseRole
import com.mangareader.model.Relation
import com.mangareader.model.Token

/** Derived UI data only. Word IDs, original tokens and persisted lexical units stay intact. */
data class LearnerEntry(
    val word: LexicalUnit?,
    val phrase: Phrase?,
    val bookmark: Token?,
    val morphology: List<Component>,
    val grammar: List<LearnerGrammar>,
)

sealed interface LearnerGrammar {
    val from: Int
    data class TokenNote(val component: Component,
        val romaji: String = Romaji.of(component.token.reading),
        val dictionaryMeanings: List<GrammarLexicon.Meaning> = emptyList(),
        val attachmentExplanation: String = component.explanation) : LearnerGrammar {
        override val from: Int get() = component.token.position
    }
    data class Pattern(val note: GrammarNote, val modelExplanations: List<Component>,
        val reading: String = "") : LearnerGrammar {
        override val from: Int get() = note.from
    }
}

object LearnerBreakdown {
    // These remain productive grammar even if a dictionary includes the full spelling.
    private val productive = setOf("っぷり", "ぶり", "たち", "達", "ら", "等", "ちゃん", "さん", "様", "さま", "君", "くん")

    fun entries(words: List<LexicalUnit>, breakdown: GrammarBreakdown,
        lexicon: GrammarLexicon = GrammarLexicon.EMPTY): List<LearnerEntry> {
        val result = mutableListOf<LearnerEntry>()
        val assigned = mutableSetOf<Int>()
        for (phrase in breakdown.phrases) {
            val local = words.filter { it.from >= phrase.from && it.to <= phrase.to }
            assigned += local.map { it.id }
            val roots = local.filter { PartOfSpeech.isIndependent(it.partOfSpeech) }
            val repaired = roots.filter { word -> word.inflections.any { it.startsWith("colloquial spelling of ") } }
            // A verified lexical repair overrides the misleading raw tags inside it.
            val notes = breakdown.notesFor(phrase.id).filterNot { note ->
                repaired.any { note.from >= it.from && note.to <= it.to }
            }
            val attached = phrase.components.filter { component ->
                repaired.none { component.token.position in it.from..it.to } &&
                    (PartOfSpeech.isAttached(component.token.partOfSpeech) ||
                        component.relation == Relation.VERBALISER)
            }
            if (roots.isEmpty()) {
                result += LearnerEntry(null, phrase, null, emptyList(),
                    grammar(attached, notes, breakdown.looseNotes, phrase.components, lexicon, phrase.role))
                continue
            }
            val componentsByWord = roots.associate { it.id to mutableListOf<Component>() }
            val notesByWord = roots.associate { it.id to mutableListOf<GrammarNote>() }
            fun owner(position: Int): LexicalUnit = roots.firstOrNull { position in it.from..it.to }
                ?: roots.lastOrNull { it.to < position } ?: roots.first()
            attached.forEach { componentsByWord.getValue(owner(it.token.position).id) += it }
            notes.forEach { notesByWord.getValue(owner(it.from).id) += it }
            for (root in roots) {
                val internal = phrase.components.filter { it.token.position in root.from..root.to }
                val suffix = internal.indexOfFirst { it.token.partOfSpeech.startsWith("名詞,接尾") &&
                    it.token.surface in productive }
                // A dictionary-grouped 俺たち must still bookmark 俺, not its plural suffix.
                val base = if (suffix == 1 && internal.first().token.position == root.from)
                    internal.first().token else root.asToken()
                val owned = componentsByWord.getValue(root.id)
                var display = if (suffix == 1) root.copy(lemma = base.baseForm,
                    lemmaReading = base.baseReading.ifEmpty { base.reading },
                    senses = base.glosses, entryId = base.jmdictEntryId) else root
                // Extend only across adjacent noun suffixes, never particles or source gaps.
                for (component in owned.sortedBy { it.token.position }) {
                    val token = component.token
                    if (token.position == display.to + 1 && token.start == display.end &&
                        token.partOfSpeech.startsWith("名詞,接尾")) {
                        display = display.copy(surface = display.surface + token.surface,
                            reading = display.reading + token.reading, to = token.position, end = token.end,
                            partOfSpeech = "名詞,一般,*,*", posLabel = "noun construction")
                    }
                }
                result += LearnerEntry(display, phrase, base,
                    internal.filterNot { it in attached },
                    grammar(owned, notesByWord.getValue(root.id), breakdown.looseNotes, phrase.components, lexicon, phrase.role))
            }
        }
        words.filter { it.id !in assigned && PartOfSpeech.isIndependent(it.partOfSpeech) }.forEach {
            result += LearnerEntry(it, null, it.asToken(), emptyList(), emptyList())
        }
        return result.sortedBy { entry -> entry.word?.from ?: entry.grammar.firstOrNull()?.from
            ?: entry.phrase?.from ?: Int.MAX_VALUE }
    }

    /** Read the matched source span, not the rule's illustrative fragment (e.g. 〜ている). */
    fun reading(note: GrammarNote, components: List<Component>): String = components
        .filter { it.token.position in note.from..note.to }
        .distinctBy { it.token.position }.sortedBy { it.token.position }
        .joinToString("") { it.token.reading }

    /** A split continuative like じゃっ needs the next syllable to retain its doubled consonant. */
    fun componentRomaji(component: Component, source: List<Component>): String {
        val token = component.token
        val plain = Romaji.of(token.reading)
        if (!Kana.toHiragana(token.reading).endsWith("っ")) return plain
        val next = source.firstOrNull { it.token.position == token.position + 1 &&
            token.end >= 0 && it.token.start == token.end }?.token?.reading.orEmpty()
        val ending = Romaji.of(next)
        if (ending.isEmpty() || ending.first() in "aiueo") return plain
        return Romaji.of(token.reading + next).removeSuffix(ending)
    }

    private fun grammar(components: List<Component>, notes: List<GrammarNote>,
        looseNotes: List<GrammarNote>, source: List<Component>, lexicon: GrammarLexicon,
        role: PhraseRole): List<LearnerGrammar> {
        val uniqueNotes = notes.distinctBy { Triple(it.from, it.to, it.explanation) }
        val lines = uniqueNotes.map { note -> LearnerGrammar.Pattern(note,
            components.filter { it.fromModel && it.token.position in note.from..note.to }, reading(note, source)) } +
            components.distinctBy { it.token.position }.filter { component -> (uniqueNotes + looseNotes).none { component.token.position in it.from..it.to } }
                .map { LearnerGrammar.TokenNote(it, componentRomaji(it, source), lexicon.meanings(it.token),
                    if (it.fromModel) Relations.offlineExplanation(it, source, role) else it.explanation) }
        return lines.sortedBy { it.from }
    }
}
