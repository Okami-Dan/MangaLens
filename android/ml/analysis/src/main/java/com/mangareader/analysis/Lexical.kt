package com.mangareader.analysis

import com.mangareader.model.LexicalUnit
import com.mangareader.model.Token

/** Bounded word grouping. Morphology remains available underneath every word. */
object Lexical {
    private data class Colloquial(val lemma: String, val pos: String, val reading: String)
    private val colloquial = mapOf(
        "なーんか" to Colloquial("なんか", "副詞", "なーんか"),
        "なんか" to Colloquial("なんか", "副詞", "なんか"),
        "もしもーし" to Colloquial("もしもし", "感動詞", "もしもーし"),
        "もしもし" to Colloquial("もしもし", "感動詞", "もしもし"),
    )

    fun group(
        text: String,
        tokens: List<Token>,
        lookup: ((String, String, String) -> List<Jmdict.Entry>)? = null,
    ): List<LexicalUnit> {
        val out = mutableListOf<LexicalUnit>()
        var i = 0
        while (i < tokens.size) {
            val first = tokens[i]
            var last = i
            var lemma = first.baseForm
            var pos = first.partOfSpeech
            var reading: String? = null
            var repairedStemReading: Pair<Int, String>? = null
            val inflections = mutableListOf<String>()
            var entry = Jmdict.Entry(first.jmdictEntryId, first.glosses, first.baseReading)

            // Explicitly supported spellings only: never strip arbitrary kana and guess.
            val special = (i..minOf(i + 5, tokens.lastIndex)).firstNotNullOfOrNull { end ->
                val surface = tokens.subList(i, end + 1).joinToString("") { it.surface }
                if (contiguous(tokens.subList(i, end + 1))) colloquial[surface]?.let { end to it } else null
            }
            if (special != null) {
                last = special.first
                lemma = special.second.lemma
                pos = special.second.pos
                reading = if (tokens.subList(i, last + 1).any { it.readingIsUser }) null else special.second.reading
                entry = lookup?.invoke(lemma, "", pos)?.firstOrNull() ?: Jmdict.Entry.NONE
                inflections += "colloquial spelling of $lemma"
            } else {
                // A split prefix + continuative stem can hide a dictionary verb (御/し).
                // Only accept a unique, exact lemma with the matching conjugation class.
                if (lookup != null && first.head == "接頭詞") {
                    val next = tokens.getOrNull(i + 1)
                    if (next?.head == "動詞" && contiguous(listOf(first, next)) && next.surface.endsWith("し")) {
                        val stem = first.surface + next.surface
                        val hits = lookup(stem.dropLast(1) + "す", "", "動詞")
                            .filter { hit -> hit.glosses.any { "v5s" in it.pos.split(",") } }
                        if (hits.size == 1) {
                            last = i + 1; lemma = stem.dropLast(1) + "す"; entry = hits.single()
                            pos = "動詞,自立,*,*"
                            if (entry.baseReading.endsWith("す")) repairedStemReading =
                                last to (entry.baseReading.dropLast(1) + "し")
                        }
                    }
                }
                // Exact dictionary compounds; no crossing particles, punctuation or gaps.
                if (lookup != null && first.head == "名詞") {
                    for (end in i + 1..minOf(i + 3, tokens.lastIndex)) {
                        val span = tokens.subList(i, end + 1)
                        if (span.any { it.head != "名詞" } || !contiguous(span)) break
                        val form = span.joinToString("") { it.surface }
                        val hits = lookup(form, span.joinToString("") { it.reading }, "名詞")
                        // Retain the morphology when equally plausible exact entries remain.
                        val hit = hits.firstOrNull() ?: continue
                        if (hits.getOrNull(1)?.rank == hit.rank) continue
                        if (hit.id != null) {
                            last = end; lemma = form; entry = hit
                            pos = "名詞,一般,*,*"
                        }
                    }
                }
                val suru = first.partOfSpeech.startsWith("名詞,サ変接続") &&
                    tokens.getOrNull(last + 1)?.baseForm == "する"
                if (suru) {
                    last++
                    lemma += "する"
                    pos = "動詞,自立,*,*"
                    entry = lookup?.invoke(lemma, "", pos)?.firstOrNull() ?: entry
                    inflections += "suru-verb"
                }
                if (pos.substringBefore(",") in setOf("動詞", "形容詞") || suru) {
                    while (last + 1 < tokens.size && last - i < 8) {
                        val next = tokens[last + 1]
                        val auxiliary = next.head == "助動詞" ||
                            next.partOfSpeech.startsWith("動詞,接尾") ||
                            next.partOfSpeech.startsWith("動詞,非自立") ||
                            next.partOfSpeech.startsWith("形容詞,非自立")
                        val te = next.head == "助詞" && next.surface in setOf("て", "で") &&
                            tokens.getOrNull(last + 2)?.let {
                                it.partOfSpeech.startsWith("動詞,非自立")
                            } == true
                        val sou = next.surface == "そう" && next.head == "名詞" &&
                            tokens.getOrNull(last + 2)?.baseForm in setOf("だ", "です")
                        if ((!auxiliary && !te && !sou) || !contiguous(listOf(tokens[last], next))) break
                        last++
                        inflection(next)?.let { inflections += it }
                    }
                    if (first.conjugationForm.startsWith("命令")) inflections += "imperative"
                }
            }
            val span = tokens.subList(i, last + 1)
            repairedStemReading?.let { (stemEnd, stemReading) ->
                if (span.none { it.readingIsUser }) reading = stemReading +
                    tokens.subList(stemEnd + 1, last + 1).joinToString("") { it.reading }
            }
            val start = first.start.takeIf { it >= 0 } ?: text.indexOf(first.surface,
                out.lastOrNull()?.end ?: 0).coerceAtLeast(0)
            val end = tokens[last].end.takeIf { it >= start }
                ?: start + span.sumOf { it.surface.length }
            out += LexicalUnit(
                id = out.size, start = start, end = end, from = first.position,
                to = tokens[last].position,
                surface = text.substring(start.coerceAtMost(text.length), end.coerceAtMost(text.length)),
                lemma = lemma, reading = reading ?: span.joinToString("") { it.reading },
                lemmaReading = entry.baseReading.ifEmpty { first.baseReading.ifEmpty { first.reading } }
                    .let { if ("suru-verb" in inflections && !it.endsWith("する") && it.isNotEmpty()) it + "する" else it },
                partOfSpeech = pos, posLabel = PartOfSpeech.label(pos), entryId = entry.id,
                senses = entry.glosses, inflections = inflections.distinct(),
            )
            i = last + 1
        }
        return out
    }

    private fun contiguous(tokens: List<Token>): Boolean = tokens.zipWithNext().all { (a, b) ->
        a.end < 0 || b.start < 0 || a.end == b.start
    }

    private fun inflection(token: Token): String? = when (token.baseForm) {
        "た" -> "past / completed"
        "ない", "ぬ", "ん" -> "negative"
        "ます", "です" -> "polite"
        "れる", "られる" -> "passive / potential (context dependent)"
        "せる", "させる" -> "causative"
        "いる" -> "progressive / resulting state"
        "てる" -> "ている contracted: progressive / resulting state"
        "ちゃう", "じゃう" -> "てしまう contracted: completion / possible regret"
        "しまう" -> "completion / possible regret"
        "たい" -> "desire"
        "やす", "やすい" -> "easy to"
        "にくい" -> "difficult to"
        "そう" -> "appearance / reported information (context dependent)"
        "う", "よう" -> "volitional / conjecture"
        else -> null
    }
}
