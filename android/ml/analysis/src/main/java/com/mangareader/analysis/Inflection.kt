package com.mangareader.analysis

/**
 * IPADIC's two inflection fields, in English.
 *
 * 活用型 ([type]) is *what kind of thing* inflects -- a godan verb, an ichidan verb, the
 * auxiliary た. 活用形 ([form]) is *which slot of it* this token is standing in -- the
 * dictionary form, the continuative stem, the imperative. The reference JSON keeps them
 * as `inflection.conjugation_type` and `inflection.conjugation_form`, and tier 4 needs
 * both: "continuative" alone does not tell a learner why 殺す becomes 殺し, and "godan
 * verb" alone does not tell them what 殺し is doing in this sentence.
 *
 * Both are matched by **prefix**, which is not a shortcut. IPADIC's 活用型 enumerates every
 * row of every conjugation -- 五段・カ行イ音便, 五段・カ行促音便, 五段・ガ行 -- and a learner
 * being told the row of the kana table is being told something true and useless. The
 * family is the part worth reading; the row is recoverable from the kana.
 *
 * The 命令 forms carry **full-width** latin tails: 命令ｅ, 命令ｒｏ, 命令ｉ, 命令ｙｏ. They do
 * not match an ASCII "e". `GrammarRules.kt` warns about the same trap; it is repeated here
 * because this is the second place that has to know.
 */
object Inflection {

    /** 活用型 -> the family, longest prefix first so 五段・ラ行特殊 does not beat 五段. */
    private val TYPES = listOf(
        "カ変・来ル" to "irregular verb 来る",
        "カ変・クル" to "irregular verb 来る",
        "サ変・スル" to "suru-verb",
        "サ変・－スル" to "suru-verb",
        "サ変・−スル" to "suru-verb",
        "サ変・−ズル" to "zuru-verb",
        "一段・クレル" to "ichidan verb",
        "一段" to "ichidan verb",
        "五段" to "godan verb",
        "四段" to "classical yodan verb",
        "形容詞・イイ" to "i-adjective",
        "形容詞" to "i-adjective",
        "特殊・タ" to "auxiliary た",
        "特殊・ダ" to "copula だ",
        "特殊・デス" to "copula です",
        "特殊・マス" to "auxiliary ます",
        "特殊・ナイ" to "auxiliary ない",
        "特殊・タイ" to "auxiliary たい",
        "特殊・ヌ" to "auxiliary ぬ",
        "特殊・ジャ" to "copula じゃ",
        "特殊・ヤ" to "copula や",
        "不変化型" to "uninflecting",
        "文語・ベシ" to "classical べし",
        "文語・ナリ" to "classical copula なり",
        "文語・ゴトシ" to "classical ごとし",
        "文語・キ" to "classical past き",
        "文語・ケリ" to "classical けり",
        "文語・リ" to "classical り",
        "文語" to "classical form",
        "下二" to "classical shimo-nidan verb",
        "上二" to "classical kami-nidan verb",
        "ラ変" to "classical ra-irregular verb",
    )

    /** 活用形 -> the slot. Order matters for the same reason. */
    private val FORMS = listOf(
        "基本形-促音便" to "dictionary form",
        "基本形" to "dictionary form",
        "現代基本形" to "dictionary form",
        "音便基本形" to "dictionary form",
        "文語基本形" to "classical dictionary form",
        "未然ウ接続" to "irrealis (before う)",
        "未然ヌ接続" to "irrealis (before ぬ)",
        "未然レル接続" to "irrealis (before れる)",
        "未然特殊" to "irrealis",
        "未然形" to "irrealis (mizenkei)",
        "連用タ接続" to "continuative (before た)",
        "連用テ接続" to "continuative (before て)",
        "連用デ接続" to "continuative (before で)",
        "連用ゴザイ接続" to "continuative (before ございます)",
        "連用ニ接続" to "continuative (before に)",
        "連用形" to "continuative (ren'youkei)",
        "連体形" to "attributive (rentaikei)",
        "体言接続" to "attributive (before a noun)",
        "仮定縮約" to "contracted conditional",
        "仮定形" to "conditional (kateikei)",
        "命令" to "imperative",
        "ガル接続" to "stem (before がる)",
    )

    /** English for 活用型, or "" when the tag is absent or unrecognised. */
    fun type(raw: String): String = lookUp(raw, TYPES)

    /** English for 活用形, or "" when the tag is absent or unrecognised. */
    fun form(raw: String): String = lookUp(raw, FORMS)

    /**
     * The pair as one clause, for a [Relations] template: "continuative (ren'youkei) of
     * the godan verb 殺す".
     *
     * Returns "" when there is nothing to say, so a caller can append it unconditionally
     * and get a clean sentence for a word that does not inflect.
     */
    fun describe(rawForm: String, rawType: String, baseForm: String): String {
        val f = form(rawForm)
        val t = type(rawType)
        if (f.isEmpty() && t.isEmpty()) return ""
        // 特殊・ナイ englishes to "auxiliary ない", so appending the base form gives
        // "auxiliary ない ない". Where the family already names the word, it is the word.
        val named = baseForm.isNotEmpty() && !t.contains(baseForm)
        if (f.isEmpty()) {
            return t.replaceFirstChar { it.uppercase() } +
                (if (named) " " + baseForm else "") + "."
        }
        if (t.isEmpty()) return f.replaceFirstChar { it.uppercase() } + "."
        return f.replaceFirstChar { it.uppercase() } + " of the " + t +
            (if (named) " " + baseForm else "") + "."
    }

    private fun lookUp(raw: String, table: List<Pair<String, String>>): String {
        if (raw.isEmpty() || raw == "*") return ""
        return table.firstOrNull { raw.startsWith(it.first) }?.second ?: ""
    }
}
