package com.mangareader.analysis

import com.mangareader.model.Component
import com.mangareader.model.PhraseRole
import com.mangareader.model.Relation
import com.mangareader.model.Token

/**
 * What each token is doing inside its 文節, and a sentence saying so.
 *
 * The reference JSON's `functional_mapping`. This is the layer that turns a tag into a
 * job: IPADIC says が is 助詞,格助詞, which is a fact about the word class and not about
 * this sentence. What a learner needs is that が is marking あんた as the subject.
 *
 * **The relation is read from three things**, and needing all three is the reason this is
 * a function rather than a map:
 *
 *  - **The tag**, for the machinery. An auxiliary た is a past tense marker wherever it
 *    appears, and no context changes that.
 *  - **The particle's surface**, for the case. IPADIC files は and も together as 係助詞
 *    and に and で together as 格助詞 -- the tag says what *kind* of particle it is, never
 *    which one. `Bunsetsu.ROLE_BY_PARTICLE` already learned this lesson for phrase roles.
 *  - **The enclosing phrase's role**, for the semantics. A noun is an [Relation.AGENT] in
 *    a subject phrase and a [Relation.PATIENT] in an object phrase. The token is
 *    identical; only the phrase around it differs.
 *
 * **The explanations are templates, and are meant to be.** They are general because they
 * come from a table, which is exactly the limit `Prompts.kt` documents for the offline
 * layer. Their job is to be correct and present on a fresh install with no model. When a
 * translator is configured, "Explain in depth" overwrites them per token with prose about
 * this particular line -- see `Component.fromModel`.
 */
object Relations {
    internal fun offlineExplanation(component: Component, source: List<Component>, role: PhraseRole): String =
        explain(component.relation, component.token, source.map { it.token },
            source.indexOfFirst { it.token.position == component.token.position }, role)

    /** Anything that can head a predicate. Mirrors `Bunsetsu.VERBAL`. */
    private val VERBAL = setOf("動詞", "形容詞", "助動詞")

    /**
     * Build every component of one phrase.
     *
     * @param tokens the phrase's own tokens, in order
     * @param role the enclosing phrase's role
     * @param isFinal whether this is the sentence's last phrase, which is what makes a
     *   trailing の a question rather than a nominaliser
     */
    fun components(
        tokens: List<Token>,
        role: PhraseRole,
        isFinal: Boolean,
    ): List<Component> = tokens.mapIndexed { index, token ->
        val relation = relationOf(token, tokens, index, role, isFinal)
        Component(
            token = token,
            relation = relation,
            explanation = explain(relation, token, tokens, index, role),
        )
    }

    // ----------------------------------------------------------------------------------
    // The relation

    private fun relationOf(
        token: Token,
        tokens: List<Token>,
        index: Int,
        role: PhraseRole,
        isFinal: Boolean,
    ): Relation {
        val tag = token.partOfSpeech
        val head = tag.substringBefore(",")
        val last = index == tokens.lastIndex

        return when {
            head == "記号" -> Relation.PUNCTUATION
            head == "感動詞" -> Relation.HEDGE
            head == "接頭詞" -> Relation.PREFIX
            tag.startsWith("名詞,接尾") || tag.startsWith("動詞,接尾") ||
                tag.startsWith("形容詞,接尾") -> suffixOrAux(token)
            head == "助詞" -> particle(token, isFinal && last)
            head == "助動詞" -> auxiliary(token)
            head == "副詞" || head == "連体詞" -> Relation.MODIFIER
            head == "接続詞" -> Relation.CONNECTIVE
            head == "動詞" || head == "形容詞" -> verb(token, tokens, index)
            head == "名詞" -> noun(token, tokens, index, role)
            else -> Relation.OTHER
        }
    }

    /**
     * 動詞,接尾 covers れる and せる, which really are the passive and the causative --
     * `PartOfSpeech.NO_LOOKUP` makes the same call for the same reason. Everything else
     * tagged a suffix is a suffix.
     */
    private fun suffixOrAux(token: Token): Relation = when (token.baseForm) {
        "れる", "られる" -> Relation.PASSIVE
        "せる", "させる" -> Relation.CAUSATIVE
        else -> Relation.SUFFIX
    }

