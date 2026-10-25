package com.mangareader.analysis

import com.mangareader.model.Token

/** Cautious explanations for attached morphology that a construction has not consumed. */
internal object GrammarFamilies {
    fun particle(token: Token): String = when (token.partOfSpeech.split(",").getOrNull(1)) {
        "格助詞" -> "Connects the preceding expression to a predicate or quoted description. The compound expression and predicate determine the relationship; spelling alone does not select it."
        "係助詞", "副助詞", "副助詞／並立助詞／終助詞" -> "Adds focus, limitation, degree or emphasis to the preceding expression. The surrounding predicate and discourse determine its specific scope."
        "接続助詞" -> "Links the preceding clause to another clause. Verb morphology and the following statement distinguish condition, cause, contrast and sequence."
        "並立助詞" -> "Coordinates expressions in a list or alternative. Context determines whether the list is exhaustive and how its members are related."
        "終助詞" -> "Adds the speaker's stance to the clause ending. Dialogue and intonation determine its force, such as emphasis, confirmation or solicitation."
        "連体化" -> "Connects the preceding expression as a description of a following noun. The two expressions determine the relationship."
        "副詞化" -> "Turns the preceding expression into a modifier of the following predicate; the complete expression determines what is modified."
        else -> "Attaches to the preceding expression to organise its role or the speaker's stance. This fragment does not provide enough context to select a particular interpretation."
    }
    fun explain(token: Token): String? {
        val p = token.partOfSpeech.split(",")
        val lemma = token.baseForm
        if (token.partOfSpeech.startsWith("助詞,副助詞／並立助詞／終助詞"))
            return "か can mark a question, an alternative or an indefinite choice. Its position and the complete clause determine which function applies."
        if (p.getOrNull(1) == "非自立" && p.first() == "名詞") return when (lemma) {
            "こと", "事", "コト", "の", "ん", "ン" ->
                "Turns the preceding description into a noun-like expression, so it can take particles. The surrounding construction determines its interpretation."
            "もの", "物", "モノ", "もん" ->
                "Refers back to what is described before it as a thing or fact; in sentence endings it can also give a reason or express the speaker's stance."
            "ため", "為", "せい", "ゆえ", "故" ->
                "Connects the preceding description to a purpose, reason or consequence. Context determines which relationship applies."
            "はず", "筈", "つもり", "わけ", "訳" ->
                "Packages the preceding proposition as an expectation, intention or explanation. Read the complete construction to determine the speaker's claim."
            "とき", "時", "ころ", "頃", "際", "たび", "度", "都度", "つど", "とたん", "途端", "のち", "後", "うち", "間" ->
                "Uses the preceding description to specify a time, occasion or interval for the following event."
            "ところ", "所", "まま", "まんま", "最中", "さなか" ->
                "Presents the preceding action or condition as a stage, situation or continuing state. The verb form and following particles determine the construction."
            "よう", "様", "やう", "みたい", "ふう", "ふり" ->
                "Uses the preceding description as a comparison, appearance or manner. Following auxiliaries and particles distinguish these functions."
            else -> "A dependent noun attached to the preceding description. It supplies a noun-like anchor for the following particles; its specific relationship needs the complete construction and surrounding context."
        }
        if (p.getOrNull(1) == "非自立" && p.first() == "形容詞") return when (lemma) {
            "やすい" -> "Says the preceding action is easy to do or tends to happen."
            "にくい", "づらい", "がたい", "難い" -> "Says the preceding action is difficult to do; context determines whether the difficulty is practical, emotional or near impossibility."
            "ほしい", "欲しい" -> "After a て-form, expresses a wish for someone to perform that action."
            else -> "Evaluates the preceding action or condition. The attachment and surrounding construction determine whether it expresses permission, desirability or another evaluation."
        }
        if (p.getOrNull(1) == "非自立" && p.first() == "動詞") return when (lemma) {
            "いる", "てる", "おる", "とる", "どる" -> "After a て-form, describes an ongoing action or a resulting state; the main verb and context determine which."
            "ある" -> "After a て-form, describes the resulting state of an intentional action."
            "おく", "とく", "どく" -> "Adds preparation for later or leaving the result in place."
            "しまう", "しまふ", "じまう", "ちゃう", "ちまう", "じゃう" -> "Presents the action as completed; context may also convey regret or an unintended outcome."
            "あげる", "上げる", "やる", "たげる" -> "Presents doing the preceding action for someone else's benefit. Context determines the recipient and social tone."
            "くれる", "くださる", "下さる" -> "Presents someone doing the preceding action for the speaker or someone in the speaker's group."
            "もらう", "もらえる", "いただく", "頂く" -> "Presents receiving the benefit of someone performing the preceding action; いただく is humble."
            "みる" -> "After a て-form, means trying the action to see its result."
            "いく", "行く", "ゆく", "くる", "来る", "てく", "でく" -> "Describes the action's development or movement relative to a reference point. Context determines its temporal or directional reading."
            "すぎる", "過ぎる" -> "Says the preceding action or quality goes beyond an appropriate degree."
            "できる", "える", "うる", "得る", "いける" -> "Adds possibility or ability to the preceding action; the complete construction determines its force."
            "かねる", "そこねる", "そびれる" -> "Adds inability or failure to carry out the preceding action."
            "はじめる", "始める", "だす", "出す" -> "Marks the beginning or onset of the preceding action."
            "つづける", "続ける", "続く" -> "Marks continuation of the preceding action."
            "きる", "切る", "ぬく", "抜く", "終える", "終わる", "つくす", "尽くす" -> "Marks completion or carrying the preceding action through to its end."
            "なおす", "直す" -> "Adds doing the preceding action again or correcting its result."
            else -> "An attached verb extending the preceding predicate. It may add a phase, direction, degree or social perspective; this spelling alone does not determine the construction's meaning."
        }
        if (p.first() == "助動詞") return when (lemma) {
            "へん", "ひん", "じ", "まじ" -> "A dialectal or classical negative auxiliary. Its tense and modal force depend on the complete predicate."
            "き", "けり", "つ", "り", "る", "たり" -> "A classical auxiliary expressing tense or completion. Its conjugation and the preceding predicate determine the exact interpretation."
            "ごとし", "如し" -> "A classical comparison auxiliary, presenting something as like the preceding description."
            "ござる", "御座る", "ある", "っす", "どす", "やす", "やん", "じゃん", "じゃン" -> "An auxiliary in a copular or socially marked predicate. The complete ending distinguishes assertion, politeness and dialectal tone."
            else -> null
        }
        if (p.first() == "接頭詞") return when (token.surface) {
            "お", "ご", "御" -> "Adds respect, politeness or a conventional refined form to the following word. It need not express respect for a person in every word."
            "不", "非", "未", "無" -> "Forms a word expressing negation, absence or something not yet realised; the following word determines the contrast."
            "ひと" -> "Adds ‘one’ to the following noun or action unit."
            else -> when (p.getOrNull(1)) {
                "数接続" -> "Attaches to a following number to qualify a quantity or its range. The specific unit and scale require the complete expression."
                "動詞接続" -> "Attaches to the following verb to modify the action. Its specific force needs the complete derived verb and context."
                "形容詞接続" -> "Attaches to the following adjective to modify its degree or evaluation. Its specific force needs the complete expression."
                else -> "Attaches before the following noun to form a derived word. Its specific semantic contribution cannot be selected from the prefix tag alone."
            }
        }
        if (p.getOrNull(1) == "接尾") return when {
            token.surface in setOf("っぷり", "ぶり", "振り") -> "Forms a noun describing the manner or state of what comes before it: how it is done."
            token.surface in setOf("たち", "達", "ら", "等") -> "Forms a group referring to the person before it and others associated with them."
            p.getOrNull(2) == "人名" -> "A form of address attached to a name, expressing familiarity or respect. The name and social context determine the appropriate English rendering."
            p.getOrNull(2) == "助数詞" -> "Attaches to a number to specify the unit or kind of thing counted. The exact unit may depend on the counted object and context."
            p.first() == "形容詞" -> "Forms an adjective evaluating the preceding word. The derived word and context determine the quality being expressed."
            p.first() == "動詞" -> if (lemma in setOf("れる", "られる", "せる", "させる")) null
                else "Forms a derived predicate from the preceding word. The stem and surrounding predicate determine the suffix's semantic contribution."
            p.getOrNull(2) == "地域" -> "Attaches to a place name to specify a geographical or administrative unit. The complete name determines the unit."
            p.getOrNull(2) == "サ変接続" -> "Forms a derived noun that can combine with する to describe an action. Its specific action depends on the preceding word."
            p.getOrNull(2) == "形容動詞語幹" -> "Forms a derived describing word that can take な or a copula. The preceding word supplies what is being characterised."
            p.getOrNull(2) == "副詞可能" -> "Forms a noun expression that may qualify a time, quantity or manner adverbially. Following particles and the predicate determine its use."
            else -> "Forms a derived noun from the preceding word. The suffix tag establishes attachment, but the resulting word and context determine its specific meaning."
        }
        return null
    }
}
