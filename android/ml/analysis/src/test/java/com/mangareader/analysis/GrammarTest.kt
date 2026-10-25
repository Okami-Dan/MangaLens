package com.mangareader.analysis

import com.mangareader.model.ClauseStructure
import com.mangareader.model.NoteSource
import com.mangareader.model.PhraseRole
import com.mangareader.model.Relation
import com.mangareader.model.RegisterLevel
import com.mangareader.model.SentenceType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Section 3.3 tier 4 on the JVM.
 *
 * Everything tier 4 does is a pure function of Kuromoji's output and a bundled JSON
 * table, and Kuromoji is pure Java -- so all of it is checkable here, with no device and
 * no dictionary. That is the same property `:ml:analysis` was built for.
 *
 * The fixtures are the mockup's own bubbles. They are real manga text with answers that
 * were verified against `tools/check_grammar_rules.py` -- the reference implementation
 * this module is a port of -- rather than answers invented to match the code. The last
 * one exercises passive, suru-verb passive, two contractions, 〜によって and a trailing
 * けど in a single line, which is most of what this table exists for.
 */
class GrammarTest {

    private val rules: GrammarRules by lazy {
        val file = File("../../../tools/grammar_rules.json").let {
            if (it.isFile) it else File("../../tools/grammar_rules.json")
        }
        assertTrue(
            "tools/grammar_rules.json not found from " + File(".").absolutePath,
            file.isFile,
        )
        GrammarRules.parse(file.readText(Charsets.UTF_8))
    }

    private val grammar: Grammar by lazy { Grammar(rules) }

    // No dictionary: tier 4 never looks a word up, and a fresh install has none anyway.
    private val analyser: Analyser by lazy { Analyser(dictionary = null, sfx = sfx()) }

    private fun sfx(): SfxDictionary {
        val file = File("../../../tools/sfx_dict.json").let {
            if (it.isFile) it else File("../../tools/sfx_dict.json")
        }
        return SfxDictionary.parse(file.readText(Charsets.UTF_8))
    }

    private fun analyse(text: String) = grammar.analyse(analyser.tokenize(text))

    private val LONG = "まぁ見る人によって色々呼ばれてて統一されてないけど"

    // ----------------------------------------------------------------------------------
    // The reference JSON, reproduced offline
    //
    // The sentence the redesign was specified against. Every assertion here is a field of
    // the supplied `sentence_analysis` document, so the whole block is one question: does
    // the offline engine produce that shape without a model?

    @Test
    fun `the reference sentence produces the reference structure`() {
        val breakdown = analyse("本当にあんたがお父さんを殺したの…？")

        assertEquals(
            listOf("本当に", "あんたが", "お父さんを", "殺したの"),
            breakdown.phrases.map { it.text },
        )
        assertEquals(
            listOf(
                PhraseRole.ADVERBIAL,
                PhraseRole.SUBJECT,
                PhraseRole.OBJECT,
                PhraseRole.PREDICATE,
            ),
            breakdown.phrases.map { it.role },
        )
        // target_dependency: everything hangs off the predicate, which is the root.
        assertEquals(listOf(3, 3, 3, -1), breakdown.phrases.map { it.head })

        assertEquals(SentenceType.QUESTION, breakdown.sentenceType)
        assertEquals(ClauseStructure.SIMPLE, breakdown.clauseStructure)
        assertEquals(RegisterLevel.CASUAL, breakdown.register.level)
    }

    @Test
    fun `the reference sentence maps every token to the reference relation`() {
        val breakdown = analyse("本当にあんたがお父さんを殺したの…？")
        val components = breakdown.phrases.flatMap { it.components }

        assertEquals(
            listOf("本当に", "あんた", "が", "お父さん", "を", "殺し", "た", "の"),
            components.map { it.token.surface },
        )
        assertEquals(
            listOf(
                Relation.MODIFIER,
                Relation.AGENT,
                Relation.SUBJECT_MARKER,
                Relation.PATIENT,
                Relation.OBJECT_MARKER,
                Relation.ACTION_CORE,
                Relation.PAST_TENSE,
                // Sentence-final の is the casual question, not the possessive.
                Relation.QUESTION,
            ),
            components.map { it.relation },
        )
        assertTrue(components.all { it.explanation.isNotBlank() })
    }