    private fun particle(token: Token, sentenceFinal: Boolean): Relation {
        // A sentence-final の is the casual question the reference JSON calls out; the
        // same token mid-sentence is possessive. Nothing in the tag separates them.
        if (token.surface == "の") {
            return if (sentenceFinal) Relation.QUESTION else Relation.POSSESSIVE
        }
        if (sentenceFinal && token.partOfSpeech.startsWith("助詞,終助詞")) {
            return if (token.surface in QUESTION_ENDINGS) Relation.QUESTION
            else Relation.SENTENCE_ENDING
        }
        return BY_PARTICLE[token.surface]
            ?: if (token.partOfSpeech.startsWith("助詞,接続助詞")) Relation.CONNECTIVE
            else Relation.OTHER
    }

    private fun auxiliary(token: Token): Relation = when (token.baseForm) {
        "た" -> Relation.PAST_TENSE
        "ない", "無い", "ぬ", "ん", "なんだ" -> Relation.NEGATION
        "ます" -> Relation.POLITENESS
        "だ", "です", "じゃ", "や", "なり" -> Relation.COPULA
        "れる", "られる" -> Relation.PASSIVE
        "せる", "させる" -> Relation.CAUSATIVE
        "たい", "たがる" -> Relation.DESIRE
        "う", "よう", "まい", "らしい", "そうだ", "みたいだ", "べし" -> Relation.CONJECTURE
        else -> Relation.OTHER
    }

    /**
     * A verb is the action core unless it is one of the two verbs that are not.
     *
     * **動詞,非自立 is aspect, not a second action.** The て of 呼ばれてて and the いる of
     * 食べている are tagged 動詞,自立's dependent sibling with base forms てる and いる, and
     * reading them as action cores put three of them in one phrase and claimed 呼ばれてて
     * contains two verbs. `PartOfSpeech.NO_LOOKUP` already refuses to gloss these for the
     * same reason: the base form is a real word and the token is not being that word.
     *
     * **The する of a suru-compound verbalises.** 統一 is already the [Relation.ACTION_CORE]
     * of 統一されてない -- `noun` says so, and `Bunsetsu.posOf` says so one level up --
     * so calling さ one too names the action twice and the noun's contribution never.
     */
    private fun verb(token: Token, tokens: List<Token>, index: Int): Relation {
        if (token.partOfSpeech.startsWith("動詞,非自立")) return Relation.ASPECT
        if (token.baseForm == "する" &&
            tokens.take(index).any { it.partOfSpeech.startsWith("名詞,サ変接続") }
        ) {
            return Relation.VERBALISER
        }
        return Relation.ACTION_CORE
    }

    /**
     * A noun takes its meaning from the phrase around it.
     *
     * The exception is a noun that has absorbed a する: 統一 in 統一されてない is tagged
     * 名詞,サ変接続 but is the verb of the sentence, which is the same correction
     * `Bunsetsu.posOf` makes for the phrase's own label.
     */
    private fun noun(
        token: Token,
        tokens: List<Token>,
        index: Int,
        role: PhraseRole,
    ): Relation {
        if (token.partOfSpeech.startsWith("名詞,サ変接続") &&
            tokens.any { it.baseForm == "する" }
        ) {
            return Relation.ACTION_CORE
        }
        // A noun that is not the phrase's head word is modifying the one that is.
        if (index > 0 && tokens[index - 1].partOfSpeech.substringBefore(",") == "名詞") {
            return Relation.MODIFIER
        }
        return when (role) {
            PhraseRole.SUBJECT -> Relation.AGENT
            PhraseRole.OBJECT -> Relation.PATIENT
            PhraseRole.TOPIC -> Relation.TOPIC
            PhraseRole.ADVERBIAL -> adverbialNoun(tokens)
            PhraseRole.PREDICATE -> Relation.ACTION_CORE
            PhraseRole.ADNOMINAL -> Relation.MODIFIER
            PhraseRole.NOMINAL -> Relation.NOMINAL_CORE
            else -> Relation.OTHER
        }
    }

    /** Which flavour of adverbial: 〜で and 〜に place it, 〜によって and 〜から ground it. */
    private fun adverbialNoun(tokens: List<Token>): Relation =
        when (tokens.lastOrNull()?.surface) {
            "で", "に" -> Relation.LOCATION
            "によって", "から", "まで" -> Relation.BASIS
            else -> Relation.MODIFIER
        }

