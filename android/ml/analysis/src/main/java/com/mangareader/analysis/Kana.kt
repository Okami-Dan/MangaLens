package com.mangareader.analysis

/**
 * Character classes and kana normalisation, ported from `harness/pipeline/analyse.py`.
 *
 * The Python leans on `jaconv` for the fold: `kata2hira(h2z(text, kana=True))`, i.e.
 * half-width katakana to full-width first, then katakana to hiragana. Both halves are
 * reimplemented here rather than pulled in as a dependency -- it is two tables and a
 * subtraction, and a mismatch between the two implementations would show up as wrong
 * furigana on the phone and nowhere else.
 *
 * The exact edges `jaconv` has, which this matches:
 *  - `ー` (U+30FC) is left alone; it is not a katakana letter.
 *  - `ヽヾ` fold to `ゝゞ`.
 *  - `ヷヸヹヺ` (U+30F7..U+30FA) have no hiragana form and are left alone.
 *  - a half-width `ﾜﾞ`/`ｦﾞ` does *not* compose; the voicing mark stays as its own
 *    character, as it does in `h2z`.
 */
object Kana {

    /** Section 6.2's kanji test. Iteration marks count -- 人々 must take ruby. */
    private const val ITERATION_MARKS = "々ゝゞヽヾ"

    private val KANJI_RANGES = arrayOf(
        0x4E00 to 0x9FFF,
        0x3400 to 0x4DBF,
        0xF900 to 0xFAFF,
    )

    fun isKanji(char: Char): Boolean {
        if (char in ITERATION_MARKS) return true
        val code = char.code
        return KANJI_RANGES.any { (low, high) -> code in low..high }
    }

    /** The Python's `"぀" <= char <= "ヿ" or char == "ー"`, which is U+3040..U+30FF. */
    fun isKana(char: Char): Boolean = char.code in 0x3040..0x30FF

    /** Half-width katakana and punctuation, U+FF61..U+FF9D, in code point order. */
    private const val HALFWIDTH = "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテト" +
        "ナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン"

    /** Bases that take U+FF9E (dakuten); `ｳﾞ` composes to ヴ, `ﾜﾞ`/`ｦﾞ` do not. */
    private const val VOICEABLE = "カキクケコサシスセソタチツテトハヒフヘホウ"
    private const val SEMI_VOICEABLE = "ハヒフヘホ"

    private const val HALFWIDTH_VOICED = '\uFF9E'
    private const val HALFWIDTH_SEMI_VOICED = '\uFF9F'

    /** `jaconv.h2z(text, kana=True, ascii=False, digit=False)`. */
    fun toFullWidth(text: String): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            val wide = when (char.code) {
                in 0xFF61..0xFF9D -> HALFWIDTH[char.code - 0xFF61]
                else -> char
            }
            val next = text.getOrNull(index + 1)
            when {
                next == HALFWIDTH_VOICED && wide in VOICEABLE -> {
                    // ウ voices to ヴ, which is not simply +1 the way the rows are.
                    out.append(if (wide == 'ウ') 'ヴ' else wide + 1)
                    index += 2
                }

                next == HALFWIDTH_SEMI_VOICED && wide in SEMI_VOICEABLE -> {
                    out.append(wide + 2)
                    index += 2
                }

                else -> {
                    out.append(wide)
                    index += 1
                }
            }
        }
        return out.toString()
    }

    /** `jaconv.kata2hira`, over already-full-width text. */
    private fun katakanaToHiragana(text: String): String = buildString(text.length) {
        text.forEach { char ->
            append(
                when (char.code) {
                    // ヷヸヹヺ (U+30F7..U+30FA), ・ and ー have no hiragana form.
                    in 0x30A1..0x30F6 -> char - 0x60
                    0x30FD, 0x30FE -> char - 0x60   // ヽヾ -> ゝゞ
                    else -> char
                },
            )
        }
    }

    /** The Python's `to_hiragana`. */
    fun toHiragana(text: String): String = katakanaToHiragana(toFullWidth(text))

    /**
     * The Python's `kana_reading`: the reading a kana surface already spells out.
     *
     * IPADIC carries no reading for a word it has never seen -- Kuromoji returns `*`,
     * janome the same -- and the token used to lose its reading, its romaji and its
     * furigana together. For a kanji surface nothing can be done; the reading is the
     * part that is missing from the page. For a kana one the reading *is* on the page,
     * and dropping it was pure loss: ツガイ came off the device with no romaji at all,
     * and romaji is most useful on exactly the katakana a learner cannot sound out.
     *
     * Only for surfaces that are entirely kana once half-width forms are folded.
     * Anything with a kanji in it keeps an empty reading: section 6.2's
     * "wrong-but-visible beats silently dropping the aid" licenses a *misaligned* real
     * reading, not an invented one.
     */
    fun kanaReading(surface: String): String {
        val folded = toHiragana(surface)
        return if (folded.isNotEmpty() && folded.all(::isKana)) folded else ""
    }
}
