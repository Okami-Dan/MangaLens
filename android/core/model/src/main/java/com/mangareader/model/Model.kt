package com.mangareader.model

/**
 * Domain types, mirroring plan section 6.
 *
 * This is a transcription of `harness/pipeline/model.py`, which was deliberately
 * written dependency-free so the two could be compared side by side. Where section 6's
 * Kotlin sketch and the Python disagree on a name, section 6 wins (it is camelCase);
 * where the Python carries a field the sketch omits -- `score`, `panelIndex`,
 * `bubbleBox` -- the Python wins, because the pipeline actually uses them.
 *
 * Boxes are normalized 0..1 against the rectified page, exactly as section 6 specifies.
 */

/**
 * What a region is. Two stages write this, and they mean different things by it.
 *
 * Stage 3 writes only [TEXT_BUBBLE] and [TEXT_FREE], and both are pure geometry -- was
 * the detector's text box inside a bubble it also found, or drawn on the artwork. Stage
 * 6 is the *only* writer of [TEXT_SFX], and that one is a content verdict.
 *
 * The geometric fact survives the reclassification: whether a region sits inside a
 * bubble is carried by `Region.bubbleBox`, not by this enum, and always was once
 * `Detector.reclassifyContained` had run. So promoting a region to [TEXT_SFX] loses
 * nothing that anything downstream needs.
 */
enum class RegionKind {
    /** Stage 3 geometry: detector class 1, text inside a bubble. */
    TEXT_BUBBLE,

    /** Stage 3 geometry: detector class 2, text drawn on the art. */
    TEXT_FREE,

    /** Stage 6 verdict: this text reads as onomatopoeia. Never written by stage 3. */
    TEXT_SFX,
}

/**
 * What kind of page this is.
 *
 * It no longer decides how free-floating text is *routed* -- [SfxClassifier] does that
 * from the text itself -- but it is still that classifier's first veto. On a cover
 * everything is free-floating (title, author, obi band, blurb) and none of it is
 * onomatopoeia, however short and katakana a title like ビースターズ happens to look.
 */
enum class PageKind { INTERIOR, COVER }

/** Section 6's resumable-work marker. Declared in pipeline order. */
enum class PipelineStage {
    CAPTURED, RECTIFIED, DETECTED, ORDERED, OCRED, ANALYSED, TRANSLATED;

    val index: Int get() = ordinal
}

/** Normalized 0..1, left/top/right/bottom against the rectified page. */
data class Box(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val area: Float get() = maxOf(0f, right - left) * maxOf(0f, bottom - top)

    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2

    fun toPixels(width: Int, height: Int) = IntArray(4).also {
        it[0] = Math.round(left * width)
        it[1] = Math.round(top * height)
        it[2] = Math.round(right * width)
        it[3] = Math.round(bottom * height)
    }

    /** Section 4: pad each text region by ~4% before cropping; tight boxes clip strokes. */
    fun padded(fraction: Float): Box {
        val w = right - left
        val h = bottom - top
        return Box(
            maxOf(0f, left - w * fraction),
            maxOf(0f, top - h * fraction),
            minOf(1f, right + w * fraction),
            minOf(1f, bottom + h * fraction),
        )
    }

    fun contains(other: Box, tolerance: Float = 0.01f): Boolean =
        left - tolerance <= other.left &&
            top - tolerance <= other.top &&
            right + tolerance >= other.right &&
            bottom + tolerance >= other.bottom
}

/** One JMdict sense. */
data class Gloss(
    val entryId: Long,
    val pos: String,
    val misc: String,
    val text: String,
    val common: Boolean,
)

