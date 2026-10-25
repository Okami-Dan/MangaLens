package com.mangareader.model

/** Increment when stored lexical grouping or matching rules change. */
const val ANALYSIS_VERSION = 2

/** Offsets are UTF-16, half-open, in the unmodified recognized text. */
data class LexicalUnit(
    val id: Int,
    val start: Int,
    val end: Int,
    val from: Int,
    val to: Int,
    val surface: String,
    val lemma: String,
    val reading: String,
    val lemmaReading: String,
    val partOfSpeech: String,
    val posLabel: String,
    val entryId: Long? = null,
    val senses: List<Gloss> = emptyList(),
    val inflections: List<String> = emptyList(),
) {
    /** Reuses the existing saved-word contract; the lemma, never the inflected spelling. */
    fun asToken(): Token = Token(
        position = from, surface = surface, reading = reading, baseForm = lemma,
        baseReading = lemmaReading, partOfSpeech = partOfSpeech, posLabel = posLabel,
        jmdictEntryId = entryId, glosses = senses,
        isContentWord = partOfSpeech.substringBefore(",") in
            setOf("名詞", "動詞", "形容詞", "副詞", "連体詞", "感動詞"),
        start = start, end = end,
    )
}

data class SentenceAnalysis(
    val tokens: List<Token>,
    val words: List<LexicalUnit>,
    val grammar: GrammarBreakdown,
    val version: Int = ANALYSIS_VERSION,
)

/** Supplied metadata, not additional text to translate. */
data class TranslationContext(
    val seriesTitle: String = "",
    val japaneseTitle: String = "",
    val note: String = "",
) {
    fun promptNote(): String = listOfNotNull(
        seriesTitle.trim().takeIf { it.isNotEmpty() }?.let { "Series: $it" },
        japaneseTitle.trim().takeIf { it.isNotEmpty() }?.let { "Japanese series title: $it" },
        note.trim().takeIf { it.isNotEmpty() },
    ).joinToString("\n")
}
