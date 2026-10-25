package com.mangareader.analysis

import com.mangareader.model.Phrase
import com.mangareader.model.PhraseRole
import com.mangareader.model.Token

/**
 * 文節 chunking, ported from `tools/check_grammar_rules.py`.
 *
 * A phrase **starts** at an 自立語 (an independent word) and **absorbs** the 付属語 that
 * follow it -- particles, auxiliaries, suffixes and dependent forms. The rule is old, it
 * is what Japanese schoolchildren are taught, and it needs no weights.
 *
 * **This is still not a dependency parse**, and the grouping here is the same grouping it
 * always was. What changed is what comes out of it: a [Phrase] now holds its own tokens
 * with a [com.mangareader.model.Relation] on each, and `Dependency` attaches the phrases
 * to each other afterwards. The chunker itself learned one new distinction and nothing
 * else --
 *
 * [PhraseRole.ADVERBIAL] and [PhraseRole.ADNOMINAL] used to be a single `MODIFIER`, which
 * ran together the two different things a modifier can attach to. 色々 modifies the verb;
 * 見る in 見る人 modifies the noun. Nothing downstream could tell them apart, and
 * `Dependency` cannot place either edge without knowing which it has.
 */
object Bunsetsu {

    /** Word classes that can head a phrase. [PartOfSpeech.CONTENT] plus 接続詞. */
    private val INDEPENDENT = PartOfSpeech.CONTENT + "接続詞"

    /** Anything that can end a predicate. */
    private val VERBAL = setOf("動詞", "形容詞", "助動詞")

    /**
     * The role a trailing particle assigns. Read off the surface rather than the tag,
     * because IPADIC files は and も together as 係助詞 and に and で together as 格助詞 --
     * the tag says what kind of particle it is, not what this one is doing.
     */
    private val ROLE_BY_PARTICLE = mapOf(
        "は" to PhraseRole.TOPIC,
        "が" to PhraseRole.SUBJECT,
        "を" to PhraseRole.OBJECT,
        "に" to PhraseRole.ADVERBIAL,
        "へ" to PhraseRole.ADVERBIAL,
        "で" to PhraseRole.ADVERBIAL,
        "から" to PhraseRole.ADVERBIAL,
        "まで" to PhraseRole.ADVERBIAL,
        "より" to PhraseRole.ADVERBIAL,
        "によって" to PhraseRole.ADVERBIAL,
        "として" to PhraseRole.ADVERBIAL,
        "の" to PhraseRole.ADNOMINAL,
        "て" to PhraseRole.CONNECTIVE,
        "けど" to PhraseRole.CONNECTIVE,
        "けれど" to PhraseRole.CONNECTIVE,
        "ので" to PhraseRole.CONNECTIVE,
        "のに" to PhraseRole.CONNECTIVE,
        "し" to PhraseRole.CONNECTIVE,
        "ながら" to PhraseRole.CONNECTIVE,
        "つつ" to PhraseRole.CONNECTIVE,
        "ば" to PhraseRole.CONNECTIVE,
        "たら" to PhraseRole.CONNECTIVE,
    )

    fun chunk(tokens: List<Token>): List<Phrase> {
        val groups = mutableListOf<MutableList<Token>>()
        tokens.forEachIndexed { index, token ->
            // Brackets and punctuation belong to no phrase. They are still in the token
            // list, and the corner-bracket rule still fires on them; they just are not
            // structure.
            if (token.head == "記号") return@forEachIndexed
            val previous = tokens.getOrNull(index - 1)
            if (groups.isEmpty() || startsChunk(token, previous)) {
                groups += mutableListOf(token)
            } else {
                groups.last() += token
            }
        }

        return groups.mapIndexed { index, group ->
            val isFinal = index == groups.lastIndex
            val role = roleOf(group, isFinal)
            Phrase(
                id = index,
                text = group.joinToString("") { it.surface },
                role = role,
                from = group.first().position,
                to = group.last().position,
                pos = posOf(group),
                // Joined with **no** separator, unlike tier 1, which spaces every token.
                // That is not an inconsistency: tier 1 spaces at token boundaries because
                // token boundaries are all it knows, and a 文節 is one prosodic word.
                // Spacing inside one gives "yoba re te te" for 呼ばれてて, which is not a
                // reading of anything. The phrase boundary is the space.
                romaji = group.joinToString("") { it.romaji },
                // Left for `Dependency.attach`, which needs every phrase's role before it
                // can place any single edge.
                head = -1,
                components = Relations.components(group, role, isFinal),
            )
        }
    }

