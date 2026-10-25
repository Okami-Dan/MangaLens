package com.mangareader.analysis

/**
 * Section 6.4's Hepburn conversion, ported from `harness/pipeline/analyse.py`.
 *
 * Section 2.3: romaji is a settings toggle, off by default, and it sits directly under
 * a kana reading. So vowel sequences written in kana are left as written -- とうきょう
 * is "toukyou", not "Tōkyō" -- because a form that maps back to the kana one glyph at
 * a time is the more useful learning aid. The macron is used only for the katakana
 * long mark `ー`, which has no kana to map back to.
 *
 * The tables are transcribed from the Python unchanged -- keeping the two files
 * literally the same table is what `AnalysisParityTest` checks, and is worth more than
 * either file tidying its own copy.
 */
object Romaji {

    private val DIGRAPHS = mapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
        "ぢゃ" to "ja", "ぢゅ" to "ju", "ぢょ" to "jo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
        "てぃ" to "ti", "でぃ" to "di", "とぅ" to "tu", "どぅ" to "du",
        "ふぁ" to "fa", "ふぃ" to "fi", "ふぇ" to "fe", "ふぉ" to "fo",
        "うぃ" to "wi", "うぇ" to "we", "うぉ" to "wo",
        // The rest of the small-vowel combinations katakana uses to spell foreign
        // sounds. These were missing while the table's only ゔ row was keyed on the
        // *katakana* ヴぁ, which [of] can never see: it folds to hiragana first. So
        // every one of them decomposed into two syllables -- チェック was "chiekku",
        // ヴァルキュリア was "vuarukyuria" -- and once [Kana.kanaReading] gave unknown
        // katakana a reading at all, that became the common case rather than a rare one.
        "ゔぁ" to "va", "ゔぃ" to "vi", "ゔぇ" to "ve", "ゔぉ" to "vo", "ゔゅ" to "vyu",
        "しぇ" to "she", "じぇ" to "je", "ちぇ" to "che", "いぇ" to "ye",
        "つぁ" to "tsa", "つぃ" to "tsi", "つぇ" to "tse", "つぉ" to "tso",
        "てゅ" to "tyu", "でゅ" to "dyu", "ふゅ" to "fyu",
    )

    private val MONOGRAPHS = mapOf(
        "あ" to "a", "い" to "i", "う" to "u", "え" to "e", "お" to "o",
        "か" to "ka", "き" to "ki", "く" to "ku", "け" to "ke", "こ" to "ko",
        "さ" to "sa", "し" to "shi", "す" to "su", "せ" to "se", "そ" to "so",
        "た" to "ta", "ち" to "chi", "つ" to "tsu", "て" to "te", "と" to "to",
        "な" to "na", "に" to "ni", "ぬ" to "nu", "ね" to "ne", "の" to "no",
        "は" to "ha", "ひ" to "hi", "ふ" to "fu", "へ" to "he", "ほ" to "ho",
        "ま" to "ma", "み" to "mi", "む" to "mu", "め" to "me", "も" to "mo",
        "や" to "ya", "ゆ" to "yu", "よ" to "yo",
        "ら" to "ra", "り" to "ri", "る" to "ru", "れ" to "re", "ろ" to "ro",
        "わ" to "wa", "ゐ" to "i", "ゑ" to "e", "を" to "o", "ん" to "n",
        "が" to "ga", "ぎ" to "gi", "ぐ" to "gu", "げ" to "ge", "ご" to "go",
        "ざ" to "za", "じ" to "ji", "ず" to "zu", "ぜ" to "ze", "ぞ" to "zo",
        "だ" to "da", "ぢ" to "ji", "づ" to "zu", "で" to "de", "ど" to "do",
        "ば" to "ba", "び" to "bi", "ぶ" to "bu", "べ" to "be", "ぼ" to "bo",
        "ぱ" to "pa", "ぴ" to "pi", "ぷ" to "pu", "ぺ" to "pe", "ぽ" to "po",
        "ぁ" to "a", "ぃ" to "i", "ぅ" to "u", "ぇ" to "e", "ぉ" to "o",
        "ゃ" to "ya", "ゅ" to "yu", "ょ" to "yo", "ゎ" to "wa", "ゔ" to "vu",
    )

    private val LONG_VOWEL = mapOf('a' to 'ā', 'i' to 'ī', 'u' to 'ū', 'e' to 'ē', 'o' to 'ō')

    fun of(reading: String): String {
        val kana = Kana.toHiragana(reading)
        val out = mutableListOf<String>()
        var i = 0
        while (i < kana.length) {
            val pair = kana.substring(i, minOf(i + 2, kana.length))
            val digraph = DIGRAPHS[pair]
            if (digraph != null) {
                out += digraph
                i += 2
                continue
            }
            when (val char = kana[i]) {
                'っ' -> {
                    // Gemination takes the *next* syllable's initial; sh/ch double as
                    // "ssh"/"tch", which is why the c case is special-cased to t.
                    val next = kana.substring(i + 1, minOf(i + 3, kana.length))
                    val syllable = DIGRAPHS[next]
                        ?: MONOGRAPHS[kana.substring(i + 1, minOf(i + 2, kana.length))]
                        ?: ""
                    if (syllable.isNotEmpty()) {
                        out += if (syllable[0] == 'c') "t" else syllable[0].toString()
                    }
                    i += 1
                }

                'ー' -> {
                    val last = out.lastOrNull()
                    if (!last.isNullOrEmpty() && LONG_VOWEL.containsKey(last.last())) {
                        out[out.size - 1] = last.dropLast(1) + LONG_VOWEL.getValue(last.last())
                    }
                    i += 1
                }

                'ん' -> {
                    // n' before a vowel or y, so しんや is shin'ya and not shi-nya. The
                    // Python's empty-string fallthrough is deliberate: a following
                    // character that is not kana at all also takes the apostrophe.
                    val following = kana.substring(i + 1, minOf(i + 2, kana.length))
                    val head = (MONOGRAPHS[following] ?: "").take(1)
                    out += if (following.isNotEmpty() && "aiueoy".contains(head)) "n'" else "n"
                    i += 1
                }

                else -> {
                    out += MONOGRAPHS[char.toString()] ?: char.toString()
                    i += 1
                }
            }
        }
        return out.joinToString("")
    }
}
