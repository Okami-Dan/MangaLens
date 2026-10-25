package com.mangareader.analysis

import com.atilika.kuromoji.ipadic.Tokenizer
import com.mangareader.model.PageKind
import com.mangareader.model.Region
import com.mangareader.model.RegionKind
import com.mangareader.model.SfxMatch
import com.mangareader.model.Token
import com.mangareader.model.ANALYSIS_VERSION
import com.mangareader.model.SentenceAnalysis
import java.io.Closeable
import java.lang.ref.SoftReference

/**
 * Stage 6. No network, no model -- section 4's heading for it, and the whole point of
 * M3: this is the layer section 2.4 calls the core feature, and it is what makes the
 * first genuinely useful build a fully offline one.
 *
 * Ported from `harness/pipeline/analyse.py::Analyser`. The tokenizer differs by name
 * only: the harness runs janome, this runs Kuromoji, and both ship **IPADIC**, which is
 * what keeps segmentation -- and therefore furigana and every dictionary lookup
 * downstream of it -- comparable between the desktop reference and the phone.
 *
 * The dictionary is optional and the tokenizer is not. That asymmetry is deliberate:
 * Kuromoji is in the APK, so readings, furigana, romaji and parts of speech work on a
 * fresh install with nothing downloaded, and only the glosses wait on the 53 MB JMdict
 * asset being unpacked. A missing dictionary costs tier 2 its meanings, not its rows.
 */