    @Test
    fun `the reference sentence reports the inflection Kuromoji had all along`() {
        val predicate = analyse("本当にあんたがお父さんを殺したの…？").phrases.last()
        val verb = predicate.components.first()
        assertEquals("殺し", verb.token.surface)
        assertEquals("殺す", verb.token.baseForm)
        // conjugation_type, which nothing read before this section needed it.
        assertTrue(
            "expected a godan verb, explanation was: " + verb.explanation,
            verb.explanation.contains("godan verb"),
        )
        assertTrue(
            "expected the continuative stem, explanation was: " + verb.explanation,
            verb.explanation.contains("ontinuative"),
        )
    }

    // ----------------------------------------------------------------------------------
    // 文節 chunking

    @Test
    fun `chunks the mockup's long bubble the way the reference does`() {
        val breakdown = analyse(LONG)

        assertEquals(
            listOf("まぁ", "見る", "人によって", "色々", "呼ばれてて", "統一されてないけど"),
            breakdown.phrases.map { it.text },
        )
        assertEquals(
            listOf(
                PhraseRole.INTERJECTION,
                // 見る modifies 人 -- attributive, not a second predicate.
                PhraseRole.ADNOMINAL,
                PhraseRole.ADVERBIAL,
                PhraseRole.ADVERBIAL,
                PhraseRole.CONNECTIVE,
                PhraseRole.PREDICATE,
            ),
            breakdown.phrases.map { it.role },
        )
    }

    @Test
    fun `an attributive verb attaches to the noun on its right, not to the predicate`() {
        // The load-bearing edge, and the one a naive "everything points at the verb" rule
        // gets wrong. 見る人 is "the person who looks"; reading 見る as a second main verb
        // changes what the sentence says.
        val phrases = analyse(LONG).phrases
        assertEquals(listOf(5, 2, 4, 4, 5, -1), phrases.map { it.head })
        assertEquals("人によって", phrases[phrases[1].head].text)
    }

    @Test
    fun `every dependency edge points rightward and stays in range`() {
        // Head-final is what makes cycles structurally impossible rather than something
        // to detect. If an edge ever pointed left this would be the first thing to know.
        listOf(LONG, "本当にあんたがお父さんを殺したの…？", "本は高い", "「幽霊」").forEach { text ->
            val phrases = analyse(text).phrases
            phrases.forEach { phrase ->
                assertTrue(
                    text + ": " + phrase.text + " points at " + phrase.head,
                    phrase.head == -1 || phrase.head > phrase.id,
                )
                assertTrue(text + ": head out of range", phrase.head < phrases.size)
            }
            assertEquals(text + ": exactly one root", 1, phrases.count { it.head == -1 })
        }
    }

    @Test
    fun `an interjection attaches to the root rather than to the first verb`() {
        // まぁ does not modify 見る. Pointing it at the nearest predicate would claim it
        // does, which is the kind of confident wrong answer the offline layer must not give.
        val phrases = analyse(LONG).phrases
        assertEquals(PhraseRole.INTERJECTION, phrases.first().role)
        assertEquals(phrases.lastIndex, phrases.first().head)
    }

    @Test
    fun `a suru-verb stays in one chunk`() {
        // IPADIC tags the さ of 統一された as 動詞,自立 with base する -- independent by the
        // tag, and one word in fact. Without the exception in Bunsetsu.startsChunk every
        // suru-verb on the page splits down the middle.
        val breakdown = analyse("統一されてない")
        assertEquals(listOf("統一されてない"), breakdown.phrases.map { it.text })
    }

    @Test
    fun `a suru-compound chunk reports verb, not the noun its head is tagged`() {
        // IPADIC tags 統一 as 名詞,サ変接続 -- a noun. Reporting the head's raw tag would
        // put "noun / predicate" under the sentence's main verb, which is worse than
        // saying nothing at all.
        val predicate = analyse(LONG).phrases.last()
        assertEquals("統一されてないけど", predicate.text)
        assertEquals("動詞", predicate.pos)
        assertEquals("verb", PartOfSpeech.shortLabel(predicate.pos))
        // And the same correction has to reach the token's own relation, or the sentence's
        // main verb is labelled a noun one line further down.
        assertEquals(Relation.ACTION_CORE, predicate.components.first().relation)
    }

