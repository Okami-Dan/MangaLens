package com.mangareader.analysis

import com.mangareader.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LearnerBreakdownTest {
    private val analyser = Analyser(null, SfxDictionary.parse("{\"entries\":[]}"))
    private val rules = GrammarRules.parse(File("../../../tools/grammar_rules.json").readText(Charsets.UTF_8))
    private val grammar = Grammar(rules)
    private fun sentence(text: String) = analyser.sentence(text, grammar)
    private fun entries(text: String) = sentence(text).let { LearnerBreakdown.entries(it.words, it.grammar) }
    private fun tokens(entry: LearnerEntry) = entry.grammar.filterIsInstance<LearnerGrammar.TokenNote>()
        .map { it.component.token.surface }

    @Test fun `manner suffix forms one noun construction with a base verb bookmark`() {
        val text = "負けっぷりに"
        val source = sentence(text)
        val original = source.words.toList()
        val entry = LearnerBreakdown.entries(source.words, source.grammar).single()
        assertEquals("負けっぷり", entry.word!!.surface)
        assertEquals("負ける", entry.bookmark!!.baseForm)
        assertEquals(source.words.first().id, entry.word.id)
        assertEquals(text.substring(entry.word.start, entry.word.end), entry.word.surface)
        assertEquals(PhraseRole.ADVERBIAL, entry.phrase!!.role)
        assertEquals("名詞", entry.phrase.pos)
        val suffix = entry.grammar.filterIsInstance<LearnerGrammar.Pattern>().single()
        assertEquals("〜っぷり", suffix.note.fragment)
        assertTrue(suffix.note.explanation.contains("manner"))
        assertEquals(listOf("に"), tokens(entry))
        assertEquals(original, source.words)
        assertEquals(PhraseRole.NOMINAL, entries("負けっぷり").single().phrase!!.role)
    }

    @Test fun `plural and name suffixes attach without their own vocabulary cards`() {
        for ((text, base) in listOf("俺たち" to "俺", "ハナちゃん" to "ハナ", "三人" to "三")) {
            val entry = entries(text).single()
            assertEquals(text, entry.word!!.surface)
            assertEquals(base, entry.bookmark!!.baseForm)
            assertEquals(1, entry.grammar.size)
        }
        val plural = entries("俺たち").single().grammar.single() as LearnerGrammar.TokenNote
        assertTrue(plural.component.explanation.contains("group"))
    }

    @Test fun `dictionary grouped productive suffix still saves the base with its dictionary meaning`() {
        val text = "俺たち"
        val raw = analyser.tokenize(text).map { if (it.surface == "俺") it.copy(
            glosses = listOf(Gloss(1, "pn", "", "I", true)), jmdictEntryId = 1) else it }
        val root = Lexical.group(text, raw).first()
        val grouped = root.copy(surface = text, to = raw.last().position, end = text.length,
            reading = raw.joinToString("") { it.reading }, lemma = text, lemmaReading = "おれたち",
            senses = listOf(Gloss(2, "pn", "", "we", true)), entryId = 2)
        val entry = LearnerBreakdown.entries(listOf(grouped), grammar.analyse(raw, listOf(grouped))).single()
        assertEquals("俺", entry.bookmark!!.baseForm)
        assertEquals("I", entry.word!!.senses.single().text)
        assertEquals(1L, entry.bookmark.jmdictEntryId)
        assertEquals(text, entry.word.surface)
        assertEquals(grouped.id, entry.word.id)
    }

    @Test fun `verified lexical compounds keep their headword bookmark`() {
        val text = "合流地点"
        val raw = analyser.tokenize(text)
        val grouped = Lexical.group(text, raw) { form, _, _ -> if (form == text)
            listOf(Jmdict.Entry(1, listOf(Gloss(1,"n","","meeting point",true)), "ごうりゅうちてん"))
            else emptyList() }
        val entry = LearnerBreakdown.entries(grouped, grammar.analyse(raw, grouped)).single()
        assertEquals(text, entry.bookmark!!.baseForm)
        assertEquals("meeting point", entry.word!!.senses.single().text)
        assertTrue(tokens(entry).isEmpty())
    }

    @Test fun `absorbed auxiliaries remain visible outside independent morphology`() {
        for (text in listOf("食べている", "読んじゃったの？", "統一されてないけど")) {
            val entry = entries(text).single()
            assertNotNull(entry.bookmark)
            assertTrue(text, entry.grammar.isNotEmpty())
            assertTrue(entry.morphology.none { PartOfSpeech.isAttached(it.token.partOfSpeech) ||
                it.relation == Relation.VERBALISER })
            val attached = entry.phrase!!.components.filter {
                PartOfSpeech.isAttached(it.token.partOfSpeech) || it.relation == Relation.VERBALISER }
            attached.forEach { component ->
                assertEquals("each grammar token explained once: ${component.token.surface}", 1,
                    entry.grammar.count { line -> when (line) {
                        is LearnerGrammar.TokenNote -> line.component.token.position == component.token.position
                        is LearnerGrammar.Pattern -> component.token.position in line.note.from..line.note.to
                    } })
            }
        }
        assertEquals("統一する", entries("統一されてないけど").single().bookmark!!.baseForm)
    }

    @Test fun `grammar only fragments have notes but no card or bookmark`() {
        for (text in listOf("かな", "っぷり", "は", "です")) {
            val entry = entries(text).single()
            assertNull(entry.word)
            assertNull(entry.bookmark)
            assertTrue(entry.grammar.isNotEmpty())
        }
        val kana = entries("かな").single()
        assertEquals(1, kana.grammar.size)
        assertEquals("かな", (kana.grammar.single() as LearnerGrammar.Pattern).note.fragment)
    }

    @Test fun `screenshot sentence keeps known roles and useful attached notes`() {
        val result = entries("ラストチーム分け変えるって！次は俺たち味方同士かな")
        assertEquals(listOf("ラスト", "チーム分け", "変える", "次", "俺たち", "味方", "同士"),
            result.map { it.word!!.surface })
        assertEquals(PhraseRole.OTHER, result.first().phrase!!.role)
        assertEquals(PhraseRole.PREDICATE, result.first { it.word!!.lemma == "変える" }.phrase!!.role)
        assertEquals(PhraseRole.TOPIC, result.first { it.word!!.lemma == "次" }.phrase!!.role)
        assertEquals(PhraseRole.NOMINAL, result.last().phrase!!.role)
        assertEquals("Other", PhraseRole.OTHER.label)
        assertEquals("Other", Relation.OTHER.label)
        assertEquals(1, result.flatMap { it.grammar }.filterIsInstance<LearnerGrammar.Pattern>()
            .count { it.note.fragment == "かな" })
    }

    @Test fun `tone particles preserve explicit case markers and grammar stays sentence local`() {
        assertEquals(PhraseRole.TOPIC, entries("本はね").single().phrase!!.role)
        assertEquals(PhraseRole.SUBJECT, entries("俺がね").single().phrase!!.role)
        assertEquals(PhraseRole.PREDICATE, entries("読むが").single().phrase!!.role)
        val result = entries("俺はね。君が来る。です？")
        assertNull(result.last().word)
        result.forEach { entry -> entry.grammar.forEach { line ->
            assertTrue(line.from in entry.phrase!!.from..entry.phrase.to)
        } }
        assertEquals(listOf("は", "ね"), tokens(result.first()))
    }

    @Test fun `model explanation provenance survives construction note deduplication`() {
        val source = sentence("同士かな")
        val target = source.tokens.first { it.surface == "か" }.position
        val merged = grammar.merge(source.grammar, mapOf(target to "Wondering aloud about this situation."),
            modelId = "test-model")
        val entry = LearnerBreakdown.entries(source.words, merged).last()
        val pattern = entry.grammar.single() as LearnerGrammar.Pattern
        assertEquals("かな", pattern.note.fragment)
        assertEquals("test-model", pattern.modelExplanations.single().modelId)
    }

    @Test fun `cross phrase notes do not duplicate their generic components`() {
        val source = sentence("俺が君を読む")
        val ga = source.tokens.first { it.surface == "が" }.position
        val wo = source.tokens.first { it.surface == "を" }.position
        val note = GrammarNote("test construction", ga, wo, "meaning", "explanation", NoteSource.PATTERN)
        val breakdown = source.grammar.copy(notes = listOf(note))
        assertEquals(listOf(note), breakdown.looseNotes)
        val result = LearnerBreakdown.entries(source.words, breakdown)
        assertTrue(result.flatMap { tokens(it) }.none { it == "が" || it == "を" })
    }

    @Test fun `source gaps and punctuation are not joined into suffix constructions`() {
        val text = "俺 たち"
        val entry = entries(text).first()
        assertEquals("俺", entry.word!!.surface)
        assertEquals(text.substring(entry.word.start, entry.word.end), entry.word.surface)
        val separate = entries("俺。っぷり")
        assertEquals(2, separate.size)
        assertNull(separate.last().word)
    }

    @Test fun `repaired colloquial words do not expose misleading raw particle notes`() {
        for ((text, surface) in listOf("なーんか出ちゃったねぇ" to "なーんか",
                "もしもーしハナちゃん" to "もしもーし")) {
            val entry = entries(text).first()
            assertEquals(surface, entry.word!!.surface)
            assertNotNull(entry.bookmark)
            assertTrue(entry.grammar.isEmpty())
            assertTrue(entry.morphology.isNotEmpty())
        }
    }

    @Test fun `copula predicates preserve their underlying noun construction POS`() {
        val entry = entries("負けっぷりだ").single()
        assertEquals("負けっぷり", entry.word!!.surface)
        assertEquals(PhraseRole.PREDICATE, entry.phrase!!.role)
        assertEquals("名詞", entry.phrase.pos)
        assertEquals("負ける", entry.bookmark!!.baseForm)
        assertTrue(entry.phrase.components.any { it.relation == Relation.COPULA })
        assertTrue(entry.grammar.any { it is LearnerGrammar.Pattern && it.note.fragment == "だ" })
    }

    @Test fun `prefix attaches forward to its noun instead of the preceding modifier`() {
        val source = sentence("最後のひと試合入るか")
        assertEquals(listOf("最後の", "ひと試合", "入るか"), source.grammar.phrases.map { it.text })
        assertEquals(listOf("saigono", "hitoshiai", "hairuka"), source.grammar.phrases.map { it.romaji })
        assertEquals(PhraseRole.ADNOMINAL, source.grammar.phrases.first().role)
        assertEquals("名詞", source.grammar.phrases[1].pos)
        val result = LearnerBreakdown.entries(source.words, source.grammar)
        assertEquals(listOf("最後", "試合", "入る"), result.map { it.word!!.surface })
        assertEquals(listOf("の"), tokens(result.first()))
        val match = result[1]
        assertEquals("試合", match.bookmark!!.baseForm)
        val prefix = match.grammar.filterIsInstance<LearnerGrammar.TokenNote>().single().component
        assertEquals("ひと", prefix.token.surface)
        assertEquals(Relation.PREFIX, prefix.relation)
        assertTrue(prefix.explanation.contains("one"))
        assertTrue(prefix.explanation.contains("ひと試合"))
        assertEquals(source.tokens.first { it.surface == "ひと" }.position, prefix.token.position)
        assertEquals(source.words.first { it.surface == "試合" }.id, match.word!!.id)
        // Phrase grouping is also repaired from already-persisted words and original tokens.
        assertEquals(source.grammar, grammar.analyse(source.tokens, source.words))
    }

    @Test fun `prefix attachment does not cross source gaps punctuation or sentences`() {
        for (text in listOf("最後のひと 試合入るか", "最後のひと、試合入るか", "最後のひと。試合入るか")) {
            // Exercise persisted prefix morphology even when an edited source adds a
            // boundary. Fresh Kuromoji calls tag standalone ひと as an independent noun.
            val raw = analyser.tokenize(text).map { if (it.surface == "ひと")
                it.copy(partOfSpeech = "接頭詞,名詞接続,*,*") else it }
            val words = Lexical.group(text, raw)
            val breakdown = grammar.analyse(raw, words)
            assertEquals(text, listOf("最後の", "ひと", "試合", "入るか"),
                breakdown.phrases.map { it.text })
            val result = LearnerBreakdown.entries(words, breakdown)
            val orphan = result.single { it.word == null }
            assertEquals(listOf("ひと"), tokens(orphan))
            assertEquals("A prefix requiring a following word.",
                (orphan.grammar.single() as LearnerGrammar.TokenNote).component.explanation)
            assertTrue(result.first { it.word?.surface == "試合" }.grammar.isEmpty())
        }
        val noun = entries("最後の人が入る")
        assertEquals("人", noun[1].word!!.surface)
        assertEquals(PhraseRole.SUBJECT, noun[1].phrase!!.role)
        assertTrue(noun.flatMap { it.grammar }.filterIsInstance<LearnerGrammar.TokenNote>()
            .none { it.component.relation == Relation.PREFIX })
    }

    @Test fun `prefix heads retain noun and predicate roles including suru constructions`() {
        assertEquals(PhraseRole.NOMINAL, entries("ひと試合").single().phrase!!.role)
        assertEquals(PhraseRole.SUBJECT, entries("ひと試合が始まる").first().phrase!!.role)
        val suru = entries("ひと試合する").single()
        assertEquals("動詞", suru.phrase!!.pos)
        assertEquals(PhraseRole.PREDICATE, suru.phrase.role)
        assertEquals("試合する", suru.bookmark!!.baseForm)
    }

    @Test fun `pattern readings use matched tokens including vocabulary and corrected readings`() {
        val kana = entries("かな").single().grammar.single() as LearnerGrammar.Pattern
        assertEquals("kana", Romaji.of(kana.reading))
        val manner = entries("負けっぷりに").single().grammar.filterIsInstance<LearnerGrammar.Pattern>().single()
        assertEquals("ppuri", Romaji.of(manner.reading))
        val source = sentence("食べている")
        val note = GrammarNote("〜ている", source.tokens.first().position, source.tokens.last().position,
            "aspect", "test whole-span reading", NoteSource.PATTERN, phraseId = source.grammar.phrases.single().id)
        val corrected = source.grammar.copy(notes = listOf(note), phrases = source.grammar.phrases.map { phrase ->
            phrase.copy(components = phrase.components.map { component ->
                if (component.token.surface == "いる") component.copy(token = component.token.copy(
                    reading = "おる", readingIsUser = true, romaji = "stale")) else component
            })
        })
        val entry = LearnerBreakdown.entries(source.words, corrected).single()
        assertEquals("tabeteoru", Romaji.of((entry.grammar.single() as LearnerGrammar.Pattern).reading))
        assertEquals("tabeteoru", Romaji.of(LearnerBreakdown.reading(note,
            corrected.phrases.flatMap { it.components }.reversed())))
    }

    @Test fun `split auxiliary romaji retains gemination and respects adjacent corrected readings`() {
        val entry = entries("読んじゃったの？").single()
        val auxiliary = entry.grammar.filterIsInstance<LearnerGrammar.TokenNote>()
            .single { it.component.token.surface == "じゃっ" }
        assertEquals("jat", auxiliary.romaji)
        val source = entry.phrase!!.components
        val corrected = source.map { component -> if (component.token.surface == "た")
            component.copy(token = component.token.copy(reading = "だ", readingIsUser = true, romaji = "stale"))
            else component }
        assertEquals("jad", LearnerBreakdown.componentRomaji(auxiliary.component, corrected))
        val gap = source.map { component -> if (component.token.surface == "た")
            component.copy(token = component.token.copy(start = component.token.start + 1)) else component }
        assertEquals(Romaji.of(auxiliary.component.token.reading),
            LearnerBreakdown.componentRomaji(auxiliary.component, gap))
    }

    @Test fun `compound toshiteno has one complete offline note beneath the preceding noun`() {
        for (text in listOf("先勝としての", "選手としての役割", "戦勝としての")) {
            val source = sentence(text)
            val result = LearnerBreakdown.entries(source.words, source.grammar)
            val entry = result.first()
            assertNotNull(entry.word)
            assertEquals(PhraseRole.ADNOMINAL, entry.phrase!!.role)
            val note = entry.grammar.single() as LearnerGrammar.Pattern
            assertEquals("としての", note.note.fragment)
            assertEquals("as / in the capacity of", note.note.gloss)
            assertTrue(note.note.explanation.contains("following noun"))
            assertEquals("toshiteno", Romaji.of(note.reading))
            assertEquals(2, note.note.to - note.note.from + 1)
            assertTrue(note.modelExplanations.isEmpty())
            assertEquals(source.words.first().id, entry.word!!.id)
            assertEquals(source.words.first().lemma, entry.bookmark!!.baseForm)
            assertTrue(result.mapNotNull { it.word }.none { it.surface == "として" })
            assertEquals(source.grammar, grammar.analyse(source.tokens, source.words))
        }
    }

    @Test fun `compound toshite is an offline adverbial note and works as a grammar only fragment`() {
        val entry = entries("先生として働く").first()
        assertEquals(PhraseRole.ADVERBIAL, entry.phrase!!.role)
        val pattern = entry.grammar.single() as LearnerGrammar.Pattern
        assertEquals("として", pattern.note.fragment)
        assertEquals("toshite", Romaji.of(pattern.reading))
        for (text in listOf("として", "としての")) {
            val orphan = entries(text).single()
            assertNull(orphan.word)
            assertNull(orphan.bookmark)
            assertEquals(text, (orphan.grammar.single() as LearnerGrammar.Pattern).note.fragment)
        }
    }

    @Test fun `compound role notes do not mistake volitional attempts or cross punctuation`() {
        val attempt = sentence("勉強しようとしている")
        assertTrue(attempt.grammar.notes.none { it.fragment in setOf("として", "としての") })
        val isolated = entries("選手として。の")
        assertEquals("として", (isolated.first().grammar.single() as LearnerGrammar.Pattern).note.fragment)
        assertNull(isolated.last().word)
        assertTrue(isolated.flatMap { it.grammar }.filterIsInstance<LearnerGrammar.Pattern>()
            .none { it.note.fragment == "としての" })
        val source = sentence("選手としての役割")
        val target = source.tokens.first { it.surface == "として" }.position
        val merged = grammar.merge(source.grammar, mapOf(target to "The player's capacity here."), modelId = "test-model")
        val pattern = LearnerBreakdown.entries(source.words, merged).first().grammar.single() as LearnerGrammar.Pattern
        assertEquals("test-model", pattern.modelExplanations.single().modelId)
    }
}
