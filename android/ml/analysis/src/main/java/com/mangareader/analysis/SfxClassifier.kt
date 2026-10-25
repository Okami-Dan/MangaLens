package com.mangareader.analysis

import com.mangareader.model.PageKind
import com.mangareader.model.SfxMatch
import com.mangareader.model.Token

/** What a recognized string turned out to be. */
enum class TextClass { DIALOGUE, SFX }

/**
 * One classification, with its arithmetic shown.
 *
 * [reasons] exists so a failing test says *which* signal stopped firing rather than
 * only "expected DIALOGUE", and so the harness's debug panel can explain a verdict the
 * reader disagrees with.
 */
data class Verdict(
    val textClass: TextClass,
    val score: Float,
    val reasons: List<String>,
) {
    val isSfx: Boolean get() = textClass == TextClass.SFX
}

/**
 * Stage 6's onomatopoeia test, ported from `harness/pipeline/analyse.py::classify_text`.
 *
 * This replaces section 2.6's original premise, which was that text detected outside a
 * bubble *is* SFX. It is not. Manga draws interior monologue, narration and captions
 * straight onto the artwork constantly, and treating those as sound effects withheld
 * them from the translator and cost the reader the scene -- the bug in `ToDo.md`, and
 * two regions of the analysis fixture (`おはようございます`, `黄泉のツガイロ`) that were
 * frozen in as SFX while being nothing of the kind. Bubble-less-ness survives here as a
 * one-point tiebreak and nothing more.
 *
 * A score rather than a decision tree, because the signals disagree constantly on real
 * OCR output and a tree forces an arbitrary priority order between them. [SFX_THRESHOLD]
 * is set high on purpose: everything uncertain falls to dialogue, because a
 * mistranslated sound effect costs one silly line and an unclassified monologue costs
 * the reader the plot. That asymmetry is the same one `Detector.reclassifyContained`
 * already commits to when it promotes toward dialogue but refuses to demote.
 */
object SfxClassifier {

    const val SFX_THRESHOLD = 3f

    /**
     * IPADIC tags for tokens that are not real content in a sentence.
     *
     * Derived from a janome run rather than assumed, because the obvious guess is
     * wrong: there is no `未知語` tag in the output at all. IPADIC's unknown-word
     * handling assigns a proper-noun tag from the character type, so an unrecognized
     * katakana run comes back as `名詞,固有名詞,組織` (ゴゴゴゴゴゴ, パカ, ドドドド) or
     * `名詞,固有名詞,人名,姓` (ドン). `副詞,助詞類接続` is IPADIC's sound-adverb class
     * and is where ハッ and サッ land.
     */
    private val SOUND_TAGS = listOf(
        "名詞,固有名詞", "感動詞", "記号", "フィラー", "副詞,助詞類接続",
    )

    /**
     * Particles that carry sentence structure. A string containing one of these is
     * doing grammar, which is the one thing onomatopoeia never does.
     */
    private val PARTICLES = setOf(
        "は", "を", "に", "へ", "と", "が", "の", "も", "で", "や", "ね", "よ", "な", "か",
        "から", "まで", "より",
    )

    /**
     * Predicate auxiliaries. ます is what catches おはようございます -- the false
     * positive already frozen into the analysis fixture, where IPADIC tags ござい as
     * `助動詞` rather than a verb, so the verb veto never sees it.
     */
    private val AUXILIARIES = setOf(
        "だ", "です", "ます", "ない", "ぬ", "た", "う", "よう", "らしい",
    )

    /** Decoration that carries no weight when measuring how katakana a string is. */
    private const val DECORATION = "ーっッー〜~・･ 　!！?？.。、,…‥"

    private fun isKatakana(char: Char): Boolean = char.code in 0x30A1..0x30FA

    private fun isHiragana(char: Char): Boolean = char.code in 0x3041..0x3096

    /** Whether [key] is one or two morae repeated: ごごご, どきどき, どどどど. */
    private fun repeats(key: String): Boolean = (1..2).any { unit ->
        key.length >= unit * 2 && key.length % unit == 0 &&
            key.take(unit).repeat(key.length / unit) == key
    }