/** Section 6's Token entity plus the display fields stage 6 derives. */
data class Token(
    val position: Int,
    val surface: String,
    /** Hiragana, of the surface form as it appears on the page. */
    val reading: String,
    val baseForm: String,
    /** The tokenizer's full IPADIC tag string. */
    val partOfSpeech: String,
    /** Short English label for the UI. */
    val posLabel: String,
    /** Hiragana of the dictionary form -- what a saved word needs. */
    val baseReading: String = "",
    /** (text, ruby) segments; ruby is empty where none is needed. */
    val furigana: List<Ruby> = emptyList(),
    val romaji: String = "",
    val jmdictEntryId: Long? = null,
    val glosses: List<Gloss> = emptyList(),
    /** Drives which tokens the breakdown emphasises. */
    val isContentWord: Boolean = true,
    /**
     * IPADIC's 活用形 -- 基本形, 未然形, 連用形, 命令ｅ and the rest. Empty for a word
     * that does not inflect.
     *
     * Carried because the imperative is invisible without it. IPADIC tags 来い, やめろ and
     * 待て as plain 動詞,自立 exactly like 来る, やめる and 待つ: the part of speech does not
     * change, only this does. Manga is full of commands, and the register classifier
     * cannot see a single one of them from the tag string alone.
     */
    val conjugationForm: String = "",
    /**
     * IPADIC's 活用型 -- 五段・ラ行, 一段, 特殊・タ, サ変・スル and the rest.
     *
     * The reference JSON's `inflection.conjugation_type`. Kuromoji has always emitted it;
     * nothing read it until tier 4 needed to say *which kind* of verb 殺す is, not merely
     * that it is one. See `Inflection` in `:ml:analysis` for the English.
     */
    val conjugationType: String = "",
    val start: Int = -1,
    val end: Int = -1,
    val readingIsUser: Boolean = false,
)

/** One furigana segment: [text] with [ruby] above it, or no ruby when [ruby] is empty. */
data class Ruby(val text: String, val ruby: String)

data class SfxMatch(
    val text: String,
    val reading: String,
    val kind: String,
    val gloss: String,
    /** How it matched: exact | normalized | repeat | prefix. */
    val match: String,
)

/** Section 6's Region entity. */
data class Region(
    val id: Long,
    val kind: RegionKind,
    val box: Box,
    val score: Float,
    val readingIndex: Int = -1,
    /** Containing bubble, if any -- tap targets now, M5's inpainting later. */
    val bubbleBox: Box? = null,
    val panelIndex: Int = -1,
    val ocrText: String = "",
    val ocrTextEdited: String? = null,
    val ocrConfidence: Float? = null,
    val tokens: List<Token> = emptyList(),
    val sfx: SfxMatch? = null,
    val translation: String? = null,
    val translatorId: String? = null,
    val lexicalUnits: List<LexicalUnit> = emptyList(),
    val analysisVersion: Int = 0,
    val translationKey: String? = null,
) {
    /** User correction wins if present -- section 6. */
    val text: String get() = ocrTextEdited ?: ocrText
}

/** Section 6's Page entity, plus the per-stage timings the debug panel shows. */
data class Page(
    /** Content hash of the source image, so re-importing the same photo reuses it. */
    val id: String,
    val bookId: String = "default",
    val imagePath: String,
    /** Pre-rectification, for re-processing. */
    val rawImagePath: String? = null,
    val width: Int,
    val height: Int,
    val stage: PipelineStage = PipelineStage.CAPTURED,
    val kind: PageKind = PageKind.INTERIOR,
    /** False once the user overrides the classification. */
    val kindAuto: Boolean = true,
    /** Evidence behind the cover/interior call. */
    val colourFraction: Float = 0f,
    val rectified: Boolean = false,
    /** 4 corners as normalized [x, y] in the raw image, if a warp was applied. */
    val quad: List<PointF>? = null,
    val regions: List<Region> = emptyList(),
    val timings: Map<String, Double> = emptyMap(),
    val title: String = "",
    val capturedAt: Long = System.currentTimeMillis(),
    val notes: String = "",
) {
    val isCover: Boolean get() = kind == PageKind.COVER

    val orderedRegions: List<Region> get() = regions.sortedBy { it.readingIndex }

    fun region(regionId: Long): Region? = regions.firstOrNull { it.id == regionId }
}

/** Normalized point, 0..1. Avoids pulling android.graphics into this module. */
data class PointF(val x: Float, val y: Float)

/**
 * Section 6's Book entity.
 *
 * Kept as `Book`, with `Page.bookId` alongside it, after M5 split the app's own storage
 * into `series` + `volumes`. This module mirrors `harness/pipeline/store.py`, whose schema
 * still has one flat `book_id`, and the parity fixtures compare against it -- the harness
 * is a pipeline tool, and folders are the app's business, not the pipeline's. Renaming
 * these would break that correspondence and buy nothing.
 */
data class Book(
    val id: Long,
    val title: String,
    val createdAt: Long,
)

/** Section 6's SavedWord entity, behind section 3.3's "tapping a token pins it". */
data class SavedWord(
    val id: Long,
    val baseForm: String,
    val reading: String,
    val gloss: String,
    val sourceRegionId: Long?,
    val savedAt: Long,
)