    private fun roleOf(group: List<Token>, isFinal: Boolean): PhraseRole {
        // Tone particles do not erase an explicit case marker or turn a noun into a verb.
        val structural = group.dropLastWhile { it.head == "助詞" &&
            (it.partOfSpeech.contains("終助詞") || it.surface in setOf("か", "かな")) }
            .ifEmpty { group }
        val last = structural.last()
        val head = group.firstOrNull { it.head != "接頭詞" } ?: group.first()
        val nominalEnding = structural.lastOrNull { it.head != "助詞" }?.head == "名詞"
        var role = PhraseRole.OTHER

        when {
            last.head == "助詞" -> {
                role = if (last.partOfSpeech.startsWith("助詞,終助詞")) {
                    // A sentence-final particle says something about tone, not about the
                    // phrase's job in the sentence.
                    PhraseRole.OTHER
                } else if (last.partOfSpeech.startsWith("助詞,接続助詞")) {
                    PhraseRole.CONNECTIVE
                } else {
                    ROLE_BY_PARTICLE[last.surface] ?: PhraseRole.OTHER
                }
            }

            // An interjection sits in front of the sentence rather than inside it, and
            // `Dependency` needs to know that to keep it off the first verb.
            head.head == "感動詞" -> role = PhraseRole.INTERJECTION

            // An adverb modifies the predicate and carries no particle to read a role
            // off, so the role has to come from the word class.
            head.head == "副詞" -> role = PhraseRole.ADVERBIAL

            // A verb or adjective sitting directly in front of another phrase is
            // attributive: 見る人 is "the person who looks", not a second predicate.
            !isFinal && last.head in VERBAL -> role = PhraseRole.ADNOMINAL

            // A 連体詞 -- この, その, 大きな -- exists only to modify a noun.
            head.head == "連体詞" -> role = PhraseRole.ADNOMINAL
        }

        if (isFinal) {
            val verbal = group.any { it.head in VERBAL } && !nominalEnding
            val endsVerbal = last.head in VERBAL || last.head == "助詞"
            if (verbal && endsVerbal && role in setOf(PhraseRole.OTHER, PhraseRole.CONNECTIVE,
                    PhraseRole.ADNOMINAL)) role = PhraseRole.PREDICATE
        }

        if (isFinal && role == PhraseRole.OTHER && nominalEnding) {
            role = PhraseRole.NOMINAL
        }
        // A final connective still belongs to the predicate it softens or leaves open.
        if (isFinal && role == PhraseRole.CONNECTIVE && !nominalEnding && group.any { it.head in VERBAL }) {
            role = PhraseRole.PREDICATE
        }

        return role
    }

    /**
     * What word class the phrase *is*, which is not always what its head token is tagged.
     *
     * IPADIC tags 統一 as `名詞,サ変接続` -- a noun that can take する. In 統一されてない the
     * する is right there in the same phrase, and the phrase is a verb by any reading that
     * matters to someone learning the sentence. Reporting the head's raw tag would put
     * "noun" under the sentence's main predicate.
     *
     * This is the same compound `startsChunk` already refuses to split; it just has to be
     * said twice, once for grouping and once for naming.
     */
    private fun posOf(group: List<Token>): String {
        val head = group.firstOrNull { it.head != "接頭詞" } ?: group.first()
        val nominalSuffix = group.indexOfLast { it.partOfSpeech.startsWith("名詞,接尾") }
        if (nominalSuffix >= 0 && group.drop(nominalSuffix + 1).all { it.head in setOf("助詞", "助動詞") }) {
            return "名詞"
        }
        if (head.partOfSpeech.startsWith("名詞,サ変接続") &&
            group.any { it.baseForm == "する" }
        ) {
            return "動詞"
        }
        return head.head
    }

    /**
     * Whether [token] opens a new phrase.
     *
     * The one exception to "an 自立語 starts a phrase" is サ変 する. IPADIC tags the さ of
     * 統一された as 動詞,自立 with the base form する -- fully independent, by the tag --
     * but 統一する is one word and one phrase. Without this, every suru-verb on the page
     * splits down the middle.
     */
    private fun startsChunk(token: Token, previous: Token?): Boolean {
        // Prefixes belong to the following word, never the preceding phrase. Keep a
        // contiguous prefix chain and its head together; punctuation/gaps break it.
        val followsPrefix = previous?.head == "接頭詞" && token.position == previous.position + 1 &&
            token.start >= 0 && token.start == previous.end
        if (token.head == "接頭詞") return !followsPrefix
        if (token.head !in INDEPENDENT) return false
        if (followsPrefix) return false
        if (token.partOfSpeech.startsWith("名詞,非自立")) return false
        if (token.partOfSpeech.startsWith("動詞,非自立")) return false
        if (token.partOfSpeech.startsWith("動詞,接尾")) return false
        if (token.partOfSpeech.startsWith("名詞,接尾")) return false
        if (token.baseForm == "する" &&
            previous?.partOfSpeech?.startsWith("名詞,サ変接続") == true
        ) {
            return false
        }
        return true
    }
}

/** The first level of the IPADIC tag, which is what most of this file asks about. */
internal val Token.head: String get() = partOfSpeech.substringBefore(",")