    /**
     * Decide whether one recognized string is onomatopoeia.
     *
     * [glossary] must come from [SfxDictionary.lookupConfident], never [SfxDictionary
     * .lookup] -- the prefix step guesses, and a guess must not vote on the
     * classification that produced it.
     *
     * [inBubble] is the weak tiebreak, worth a single point in each direction. It is
     * deliberately not a veto: a false-positive bubble detection must not be able to
     * drag a ドン into dialogue on its own, and a missing one must not push a line of
     * speech out of it.
     */
    fun classify(
        text: String,
        tokens: List<Token>,
        glossary: SfxMatch?,
        inBubble: Boolean,
        pageKind: PageKind,
    ): Verdict {
        // Vetoes. Any one of these and the text is language, not a sound.
        if (pageKind == PageKind.COVER) return veto("cover page")
        if (text.any(Kana::isKanji)) return veto("contains kanji")
        if (text.none(Kana::isKana)) {
            // Symbol runs and OCR garbage (＠＠＠). Neither dialogue nor a sound effect,
            // but labelling it a sound effect puts a confident glossary card on
            // nonsense, and dialogue is the honest default everywhere else here.
            return veto("no kana")
        }
        tokens.firstOrNull { it.partOfSpeech.substringBefore(",") in INFLECTING }?.let {
            return veto("inflected ${it.partOfSpeech.substringBefore(",")}: ${it.surface}")
        }

        val key = SfxDictionary.normalize(text)
        var score = 0f
        val reasons = mutableListOf<String>()
        fun add(points: Float, why: String) {
            score += points
            reasons += "$why (${if (points > 0) "+" else ""}${points.toInt()})"
        }

        // -- dialogue evidence
        if (tokens.size >= 2 &&
            tokens.any { it.partOfSpeech.startsWith("助詞") && it.surface in PARTICLES }
        ) {
            add(-3f, "structural particle")
        }
        if (tokens.any { it.partOfSpeech.startsWith("助動詞") && it.baseForm in AUXILIARIES }) {
            add(-3f, "predicate auxiliary")
        }
        if (key.length >= 7) add(-2f, "long")
        val kana = text.filter(Kana::isKana)
        if (kana.count(::isHiragana) > kana.count(::isKatakana)) {
            // Drawn SFX lettering is overwhelmingly katakana. Hiragana onomatopoeia is
            // real (ざわざわ, どきどき) but it arrives carrying a glossary hit or
            // repeated morae, which outweigh this twice over. Without the full -2,
            // single hiragana interjections -- うん, そう -- land exactly on the
            // threshold and are read as sound effects, which is the reported bug
            // pointing the other way.
            add(-2f, "mostly hiragana")
        }
        if (inBubble) add(-1f, "inside a bubble")

        // -- SFX evidence. The two script signals are gated on length: a single kana is
        // an utterance in a bubble far more often than it is lettering on the artwork.
        if (glossary != null) add(4f, "glossary ${glossary.match}")
        if (key.length >= 2 && repeats(key)) add(2f, "repeated morae")
        val body = text.filterNot { it in DECORATION }
        if (key.length >= 2 && body.isNotEmpty() && body.all(::isKatakana)) {
            add(2f, "all katakana")
        }
        if (key.length >= 2 && tokens.isNotEmpty() &&
            tokens.all { token -> SOUND_TAGS.any { token.partOfSpeech.startsWith(it) } }
        ) {
            add(2f, "no content words")
        }
        if (key.length <= 4) add(1f, "short")
        if (!inBubble) add(1f, "not in a bubble")

        val textClass = if (score >= SFX_THRESHOLD) TextClass.SFX else TextClass.DIALOGUE
        return Verdict(textClass, score, reasons)
    }

    /** An inflected verb or i-adjective is grammar, not a sound. */
    private val INFLECTING = setOf("動詞", "形容詞")

    private fun veto(why: String) =
        Verdict(TextClass.DIALOGUE, Float.NEGATIVE_INFINITY, listOf(why))
}