// --------------------------------------------------------------------------------------
// Section 3.3 tier 4 -- the grammar breakdown.
//
// One tree, not two lists. The mockup and the reference JSON agree on the shape:
// sentence -> 文節 phrase -> token -> what that token is doing. Every token carries a
// [Relation] and a sentence of prose about it, and every phrase points at the phrase it
// modifies.
//
// The previous version of this section was a flat chunk strip beside a flat note list,
// joined by nothing -- the strip showed only the joined phrase text, so the tokens inside
// a chunk were invisible at this tier, and a note could not be reached from the chunk that
// contained it. The tree replaces both.
//
// All of it is derived from [Token] and bundled tables, so it is a pure function of output
// the database already holds -- see `Grammar` in `:ml:analysis`. That is what keeps the
// section filled in on a fresh install with no model, no account and no network.

/**
 * Where a note came from. The sheet badges it, because a rule firing and a language model
 * guessing are different kinds of claim and should not look alike.
 */
enum class NoteSource {
    /** A multi-token grammar pattern from the bundled table. */
    PATTERN,

    /** An inflection read off the auxiliary chain: passive, causative, negation. */
    INFLECTION,

    /** A single particle doing something worth remarking on. */
    PARTICLE,

    /** Written by a language model, on demand, for this bubble. */
    LLM,
}

/**
 * One multi-token grammar point: 〜によって, 〜なきゃ, 〜てない.
 *
 * [from] and [to] are inclusive [Token.position] bounds. [phraseId] is the [Phrase.id]
 * whose span contains them, or -1 for a pattern that straddles a phrase boundary -- those
 * render in a short list below the per-phrase blocks, because there is no single phrase
 * they belong under.
 *
 * These survive the redesign for a reason worth stating: a pattern is not the sum of its
 * tokens. 〜なきゃ explained token by token says "negative" and "conditional" and misses
 * that the whole thing means *have to*. The per-token layer cannot produce that, and the
 * table already has 98 of them with their JLPT levels measured.
 */
data class GrammarNote(
    /** The Japanese this is about, bolded in the sheet: "〜によって". */
    val fragment: String,
    val from: Int,
    val to: Int,
    /** A short English equivalent, where the point has one. Often empty. */
    val gloss: String = "",
    val explanation: String,
    val source: NoteSource,
    /** "N3" where the point is on a JLPT list, "" otherwise. */
    val jlpt: String = "",
    /** Set only when [source] is [NoteSource.LLM]. */
    val modelId: String = "",
    /** The [Phrase.id] this sits inside, or -1 when it crosses a phrase boundary. */
    val phraseId: Int = -1,
)

/**
 * How politely the speaker is talking, which is the axis manga dialogue varies most on
 * and the one section 7's prompt already claims to preserve ("casual stays casual, keigo
 * stays polite") without the UI ever saying what it preserved.
 *
 * Declared weakest-first so ordinal comparison resolves a bubble that mixes them: a humble
 * verb inside a です sentence is [HUMBLE], not [POLITE].
 */
enum class RegisterLevel {
    /** No predicate at all -- a bare noun, a name, a shout. Nothing to read. */
    NEUTRAL,
    CASUAL,
    ROUGH,
    ARCHAIC,
    POLITE,
    HUMBLE,
    HONORIFIC,
}

/**
 * The register verdict for one region, plus the surfaces that decided it.
 *
 * [note] is the sentence explaining the level -- "Plain forms, with no politeness marker
 * anywhere." It used to be a [GrammarNote] pinned to the top of the note list, which was
 * the only way to say it back when this section was a list. It is a fact about the whole
 * sentence rather than about a span of it, so it rides here now and renders in the
 * metadata line.
 */
data class Register(
    val level: RegisterLevel,
    val evidence: List<String> = emptyList(),
    val note: String = "",
)

/**
 * What the sentence is doing as a whole. The reference JSON's `metadata.sentence_type`.
 *
 * Read off the final tokens rather than off punctuation, because manga punctuates
 * erratically -- 殺したの…？ and 殺したの are the same question and only one of them says
 * so with a mark.
 */
enum class SentenceType(val label: String) {
    STATEMENT("Statement"),
    QUESTION("Question"),
    COMMAND("Command"),
    EXCLAMATION("Exclamation"),