    @Test
    fun `chunk parts of speech come from the head otherwise`() {
        val phrases = analyse(LONG).phrases
        assertEquals(
            listOf("感動詞", "動詞", "名詞", "副詞", "動詞", "動詞"),
            phrases.map { it.pos },
        )
        assertEquals(
            listOf("interjection", "verb", "noun", "adverb", "verb", "verb"),
            phrases.map { PartOfSpeech.shortLabel(it.pos) },
        )
    }

    @Test
    fun `chunk romaji is joined without spaces`() {
        // Tier 1 spaces every token because token boundaries are all it knows. A 文節 is
        // one prosodic word, and spacing inside one gives "yoba re te te", which is not a
        // reading of anything.
        val phrases = analyse(LONG).phrases
        assertEquals("yobaretete", phrases[4].romaji)
        assertEquals("hitoniyotte", phrases[2].romaji)
    }

    @Test
    fun `romaji transcribes the kana rather than normalising it`() {
        // Romaji.kt:6 chooses "toukyou" over "Tokyo" and leaves the particle は as "ha",
        // because a form that maps back to the kana one glyph at a time is the better
        // learning aid. It looks wrong; it is the documented decision.
        assertEquals("toukyouhe", analyse("東京へ行く").phrases.first().romaji)
        assertEquals("honha", analyse("本は高い").phrases.first().romaji)
    }

    @Test
    fun `particles assign the roles they mark`() {
        assertEquals(PhraseRole.TOPIC, analyse("本は高い").phrases.first().role)
        assertEquals(PhraseRole.SUBJECT, analyse("先生が来る").phrases.first().role)
        assertEquals(PhraseRole.OBJECT, analyse("本を読む").phrases.first().role)
    }

    @Test
    fun `a bare noun has one chunk and no predicate`() {
        val breakdown = analyse("「幽霊」")
        assertEquals(listOf("幽霊"), breakdown.phrases.map { it.text })
        assertTrue(breakdown.phrases.none { it.role == PhraseRole.PREDICATE })
    }

    // ----------------------------------------------------------------------------------
    // Sentence metadata

    @Test
    fun `a question is read off the ending, with or without a mark`() {
        assertEquals(SentenceType.QUESTION, analyse("行くの？").sentenceType)
        // Manga punctuates as a design choice. The same line without the mark is the
        // same question, and this is the case the punctuation-only reading gets wrong.
        assertEquals(SentenceType.QUESTION, analyse("本当に殺したの").sentenceType)
        assertEquals(SentenceType.QUESTION, analyse("行きますか").sentenceType)
    }

    @Test
    fun `an imperative reads as a command`() {
        // Invisible without Token.conjugationForm: IPADIC tags 来い exactly like 来る.
        assertEquals(SentenceType.COMMAND, analyse("早く来い").sentenceType)
        assertEquals(SentenceType.COMMAND, analyse("やめろ").sentenceType)
    }

    @Test
    fun `a bubble with no predicate is a fragment`() {
        assertEquals(SentenceType.FRAGMENT, analyse("「幽霊」").sentenceType)
        assertEquals(ClauseStructure.FRAGMENT, analyse("「幽霊」").clauseStructure)
    }

    @Test
    fun `clause structure separates subordination from coordination`() {
        assertEquals(ClauseStructure.SIMPLE, analyse("本を読む").clauseStructure)
        assertEquals(ClauseStructure.COMPOUND, analyse("高いけど買う").clauseStructure)
        // Both a connective and an attributive clause. Subordination wins because it is
        // the harder thing to see and the thing the reader is likelier to have missed.
        assertEquals(ClauseStructure.COMPLEX, analyse(LONG).clauseStructure)
    }

    // ----------------------------------------------------------------------------------
    // Relations

