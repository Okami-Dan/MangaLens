package com.mangareader.analysis

import com.mangareader.model.Ruby

/**
 * Section 6.2's alignment routine, ported from `harness/pipeline/analyse.py`.
 *
 * Furigana is *derived*, not looked up: the tokenizer hands back a reading for the
 * whole surface form, and the kana that appear in the surface are matched off against
 * the head and tail of that reading, leaving the middle to the kanji. That is what
 * handles okurigana -- 見る reads みる, the trailing る matches, and 見 is left with み.
 *
 * Where alignment fails the whole surface takes the whole reading. Section 6.2's rule:
 * wrong-but-visible beats silently dropping the aid.
 */
object Furigana {

    /** Consecutive (text, isKanjiRun) chunks of a surface form. */
    private fun runs(surface: String): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        surface.forEach { char ->
            val kanji = Kana.isKanji(char)
            val last = out.lastOrNull()
            if (last != null && last.second == kanji) {
                out[out.size - 1] = (last.first + char) to kanji
            } else {
                out += char.toString() to kanji
            }
        }
        return out
    }

    /** Returns segments in surface order; [Ruby.ruby] is empty where none is needed. */
    fun align(surface: String, reading: String): List<Ruby> {
        if (reading.isEmpty() || surface.none { Kana.isKanji(it) }) {
            return listOf(Ruby(surface, ""))
        }
        if (surface == reading) return listOf(Ruby(surface, ""))

        val whole = listOf(Ruby(surface, reading))
        val chunks = runs(surface)
        val segments = mutableListOf<Ruby>()
        var rest = reading

        chunks.forEachIndexed { index, (text, isKanjiRun) ->
            if (!isKanjiRun) {
                val kana = Kana.toHiragana(text)
                if (!rest.startsWith(kana)) return whole
                rest = rest.substring(kana.length)
                segments += Ruby(text, "")
                return@forEachIndexed
            }

            // A kanji run owns the reading up to wherever the following kana run starts.
            val following = chunks.getOrNull(index + 1)?.first.orEmpty()
            if (following.isNotEmpty()) {
                // From index 1: a kanji run always reads as at least one mora, so the
                // separator cannot begin where the run's own reading does.
                val cut = rest.indexOf(Kana.toHiragana(following), 1)
                if (cut < 0) return whole
                segments += Ruby(text, rest.substring(0, cut))
                rest = rest.substring(cut)
            } else {
                if (rest.isEmpty()) return whole
                segments += Ruby(text, rest)
                rest = ""
            }
        }

        return if (rest.isEmpty()) segments else whole
    }
}