    /** No predicate: a name, a noun in quotation marks, a shout. */
    FRAGMENT("Fragment"),
}

/** The reference JSON's `metadata.clause_structure`. */
enum class ClauseStructure(val label: String) {
    SIMPLE("Simple"),

    /** Two predicates joined by a connective: 〜て, 〜けど, 〜ながら. */
    COMPOUND("Compound"),

    /** A clause modifying a noun inside another clause: 見る人. */
    COMPLEX("Complex"),

    FRAGMENT("Fragment"),
}

/**
 * What a [Phrase] is doing in the sentence.
 *
 * Read off the trailing particle where there is one, off the head word class where there
 * is not. [ADVERBIAL] and [ADNOMINAL] were one `MODIFIER` in the previous version, which
 * conflated the two things a modifier can attach to: 色々 modifies the verb, 見る in 見る人
 * modifies the noun, and the difference decides where the phrase's dependency edge goes.
 */
enum class PhraseRole(val label: String) {
    TOPIC("Topic"),
    SUBJECT("Subject"),
    OBJECT("Object"),

    /** Modifies a predicate: an adverb, or a phrase ending in に / で / から / まで. */
    ADVERBIAL("Adverbial"),

    /** Modifies a noun: の, or an attributive verb or adjective. */
    ADNOMINAL("Adnominal"),

    /** Ends in 〜て / 〜けど / 〜ながら and links to a following clause. */
    CONNECTIVE("Connective"),

    PREDICATE("Predicate"),
    INTERJECTION("Interjection"),
    NOMINAL("Nominal fragment"),
    OTHER("Other"),
}

/**
 * What one token contributes to its phrase. The reference JSON's `grammatical_relation`.
 *
 * Two families, and the split matters when reading the list. The *semantic* roles --
 * [AGENT], [PATIENT], [TOPIC] -- belong to content words and say who is doing what to
 * whom. The *marker* relations belong to particles and auxiliaries and say how the
 * grammar is wired: which noun is the subject, what tense the verb is in, whether the
 * sentence is a question.
 */
enum class Relation(val label: String) {
    // Semantic roles, on content words.
    AGENT("Agent"),
    PATIENT("Patient"),
    TOPIC("Topic"),
    LOCATION("Location"),
    BASIS("Basis"),
    ACTION_CORE("Action core"),
    MODIFIER("Modifier"),

    // Case and binding, on particles. **Every particle gets a marker relation and no
    // content word does.** A noun in an adverbial phrase is the [LOCATION] or the [BASIS];
    // the particle after it is the thing that says so. Giving both the same relation --
    // which the first draft did -- printed "Basis. What the rest of the clause depends on."
    // twice under 人によって and told the reader nothing about either half.
    SUBJECT_MARKER("Subject marker"),
    OBJECT_MARKER("Object marker"),
    TOPIC_MARKER("Topic marker"),
    DIRECTION_MARKER("Direction marker"),
    LOCATION_MARKER("Location marker"),
    BASIS_MARKER("Basis marker"),
    POSSESSIVE("Possessive"),

    // Predicate machinery, on auxiliaries.
    PAST_TENSE("Past tense"),
    NEGATION("Negation"),
    POLITENESS("Politeness"),
    PASSIVE("Passive"),
    CAUSATIVE("Causative"),
    /** 〜ている, 〜てある, 〜ておく: a dependent verb carrying aspect, not a second action. */
    ASPECT("Aspect"),
    /** The する of a suru-compound, which verbalises the noun rather than acting itself. */
    VERBALISER("Verbaliser"),
    DESIRE("Desire"),
    CONJECTURE("Conjecture"),
    COPULA("Copula"),

    // Sentence shape, on the tail.
    CONNECTIVE("Connective"),
    QUESTION("Question"),
    SENTENCE_ENDING("Sentence ending"),
    EMPHASIS("Emphasis"),

    // Word formation.
    PREFIX("Prefix"),
    SUFFIX("Suffix"),
    HEDGE("Hedge"),
    PUNCTUATION("Punctuation"),
    NOMINAL_CORE("Nominal core"),
    OTHER("Other"),
}