    private val QUESTION_ENDINGS = setOf("か", "かい", "かな", "の", "のか")

    private val BY_PARTICLE = mapOf(
        "が" to Relation.SUBJECT_MARKER,
        "を" to Relation.OBJECT_MARKER,
        "は" to Relation.TOPIC_MARKER,
        "も" to Relation.EMPHASIS,
        "こそ" to Relation.EMPHASIS,
        "に" to Relation.DIRECTION_MARKER,
        "へ" to Relation.DIRECTION_MARKER,
        "で" to Relation.LOCATION_MARKER,
        "から" to Relation.BASIS_MARKER,
        "まで" to Relation.BASIS_MARKER,
        "より" to Relation.BASIS_MARKER,
        "によって" to Relation.BASIS_MARKER,
        "の" to Relation.POSSESSIVE,
        "と" to Relation.MODIFIER,
        "や" to Relation.MODIFIER,
        "か" to Relation.QUESTION,
        "て" to Relation.CONNECTIVE,
        "けど" to Relation.CONNECTIVE,
        "けれど" to Relation.CONNECTIVE,
        "ので" to Relation.CONNECTIVE,
        "のに" to Relation.CONNECTIVE,
        "し" to Relation.CONNECTIVE,
        "ながら" to Relation.CONNECTIVE,
        "つつ" to Relation.CONNECTIVE,
        "ば" to Relation.CONNECTIVE,
        "たら" to Relation.CONNECTIVE,
        "ね" to Relation.SENTENCE_ENDING,
        "よ" to Relation.SENTENCE_ENDING,
        "な" to Relation.SENTENCE_ENDING,
        "ぞ" to Relation.SENTENCE_ENDING,
        "ぜ" to Relation.SENTENCE_ENDING,
        "わ" to Relation.SENTENCE_ENDING,
        "さ" to Relation.SENTENCE_ENDING,
    )

    // ----------------------------------------------------------------------------------
    // The prose

    /**
     * A sentence about this token.
     *
     * Where the token inflects, the inflection is appended rather than woven in: a
     * template that has to read correctly with and without a conjugation ends up saying
     * neither thing well. Two short sentences beat one hedged one.
     */
    private fun explain(
        relation: Relation,
        token: Token,
        tokens: List<Token>,
        index: Int,
        role: PhraseRole,
    ): String {
        val prefixNeedsContext = relation == Relation.PREFIX && (token.surface == "ひと" ||
            tokens.getOrNull(index + 1)?.let { token.end == it.start && PartOfSpeech.isIndependent(it.partOfSpeech) } != true)
        val lead = if (prefixNeedsContext) template(relation, token, tokens, index, role)
            else GrammarFamilies.explain(token) ?: if (relation == Relation.OTHER && token.head == "助詞")
                GrammarFamilies.particle(token) else template(relation, token, tokens, index, role)
        val head = token.partOfSpeech.substringBefore(",")
        if (head !in VERBAL && !token.partOfSpeech.startsWith("動詞,接尾")) return lead
        val inflection = Inflection.describe(
            rawForm = token.conjugationForm,
            rawType = token.conjugationType,
            baseForm = token.baseForm,
        )
        return if (inflection.isEmpty()) lead else lead + " " + inflection
    }