class Analyser(
    private val dictionary: Jmdict?,
    private val sfx: SfxDictionary,
) : Closeable {

    private val tokenizer: Tokenizer get() = shared()

    val hasDictionary: Boolean get() = dictionary != null

    fun tokenize(text: String): List<Token> =
        tokenizer.tokenize(text).mapIndexed { position, token ->
            val pos = listOf(
                token.partOfSpeechLevel1,
                token.partOfSpeechLevel2,
                token.partOfSpeechLevel3,
                token.partOfSpeechLevel4,
            ).joinToString(",")
            val head = pos.substringBefore(",")
            val known = token.reading
                ?.takeIf { it.isNotEmpty() && it != "*" }
                ?.let(Kana::toHiragana)
                .orEmpty()
            // A word IPADIC does not know has no reading at all, and an unknown
            // katakana run -- a name, a loanword, drawn lettering -- is spelling its
            // own. See [Kana.kanaReading]; without it the whole reading row and the
            // romaji under it were simply dropped for those.
            val reading = known.ifEmpty { Kana.kanaReading(token.surface) }
            val baseForm = token.baseForm
                ?.takeIf { it.isNotEmpty() && it != "*" }
                ?: token.surface

            // Particles would otherwise drag in a headword for every の on the page;
            // 助動詞 is looked up because だ/です/ない carry real senses. See
            // PartOfSpeech.looksUp for the three content tags that are excluded anyway.
            val entry = if (PartOfSpeech.looksUp(pos)) {
                // `known`, not `reading`: the derived reading is a display aid and must
                // not widen the lookup. It exists only for a word IPADIC did not
                // recognize, where the base form is the surface and the fold is a
                // *different* spelling -- passing it would let an unknown katakana word
                // land on whichever entry happens to share its sound, the confident
                // wrong gloss PartOfSpeech.NO_LOOKUP already exists to prevent.
                dictionary?.lookup(baseForm, known, pos) ?: Jmdict.Entry.NONE
            } else {
                Jmdict.Entry.NONE
            }

            Token(
                position = position,
                surface = token.surface,
                reading = reading,
                baseForm = baseForm,
                baseReading = entry.baseReading.ifEmpty { reading },
                partOfSpeech = pos,
                posLabel = PartOfSpeech.label(pos),
                furigana = Furigana.align(token.surface, reading),
                romaji = if (reading.isNotEmpty()) Romaji.of(reading) else "",
                jmdictEntryId = entry.id,
                glosses = entry.glosses,
                isContentWord = head in PartOfSpeech.CONTENT,
                // Not part of the harness parity contract -- `harness/pipeline/model.py`
                // has no such field and the fixture does not carry one. It is here for
                // section 3.3 tier 4, which cannot see an imperative without it.
                conjugationForm = token.conjugationForm
                    ?.takeIf { it.isNotEmpty() && it != "*" }
                    .orEmpty(),
                // Emitted by Kuromoji all along and read by nothing until tier 4 needed
                // to say which *kind* of verb this is, not merely that it is one.
                conjugationType = token.conjugationType
                    ?.takeIf { it.isNotEmpty() && it != "*" }
                    .orEmpty(),
                start = token.position,
                end = token.position + token.surface.length,
            )
        }

    fun sentence(text: String, grammar: Grammar): SentenceAnalysis {
        val tokens = tokenize(text)
        val words = lexical(text, tokens)
        return SentenceAnalysis(tokens, words, grammar.analyse(tokens, words))
    }

    fun lexical(text: String, tokens: List<Token>) = Lexical.group(text, tokens,
        dictionary?.let { db -> { form, reading, pos -> db.lookupCandidates(form, reading, pos) } })

    fun analyseSfx(text: String): SfxMatch? = sfx.lookup(text)

    /** The classifier's view of one string, without committing it to a region. */
    fun classify(
        text: String,
        tokens: List<Token>,
        inBubble: Boolean,
        pageKind: PageKind = PageKind.INTERIOR,
    ): Verdict = SfxClassifier.classify(
        text = text,
        tokens = tokens,
        glossary = sfx.lookupConfident(text),
        inBubble = inBubble,
        pageKind = pageKind,
    )

    /**
     * Stage 6 for one region, and the seam section 3.3's Tier 1 edit re-runs.
     *
     * Tokenize first, classify second. The tokenizer output *is* the evidence
     * [SfxClassifier] weighs -- an inflected verb or a structural particle is the
     * clearest signal there is that a string is language rather than a sound -- so the
     * old shortcut of skipping JMdict for free-floating text would have thrown away the
     * very thing the decision needs. It costs nothing: IPADIC is already built and held
     * across the process, and a page carries a handful of short SFX strings at most.
     *
     * A region ruled SFX keeps its tokens. They are the reading aid for a katakana
     * string a learner cannot sound out, and section 3.3's tier 2 shows them with
     * furigana and romaji and no glosses, which is honest. What used to be true --
     * "tokens and SFX are mutually exclusive" -- is not true any more.
     */
    fun analyse(region: Region, pageKind: PageKind = PageKind.INTERIOR): Region {
        val text = region.text
        if (text.isEmpty()) {
            return region.copy(kind = geometry(region), tokens = emptyList(), sfx = null,
                lexicalUnits = emptyList(), analysisVersion = if (hasDictionary) ANALYSIS_VERSION else 0)
        }
        val tokens = tokenize(text)
        val words = lexical(text, tokens)
        val verdict = classify(text, tokens, region.bubbleBox != null, pageKind)
        return if (verdict.isSfx) {
            region.copy(kind = RegionKind.TEXT_SFX, tokens = tokens, sfx = analyseSfx(text),
                lexicalUnits = words, analysisVersion = if (hasDictionary) ANALYSIS_VERSION else 0)
        } else {
            region.copy(kind = geometry(region), tokens = tokens, sfx = null,
                lexicalUnits = words, analysisVersion = if (hasDictionary) ANALYSIS_VERSION else 0)
        }
    }

    /**
     * The stage-3 kind to fall back to when stage 6 says a region is not onomatopoeia.
     *
     * Re-analysis has to be able to undo a previous [RegionKind.TEXT_SFX] verdict --
     * after a Tier 1 text correction, say -- and `TEXT_SFX` overwrote whichever
     * geometric kind the detector assigned. `bubbleBox` is the surviving record of that
     * geometry, so it is what the answer is reconstructed from.
     */
    private fun geometry(region: Region): RegionKind =
        if (region.kind == RegionKind.TEXT_SFX) {
            if (region.bubbleBox != null) RegionKind.TEXT_BUBBLE else RegionKind.TEXT_FREE
        } else {
            region.kind
        }

    override fun close() {
        dictionary?.close()
    }

    companion object {
        private var cached = SoftReference<Tokenizer>(null)

        /**
         * One IPADIC across the process, held softly.
         *
         * Building it is what stage 6 costs: measured on the A73, analysing a page took
         * 2.2 s with a fresh `Tokenizer()` per page and the tokenization itself is tens
         * of milliseconds of that. The rest is reading the dictionary out of the APK and
         * assembling the FST, and doing it again for every page -- and again for every
         * one-word correction on the Tier 1 edit path, which section 3.3 wants to feel
         * immediate -- is pure waste.
         *
         * A [SoftReference] rather than a plain `val`: the built dictionary is tens of
         * megabytes of heap on a device that has just been handed a 12 MP still, and the
         * only correct answer to memory pressure here is to let it go and pay the 2 s
         * again. `Tokenizer` is immutable and documented thread-safe, so sharing it is
         * safe even when two pages are analysed at once; the worst a race does is build
         * it twice.
         */
        @Synchronized
        private fun shared(): Tokenizer = cached.get() ?: Tokenizer().also {
            cached = SoftReference(it)
        }
    }
}