    @Test
    fun `a noun takes its relation from the phrase around it`() {
        // Same word class, same tag, three different jobs. Nothing but the enclosing
        // phrase's role separates them.
        assertEquals(Relation.TOPIC, first("本は高い").relation)
        assertEquals(Relation.AGENT, first("先生が来る").relation)
        assertEquals(Relation.PATIENT, first("本を読む").relation)
    }

    @Test
    fun `sentence-final no is a question and a medial one is possessive`() {
        // IPADIC gives the same tag to both. Only the position separates them, and
        // getting it wrong turns a question into a genitive.
        assertEquals(Relation.QUESTION, analyse("殺したの").phrases.last().components.last().relation)
        assertEquals(
            Relation.POSSESSIVE,
            analyse("先生の本").phrases.first().components.last().relation,
        )
    }

    @Test
    fun `auxiliaries map to the machinery they are`() {
        assertEquals(Relation.PAST_TENSE, relationOf("食べた", "た"))
        assertEquals(Relation.NEGATION, relationOf("行かない", "ない"))
        assertEquals(Relation.POLITENESS, relationOf("行きます", "ます"))
        assertEquals(Relation.PASSIVE, relationOf("彼に呼ばれた", "れ"))
    }

    @Test
    fun `every token in a bubble gets a relation and a sentence about it`() {
        // The tier renders one line per component. A component with no prose is a blank
        // row on the phone, so this is a rendering invariant as much as a data one.
        analyse(LONG).phrases.flatMap { it.components }.forEach { component ->
            assertTrue(
                component.token.surface + " has no explanation",
                component.explanation.isNotBlank(),
            )
            assertTrue(
                component.token.surface + " should not be model-written offline",
                !component.fromModel,
            )
        }
    }

    @Test
    fun `a dependent verb is aspect rather than a second action`() {
        // IPADIC tags the て of 呼ばれてて and the いる of 食べている as 動詞,非自立 with base
        // forms てる and いる. Reading them as action cores put three "root action" lines in
        // one phrase and claimed 呼ばれてて contains two verbs.
        assertEquals(Relation.ASPECT, relationOf("食べている", "いる"))
        val yobarete = analyse(LONG).phrases[4].components
        assertEquals(1, yobarete.count { it.relation == Relation.ACTION_CORE })
        assertTrue(yobarete.any { it.relation == Relation.ASPECT })
    }

    @Test
    fun `the suru of a compound verbalises rather than acting`() {
        // 統一 is already the action core of 統一されてない -- Bunsetsu.posOf says so one
        // level up -- so calling さ one too names the action twice and the noun never.
        val predicate = analyse(LONG).phrases.last().components
        assertEquals(Relation.ACTION_CORE, predicate[0].relation)
        assertEquals(Relation.VERBALISER, predicate[1].relation)
        assertEquals(1, predicate.count { it.relation == Relation.ACTION_CORE })
    }

    @Test
    fun `a particle marks and a content word means`() {
        // The rule that keeps the two halves of 人によって from printing the same sentence
        // twice: the noun carries the semantic role, the particle carries the marker.
        val phrase = analyse(LONG).phrases[2]
        assertEquals(Relation.BASIS, phrase.components[0].relation)
        assertEquals(Relation.BASIS_MARKER, phrase.components[1].relation)
        assertTrue(phrase.components[0].explanation != phrase.components[1].explanation)
    }

    @Test
    fun `an inflection does not name a word its family already names`() {
        // 特殊・ナイ englishes to "auxiliary ない", so appending the base form used to give
        // "Dictionary form of the auxiliary ない ない."
        val negation = analyse(LONG).phrases.last().components
            .first { it.relation == Relation.NEGATION }
        assertTrue(
            "duplicated base form: " + negation.explanation,
            !negation.explanation.contains("ない ない"),
        )
        assertTrue(negation.explanation.contains("auxiliary ない"))
    }

    private fun first(text: String) = analyse(text).phrases.first().components.first()

    private fun relationOf(text: String, surface: String) =
        analyse(text).phrases.flatMap { it.components }
            .first { it.token.surface == surface }.relation