    private fun template(
        relation: Relation,
        token: Token,
        tokens: List<Token>,
        index: Int,
        role: PhraseRole,
    ): String {
        val next = tokens.getOrNull(index + 1)
        return when (relation) {
            Relation.AGENT ->
                "The one performing the action of the predicate."
            Relation.PATIENT ->
                "The entity the action is done to."
            Relation.TOPIC ->
                "What the rest of the sentence is about."
            Relation.LOCATION ->
                "Where the action happens, or what it is done with."
            Relation.BASIS ->
                "What the rest of the clause depends on."
            Relation.ACTION_CORE ->
                if (role == PhraseRole.PREDICATE) "The root action of the predicate."
                else "The action this phrase is built on."
            Relation.MODIFIER ->
                "Modifies what follows it."

            Relation.SUBJECT_MARKER ->
                "Marks the noun before it as the grammatical subject of the clause."
            Relation.OBJECT_MARKER ->
                "Marks the noun before it as the direct object of the transitive verb."
            Relation.TOPIC_MARKER ->
                "Raises the noun before it to the topic: the thing being talked about, " +
                    "which is not always the subject."
            Relation.DIRECTION_MARKER ->
                if (token.surface == "へ") "Marks the direction or destination of the action."
                else "Connects the noun or noun construction before it to the predicate: a destination, " +
                    "time, recipient, purpose or cause, depending on the verb."
            Relation.LOCATION_MARKER ->
                "Marks the noun before it as where the action happens, or what it is done " +
                    "with."
            Relation.BASIS_MARKER ->
                "Marks the noun before it as what the rest of the clause depends on."
            Relation.POSSESSIVE ->
                "Links the noun before it to the noun after: the second belongs to, or is " +
                    "described by, the first."

            Relation.PAST_TENSE -> buildString {
                append("Puts the action in the past.")
                if (next?.surface == "の" || next?.surface == "ん") {
                    append(" Attributive here, so it can carry the ")
                    append(next.surface)
                    append(" that follows.")
                }
            }
            Relation.NEGATION ->
                "Negates the verb it attaches to."
            Relation.POLITENESS ->
                "The polite ending. It changes nothing about what happened, only who the " +
                    "speaker is talking to."
            Relation.PASSIVE ->
                "れる／られる can express passive voice, potential ability or respect. " +
                    "The predicate and surrounding context determine which function applies."
            Relation.CAUSATIVE ->
                "Makes the subject the one who causes or permits the action."
            Relation.ASPECT ->
                "Carries aspect rather than a second action: how the verb before it is " +
                    "spread out in time."
            Relation.VERBALISER ->
                "する, which turns the noun before it into a verb. A large part of the " +
                    "Japanese verb stock is built this way."
            Relation.DESIRE ->
                "Turns the verb into wanting to do it."
            Relation.CONJECTURE ->
                "Marks the statement as a guess or an intention rather than a fact."
            Relation.COPULA ->
                "The copula: asserts that the thing before it is so."

            Relation.CONNECTIVE ->
                "Ends this clause without ending the sentence, and links it to the next."
            Relation.QUESTION ->
                if (token.surface == "の")
                    "Turns the plain statement into a casual question. Softer than か, and " +
                        "expects an explanation rather than a yes or no."
                else "Marks the sentence as a question."
            Relation.SENTENCE_ENDING ->
                "Carries tone rather than grammar: how the speaker wants the line taken."
            Relation.EMPHASIS ->
                "Adds \"too\" or \"even\" to the noun before it."

            Relation.PREFIX -> {
                val next = tokens.getOrNull(index + 1)?.takeIf {
                    PartOfSpeech.isIndependent(it.partOfSpeech) && token.end == it.start
                }
                when {
                    next == null -> "A prefix requiring a following word."
                    token.surface == "ひと" && token.partOfSpeech.startsWith("接頭詞,名詞接続") ->
                        "Adds ‘one’ to the following noun (${next.surface}), forming ${token.surface}${next.surface}."
                    else -> "A prefix on the following word (${next.surface})."
                }
            }
            Relation.SUFFIX ->
                when {
                    token.surface == "っぷり" && token.partOfSpeech.startsWith("名詞,接尾") ->
                        "Forms a noun describing the manner or state of what comes before it: how it is done."
                    token.surface in setOf("たち", "達", "ら", "等") ->
                        "Forms a group referring to the person before it and others associated with them."
                    token.surface == "分け" && token.partOfSpeech.startsWith("名詞,接尾") ->
                        "Forms a noun for dividing or sorting what comes before it into groups."
                    token.partOfSpeech.startsWith("名詞,接尾,人名") ->
                        "A form of address attached to a name, expressing familiarity or respect."
                    token.partOfSpeech.startsWith("名詞,接尾,助数詞") ->
                        "A counter specifying the kind of thing counted by the number before it."
                    else -> "Forms a derived word from what precedes it; the full construction determines its meaning."
                }
            Relation.HEDGE ->
                "An interjection. Sets the tone before the sentence proper starts."
            Relation.PUNCTUATION ->
                "Punctuation. Not part of the sentence's structure."
            Relation.OTHER ->
                "Its role is unclear from this fragment; check the surrounding dialogue or correct the text."
            Relation.NOMINAL_CORE ->
                "Names something in a fragment with no explicit predicate."
        }
    }
}