/**
 * One token inside a phrase, with what it is doing there.
 *
 * Holds the [Token] itself rather than an index into the region's list. The breakdown is
 * recomputed every time the sheet opens and is never persisted (see `Grammar`), so there
 * is no denormalisation to regret and the UI does not have to carry a second list around
 * to resolve against.
 *
 * [explanation] starts as a template from the bundled table -- general, correct, and
 * about the construction rather than about this line. "Explain in depth" overwrites it
 * with the model's sentence about *this* token where the model wrote one.
 */
data class Component(
    val token: Token,
    val relation: Relation,
    val explanation: String,
    /** True once a model has replaced [explanation]; the sheet badges those. */
    val fromModel: Boolean = false,
    /** Which model wrote it, when [fromModel]. */
    val modelId: String = "",
)

/**
 * One 文節: an independent word plus whatever attached to it, and what each of those is
 * doing.
 *
 * [head] is the reference JSON's `target_dependency` -- the index of the phrase this one
 * modifies, or -1 for the root. See `Dependency` in `:ml:analysis` for how it is derived
 * and for what it is honestly worth: a right-attachment rule over a head-final language,
 * not a parse.
 */
data class Phrase(
    /** Index in the sentence. The JSON's "b1" is id 0. */
    val id: Int,
    val text: String,
    val role: PhraseRole,
    /** Inclusive [Token.position] bounds. */
    val from: Int,
    val to: Int,
    /**
     * IPADIC head of the phrase's 自立語 -- 名詞, 動詞, 副詞 and so on. What the card's
     * colour is keyed on.
     *
     * **Corrected for suru-compounds**, and that correction is the whole reason this is a
     * field rather than something the UI derives. The head token of 統一されてないけど is
     * 統一, which IPADIC tags 名詞,サ変接続 -- a noun. Labelling the sentence's main verb
     * "noun" would be worse than labelling it nothing, so a サ変接続 noun that has absorbed
     * a する reports 動詞.
     */
    val pos: String = "",
    /**
     * The phrase's tokens' romaji, joined. Rendered only when section 2.3's toggle is on.
     *
     * Joined with **no** separator, unlike tier 1, which spaces every token. That is not
     * an inconsistency: tier 1 spaces at token boundaries because token boundaries are all
     * it knows, and a 文節 is one prosodic word. Spacing inside one gives "yoba re te te"
     * for 呼ばれてて, which is not a reading of anything.
     */
    val romaji: String = "",
    /** [id] of the phrase this modifies, or -1 for the root. */
    val head: Int = -1,
    val components: List<Component> = emptyList(),
)

/** Section 3.3 tier 4, whole. */
data class GrammarBreakdown(
    val register: Register,
    val sentenceType: SentenceType,
    val clauseStructure: ClauseStructure,
    val phrases: List<Phrase>,
    /** Multi-token patterns, attached to a phrase by [GrammarNote.phraseId] where they fit. */
    val notes: List<GrammarNote>,
) {
    val isEmpty: Boolean get() = phrases.isEmpty() && notes.isEmpty()

    /** The patterns that straddle a phrase boundary, for the list below the blocks. */
    val looseNotes: List<GrammarNote> get() = notes.filter { it.phraseId < 0 }

    fun notesFor(phraseId: Int): List<GrammarNote> = notes.filter { it.phraseId == phraseId }

    companion object {
        val NONE = GrammarBreakdown(
            register = Register(RegisterLevel.NEUTRAL),
            sentenceType = SentenceType.FRAGMENT,
            clauseStructure = ClauseStructure.FRAGMENT,
            phrases = emptyList(),
            notes = emptyList(),
        )
    }
}

/**
 * What one "Explain in depth" call came back with.
 *
 * [explanations] is keyed by [Token.position] and [heads] by [Phrase.id] -- the model is
 * asked about numbered tokens and answers with the numbers it was given, so nothing has to
 * be matched back by text. The previous shape asked it to copy the Japanese fragment back
 * and recovered a position with `indexOf`, which resolved to nothing whenever the model
 * paraphrased and could never do better than order the notes.
 *
 * Lives here rather than in `:ml:translate` because three modules need it: translate
 * produces it, database caches it, and app folds it into the breakdown. Neither of the
 * other two can depend on translate.
 */
data class GrammarAnswer(
    val explanations: Map<Int, String>,
    val heads: Map<Int, Int> = emptyMap(),
    val meanings: Map<Int, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = explanations.isEmpty() && heads.isEmpty() && meanings.isEmpty()

    companion object {
        val NONE = GrammarAnswer(emptyMap(), emptyMap())
    }
}