    // ----------------------------------------------------------------------------------
    // Register

    @Test
    fun `plain forms read as casual`() {
        assertEquals(RegisterLevel.CASUAL, analyse("行かない").register.level)
        assertEquals(RegisterLevel.CASUAL, analyse(LONG).register.level)
    }

    @Test
    fun `masu and desu read as polite`() {
        assertEquals(RegisterLevel.POLITE, analyse("行きます").register.level)
        assertEquals(RegisterLevel.POLITE, analyse("雨が降りそうです").register.level)
    }

    @Test
    fun `a humble verb outranks the polite ending it sits inside`() {
        // 伺います is not merely polite, and a bubble that mixes registers is
        // characterised by the highest note it reaches. This is the precedence rule.
        assertEquals(RegisterLevel.HUMBLE, analyse("うかがいます").register.level)
        assertEquals(RegisterLevel.HONORIFIC, analyse("いらっしゃいますか").register.level)
    }

    @Test
    fun `an imperative reads as rough`() {
        assertEquals(RegisterLevel.ROUGH, analyse("早く来い").register.level)
        assertEquals(RegisterLevel.ROUGH, analyse("やめろ").register.level)
        assertEquals(RegisterLevel.ROUGH, analyse("行くぜ").register.level)
    }

    @Test
    fun `classical forms read as archaic`() {
        assertEquals(RegisterLevel.ARCHAIC, analyse("これでござる").register.level)
        assertEquals(RegisterLevel.ARCHAIC, analyse("知らぬ").register.level)
        assertEquals(RegisterLevel.ARCHAIC, analyse("「対なるもの」").register.level)
    }

    @Test
    fun `a bubble with no predicate has no register but still says so`() {
        val register = analyse("「幽霊」").register
        assertEquals(RegisterLevel.NEUTRAL, register.level)
        assertTrue(register.evidence.isEmpty())
        // The verdict moved out of the note list and onto Register itself. Leaving the
        // metadata line blank would read as a failure to analyse rather than the answer.
        assertTrue("expected a note explaining the silence", register.note.isNotBlank())
    }

    @Test
    fun `an unreadable table stays silent rather than inventing a verdict`() {
        // No table means nothing was ever asked. Asserting "this bubble has no predicate"
        // over 行きます would be the analyser reporting its own failure as a finding.
        val register = Grammar(GrammarRules.EMPTY).analyse(analyser.tokenize("行きます")).register
        assertEquals(RegisterLevel.NEUTRAL, register.level)
        assertTrue(register.note.isEmpty())
    }

    @Test
    fun `evidence names the chunk rather than the bare auxiliary`() {
        // "casual, because て" teaches nothing -- the trigger is not something you can
        // point at on the page. The enclosing 文節 is.
        val evidence = analyse(LONG).register.evidence
        assertTrue(
            "evidence should quote whole phrases, was " + evidence,
            evidence.any { it == "呼ばれてて" } || evidence.any { it == "統一されてないけど" },
        )
        assertTrue("evidence should not be a bare auxiliary", evidence.none { it == "て" })
    }

    // ----------------------------------------------------------------------------------
    // The matcher

    @Test
    fun `the mockup's long bubble produces the notes the reference produces`() {
        val notes = analyse(LONG).notes

        // The register note is gone from this list -- it is a property of the sentence and
        // lives on Register now. What is left is patterns, in reading order.
        assertEquals(
            listOf("〜によって", "呼ばれ", "〜てて", "〜する", "され", "〜てない", "〜けど"),
            notes.map { it.fragment },
        )
        assertEquals("N3", notes.first { it.fragment == "〜によって" }.jlpt)
        assertTrue(notes.none { it.source == NoteSource.LLM })
    }

