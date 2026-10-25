package com.mangareader.analysis

import com.mangareader.model.SfxMatch
import org.json.JSONObject

/**
 * Section 3.4's bundled onomatopoeia glossary, ported from
 * `harness/pipeline/analyse.py::SfxDictionary`.
 *
 * Section 2.6 no longer routes on region class: [SfxClassifier] decides what is
 * onomatopoeia from the recognized text, and this glossary is one of its inputs as well
 * as the source of the card the sheet shows. A region that misses here is still
 * translated -- the gloss is a bonus on top, not the only thing standing between the
 * reader and the text.
 *
 * Matching is deliberately loose, in four widening steps, because SFX lettering is
 * drawn rather than typeset: exact string, then the normalized fold (katakana to
 * hiragana, long marks and small tsu and spacing stripped), then the doubled or halved
 * form -- manga writes パカ and パカパカ interchangeably -- then the longest dictionary
 * key the recognized string starts with, so a stretched or trailed-off rendering still
 * resolves. `SfxMatch.match` records which step fired, and the sheet shows it: an
 * inexact match should look inexact.
 *
 * The source is `tools/sfx_dict.json`, copied into the APK's assets by the `:app`
 * build. 24 KB, so unlike JMdict there is nothing to weigh up.
 */
class SfxDictionary private constructor(
    // Not `entries`: this is used as the receiver of a buildMap below, where that name
    // resolves to the map being built instead.
    private val glossary: List<Entry>,
    val meta: Map<String, String>,
) {
    data class Entry(val text: String, val reading: String, val kind: String, val gloss: String)

    private val byNormalized: Map<String, Entry> = buildMap {
        glossary.forEach { entry ->
            // setdefault: the first entry to claim a normalized key keeps it.
            putIfAbsent(normalize(entry.text), entry)
            putIfAbsent(normalize(entry.reading), entry)
        }
    }

    val size: Int get() = glossary.size

    /**
     * The exact, normalized and repeat steps -- the ones that mean something.
     *
     * Split out from [lookup] because the match is no longer only cosmetic: it is an
     * input to [SfxClassifier.classify], which decides whether a region is onomatopoeia
     * at all. The prefix step below is a display convenience that guesses, and a guess
     * must not be allowed to vote on the classification that produced it.
     */
    fun lookupConfident(text: String): SfxMatch? {
        if (text.isEmpty()) return null
        glossary.firstOrNull { it.text == text }?.let { return it.asMatch("exact") }

        val key = normalize(text)
        if (key.isEmpty()) return null
        byNormalized[key]?.let { return it.asMatch("normalized") }

        // パカ <-> パカパカ: manga doubles or halves these freely.
        byNormalized[key + key]?.let { return it.asMatch("repeat") }
        val half = key.length / 2
        if (half > 0 && key.take(half) == key.substring(half)) {
            byNormalized[key.take(half)]?.let { return it.asMatch("repeat") }
        }
        return null
    }

    /**
     * [lookupConfident], then the prefix widening. What the sheet displays.
     *
     * Only ever reached for a region [SfxClassifier] has already ruled SFX, so the
     * prefix step can no longer put a confident-looking gloss on a sentence that merely
     * opens with a two-mora run -- ざわざわしてきた is classified as dialogue and never
     * gets here. The length guard is the second belt: a stretched or trailed-off
     * rendering is at most a few times its root, and a sentence is not.
     */
    fun lookup(text: String): SfxMatch? {
        lookupConfident(text)?.let { return it }

        val key = normalize(text)
        if (key.isEmpty()) return null
        return byNormalized.keys
            .filter { it.length >= 2 && key.startsWith(it) && key.length <= it.length * 3 }
            .maxByOrNull { it.length }
            ?.let { byNormalized.getValue(it).asMatch("prefix") }
    }

    private fun Entry.asMatch(how: String) =
        SfxMatch(text = text, reading = reading, kind = kind, gloss = gloss, match = how)

    companion object {
        /** The decorative variation SFX lettering carries, folded away. */
        private const val STRIPPED = "ーっー〜~・･ 　!！?？.。、,"

        fun normalize(text: String): String =
            Kana.toHiragana(text).filterNot { it in STRIPPED }

        fun parse(json: String): SfxDictionary {
            val root = JSONObject(json)
            val array = root.getJSONArray("entries")
            val entries = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                Entry(
                    text = item.getString("text"),
                    reading = item.getString("reading"),
                    kind = item.getString("kind"),
                    gloss = item.getString("gloss"),
                )
            }
            val metaJson = root.optJSONObject("_meta")
            val meta = metaJson?.keys()?.asSequence()
                ?.associateWith { metaJson.getString(it) }.orEmpty()
            return SfxDictionary(entries, meta)
        }
    }
}
