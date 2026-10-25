package com.mangareader.analysis

/**
 * IPADIC part-of-speech tags, ported from `harness/pipeline/analyse.py`.
 *
 * Two jobs: a short English label for the breakdown row, and the mapping section 6.3
 * uses to prefer a JMdict entry whose own part of speech agrees with the tokenizer --
 * without it 見る as a verb can lose to an unrelated noun headword.
 */
object PartOfSpeech {

    private val LABELS = mapOf(
        "名詞" to "noun", "動詞" to "verb", "形容詞" to "i-adjective", "副詞" to "adverb",
        "助詞" to "particle", "助動詞" to "auxiliary", "接続詞" to "conjunction",
        "感動詞" to "interjection", "連体詞" to "prenominal", "接頭詞" to "prefix",
        "接続助詞" to "conjunctive particle", "記号" to "symbol", "フィラー" to "filler",
        "その他" to "other", "未知語" to "unknown",
    )

    /** IPADIC part of speech -> the JMdict pos tags that should be preferred for it. */
    private val TO_JMDICT = mapOf(
        "名詞" to listOf("n", "n-suf", "n-pref", "adj-na", "pn", "vs"),
        "動詞" to listOf(
            "v1", "v5", "vk", "vs", "vt", "vi", "v5r", "v5u", "v5k", "v5s", "v5t",
            "v5n", "v5b", "v5m", "v5g", "v5aru", "vs-i", "vz",
        ),
        "形容詞" to listOf("adj-i", "adj-ix"),
        "副詞" to listOf("adv", "adv-to", "n-adv"),
        "連体詞" to listOf("adj-pn"),
        "接続詞" to listOf("conj"),
        "感動詞" to listOf("int"),
        "助詞" to listOf("prt"),
        "助動詞" to listOf("aux-v", "aux", "cop"),
        "接頭詞" to listOf("pref"),
    )

    /** What the breakdown emphasises; section 3.3's tier 2 dims the rest. */
    val CONTENT = setOf("名詞", "動詞", "形容詞", "副詞", "連体詞", "感動詞")

    /** Full IPADIC tags matter: a noun suffix is not independent vocabulary. */
    fun isIndependent(pos: String): Boolean {
        val parts = pos.split(",")
        return parts.first() in CONTENT + setOf("接続詞", "未知語", "その他") &&
            parts.getOrNull(1) !in setOf("接尾", "非自立")
    }

    fun isAttached(pos: String): Boolean {
        val parts = pos.split(",")
        return parts.first() in setOf("助詞", "助動詞", "接頭詞") ||
            parts.getOrNull(1) in setOf("接尾", "非自立")
    }

    /**
     * Tags whose base form is not a headword, even though their part of speech is a
     * content one. JMdict finds an entry for each of these -- the wrong entry -- because
     * the base form collides with an unrelated word, and a confident wrong gloss is
     * worse for a learner than no gloss at all:
     *
     *  - `動詞,非自立` the て of ～されてない bases to てる and lands on 照る, "to shine"
     *  - `名詞,接尾,人名` the ちゃん of ハナちゃん lands on 父ちゃん, "father"
     *  - `名詞,接尾,助数詞` a counter lands on whatever noun shares its kanji
     *
     * `動詞,接尾` is deliberately *not* here: れる and せる really are the passive and
     * causative auxiliaries JMdict describes, and that is what tier 2 should say.
     */
    private val NO_LOOKUP = listOf(
        listOf("動詞", "非自立"),
        listOf("名詞", "接尾", "人名"),
        listOf("名詞", "接尾", "助数詞"),
    )

    /** Whether a token should be sent to JMdict at all. */
    fun looksUp(pos: String): Boolean {
        val parts = pos.split(",")
        if (parts[0] !in CONTENT && parts[0] != "助動詞") return false
        return NO_LOOKUP.none { tags -> parts.size >= tags.size && parts.take(tags.size) == tags }
    }

    fun jmdictTags(pos: String): List<String> = TO_JMDICT[pos.substringBefore(",")].orEmpty()

    /**
     * The bare English word for a tag's first level: "noun", "verb", "particle".
     *
     * [label] is the row label and says as much as it can -- "noun (suru-verb)",
     * "verb (dependent)", "proper noun". A structure chip has room for one word next to
     * the role, so this is that word.
     */
    fun shortLabel(pos: String): String {
        val head = pos.substringBefore(",")
        return LABELS[head] ?: head
    }

    fun label(pos: String): String {
        val parts = pos.split(",")
        val label = LABELS[parts[0]] ?: parts[0]
        val second = parts.getOrNull(1)
        return when (second) {
            "サ変接続" -> "$label (suru-verb)"
            "固有名詞" -> "proper noun"
            "非自立" -> "$label (dependent)"
            "接尾" -> "$label (suffix)"
            else -> label
        }
    }
}