    @Test
    fun `a pattern is filed under the phrase that contains it`() {
        val breakdown = analyse(LONG)
        val byPhrase = breakdown.notes.associate { it.fragment to it.phraseId }

        // 〜によって is one token and sits inside 人によって, which is phrase 2.
        assertEquals(2, byPhrase["〜によって"])
        // 〜てない and 〜けど are both inside the predicate, phrase 5.
        assertEquals(5, byPhrase["〜てない"])
        assertEquals(5, byPhrase["〜けど"])

        breakdown.notes.forEach { note ->
            if (note.phraseId >= 0) {
                val phrase = breakdown.phrases[note.phraseId]
                assertTrue(
                    note.fragment + " is filed outside its span",
                    note.from >= phrase.from && note.to <= phrase.to,
                )
            }
        }
        // Nothing in this line straddles a boundary, so the loose list is empty.
        assertTrue(breakdown.looseNotes.isEmpty())
    }

    @Test
    fun `a single-particle note is dropped once the relation layer says the same thing`() {
        // The table has 24 one-token PARTICLE rules -- は, が, を, に, で, の -- and every
        // one of them names what Relations now names. Rendering both printed the same fact
        // twice in adjacent rows, which the preview made obvious.
        val breakdown = analyse("本当にあんたがお父さんを殺したの…？")
        val fragments = breakdown.notes.map { it.fragment }
        assertTrue("が should not also be a note: " + fragments, !fragments.contains("が"))
        assertTrue("を should not also be a note: " + fragments, !fragments.contains("を"))

        // The relation layer still carries the fact, so nothing was lost.
        val ga = breakdown.phrases[1].components.last()
        assertEquals(Relation.SUBJECT_MARKER, ga.relation)
        assertTrue(ga.explanation.isNotBlank())
    }

    @Test
    fun `a multi-token pattern survives the same filter`() {
        // A pattern is not the sum of its tokens: 〜てない token by token says "aspect"
        // and "negative" and misses that the whole thing is a contraction of 〜ていない.
        // Only the single-particle rules are superseded.
        val fragments = analyse(LONG).notes.map { it.fragment }
        assertTrue(fragments.contains("〜てない"))
        assertTrue(fragments.contains("〜によって"))
        assertTrue(fragments.contains("〜けど"))
    }

    @Test
    fun `an inflection note names the verb it came from`() {
        val note = analyse("彼に呼ばれた").notes.first { it.source == NoteSource.INFLECTION }
        assertTrue(
            "expected the note to interpolate 呼ぶ, was: " + note.explanation,
            note.explanation.contains("呼ぶ"),
        )
    }

    @Test
    fun `a more specific rule beats an equally long generic one`() {
        // なきゃ and ない are both one 助動詞 token based on ない. Length cannot separate
        // them, so without the specificity tie-break the obligation rule is silently
        // swallowed by the plain negative and never fires at all.
        val fragments = analyse("行かなきゃ").notes.map { it.fragment }
        assertTrue("expected 〜なきゃ, got " + fragments, fragments.contains("〜なきゃ"))
        assertTrue("〜ない should not also fire", !fragments.contains("〜ない"))
    }

    @Test
    fun `notes do not overlap`() {
        analyse(LONG).notes.zipWithNext().forEach { (left, right) ->
            assertTrue(
                "" + left.fragment + " and " + right.fragment + " overlap",
                left.to < right.from,
            )
        }
    }

    @Test
    fun `trailing kedo is told apart from a mid-sentence one`() {
        val trailing = analyse("高いけど").notes.first { it.fragment.contains("けど") }
        assertTrue(
            "trailing けど should be described as an aside: " + trailing.explanation,
            trailing.explanation.contains("aside"),
        )
        val middle = analyse("高いけど買う").notes.first { it.fragment.contains("けど") }
        assertTrue(
            "mid-sentence けど should be the concessive: " + middle.explanation,
            !middle.explanation.contains("aside"),
        )
    }

    @Test
    fun `every note points at tokens that exist`() {
        val tokens = analyser.tokenize(LONG)
        analyse(LONG).notes.forEach { note ->
            assertTrue("" + note.fragment + " has from > to", note.from <= note.to)
            assertTrue("" + note.fragment + " points past the end", note.to < tokens.size)
        }
    }

    // ----------------------------------------------------------------------------------
    // The model merge

    @Test
    fun `a model's explanation replaces one component and leaves the rest`() {
        val offline = analyse(LONG)
        val target = offline.phrases[2].components.last().token.position
        val merged = grammar.merge(
            breakdown = offline,
            explanations = mapOf(target to "  Front-loads the condition.  "),
            modelId = "Qwen3-1.7B",
        )

        val written = merged.phrases[2].components.last()
        assertEquals("Front-loads the condition.", written.explanation)
        assertTrue(written.fromModel)
        assertEquals("Qwen3-1.7B", written.modelId)

        // Everything else keeps its template, which is the whole bargain: a configured
        // translator makes the section better and its absence never makes it empty.
        val untouched = merged.phrases[0].components.first()
        assertTrue(!untouched.fromModel)
        assertEquals(offline.phrases[0].components.first().explanation, untouched.explanation)
        assertEquals(offline.phrases.map { it.text }, merged.phrases.map { it.text })
    }

    @Test
    fun `a model may correct a head but not reverse one`() {
        val offline = analyse(LONG)
        // Rightward and in range: accepted.
        val fixed = grammar.merge(offline, emptyMap(), heads = mapOf(0 to 4))
        assertEquals(4, fixed.phrases[0].head)

        // Leftward, self-pointing and out of range: all refused. Accepting a leftward edge
        // could produce a cycle, and the sheet's "dim everything but this and its head"
        // walk would not terminate.
        val bad = grammar.merge(offline, emptyMap(), heads = mapOf(3 to 1, 4 to 4, 2 to 99))
        assertEquals(offline.phrases[3].head, bad.phrases[3].head)
        assertEquals(offline.phrases[4].head, bad.phrases[4].head)
        assertEquals(offline.phrases[2].head, bad.phrases[2].head)
    }

    @Test
    fun `merging nothing returns the breakdown untouched`() {
        val offline = analyse(LONG)
        assertEquals(offline, grammar.merge(offline, emptyMap(), emptyMap()))
    }

    // ----------------------------------------------------------------------------------
    // The table itself

    @Test
    fun `the rule table parses and is not trivially small`() {
        assertTrue("expected a real table, got " + rules.size, rules.size > 80)
        assertNotNull(rules.meta["attribution"])
    }

    @Test
    fun `every rule has a unique id and a usable source`() {
        val file = File("../../../tools/grammar_rules.json").let {
            if (it.isFile) it else File("../../tools/grammar_rules.json")
        }
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val array = root.getJSONArray("rules")
        val ids = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val rule = array.getJSONObject(index)
            val id = rule.getString("id")
            assertTrue("duplicate rule id " + id, ids.add(id))
            assertTrue("rule " + id + " has an empty fragment", rule.getString("fragment").isNotEmpty())
            assertTrue("rule " + id + " has no match", rule.getJSONArray("match").length() > 0)
            val source = rule.optString("source", "PATTERN")
            assertTrue(
                "rule " + id + " has unknown source " + source,
                runCatching { NoteSource.valueOf(source) }.isSuccess,
            )
        }

        val register = root.getJSONArray("register")
        for (index in 0 until register.length()) {
            val level = register.getJSONObject(index).getString("level")
            assertTrue(
                "unknown register level " + level,
                runCatching { RegisterLevel.valueOf(level) }.isSuccess,
            )
        }
    }

    @Test
    fun `an empty table costs the notes and not the section`() {
        // A missing or unreadable asset must degrade to "no notes", never to a crash --
        // the phrase cards and the token list are still worth showing.
        val bare = Grammar(GrammarRules.EMPTY)
        val breakdown = bare.analyse(analyser.tokenize("行きます"))
        assertTrue(breakdown.notes.isEmpty())
        assertEquals(RegisterLevel.NEUTRAL, breakdown.register.level)
        assertTrue(breakdown.phrases.isNotEmpty())
        // And the per-token layer needs no table at all, so it is still fully populated.
        assertTrue(breakdown.phrases.first().components.all { it.explanation.isNotBlank() })
    }

    @Test
    fun `an empty bubble analyses to nothing rather than throwing`() {
        assertEquals(0, grammar.analyse(emptyList()).notes.size)
        assertTrue(grammar.analyse(emptyList()).isEmpty)
    }
}
