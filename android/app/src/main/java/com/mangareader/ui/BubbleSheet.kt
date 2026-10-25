package com.mangareader.ui

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.analysis.PartOfSpeech
import com.mangareader.analysis.LearnerBreakdown
import com.mangareader.analysis.LearnerEntry
import com.mangareader.analysis.LearnerGrammar
import com.mangareader.database.PageEntity
import com.mangareader.database.RegionEntity
import com.mangareader.database.SavedWordEntity
import com.mangareader.database.TokenEntity
import com.mangareader.model.Component
import com.mangareader.model.GrammarBreakdown
import com.mangareader.model.GrammarNote
import com.mangareader.model.NoteSource
import com.mangareader.model.Phrase
import com.mangareader.model.RegisterLevel
import com.mangareader.model.Ruby
import com.mangareader.model.SfxMatch
import com.mangareader.model.Token
import com.mangareader.model.LexicalUnit
import com.mangareader.model.TranslationContext
import com.mangareader.model.SentenceAnalysis
import com.mangareader.database.LexicalSerial
import com.mangareader.analysis.Lexical
import com.mangareader.analysis.Furigana
import com.mangareader.analysis.Romaji

/**
 * Section 3.3's bubble sheet -- "the core screen" -- in the design from
 * `Design mockups/Page view with overlay.pdf`.
 *
 * The tiers are the same three, but the mockup numbers them and puts them in reading
 * order, which is not the order they were built in:
 *
 *  1. **Detection** -- the recognized Japanese with furigana above the kanji, romaji
 *     beneath it if section 2.3's toggle is on, and the edit affordance section 3.3
 *     requires because "OCR will be wrong sometimes and the user must be able to
 *     correct it". Always open.
 *  2. **Translation** -- the free translation. Tier 0 is the default and stays it, so
 *     the state most users see is "no translator configured" with a pointer to Options,
 *     which is a real state rather than an empty box.
 *  3. **Sentence breakdown** -- interactive phrase links followed by lexical units with
 *     readings, dictionary senses, POS, sentence function, inflections and saved words.
 *     Expandable morphology retains the fine-grained tokens. Optional model-written
 *     meanings and explanations are labelled; the offline result needs no inference.
 *
 * Section 3.4's SFX case adds to those rather than replacing them: a region stage 6
 * ruled onomatopoeia gets its reading and a gloss from the bundled glossary *above* the
 * translation and the word list, and only tier 4 is withheld, because a two-mora sound
 * has no sentence structure to break down.
 */
@Composable
fun BubbleSheet(
    page: PageEntity,
    regions: List<RegionEntity>,
    region: RegionEntity,
    tokens: List<TokenEntity>,
    savedWords: List<SavedWordEntity>,
    settings: SheetSettings,
    analysis: AnalysisController,
    translation: TranslationController,
    grammar: GrammarController,
    onSelect: (Long?) -> Unit,
) {
    // Navigation follows visible bubbles, but request validation needs every region
    // sent to the translator, including low-confidence OCR hidden by the reader.
    val ordered = regions.filter(::isVisibleRegion).sortedBy { it.readingIndex }
    val at = ordered.indexOfFirst { it.regionId == region.regionId }
    val regionTokens = remember(tokens, region.regionId) {
        tokens.filter { it.regionId == region.regionId }
            .sortedBy { it.position }
    }
    val words = remember(regionTokens) { regionTokens.map { it.toToken() } }
    val units = remember(region.lexicalUnits, words, region.text()) {
        LexicalSerial.decode(region.lexicalUnits).ifEmpty { Lexical.group(region.text(), words) }
    }
    val series by remember(page.id) { translation.observeContext(page.id) }
        .collectAsState(initial = TranslationContext())
    val context = series.copy(note = translation.contextNote)
    val sfx = region.toRegion().sfx

    // Recomputed on open rather than stored. It is a pure function of token rows the
    // database already holds and runs in microseconds over a bubble, and recomputing
    // means a rule added in a later version applies to the whole existing library at
    // once instead of only to pages captured after the update.
    val sentence = remember(words, units) {
        SentenceAnalysis(words, units, analysis.grammar.analyse(words, units))
    }
    val offline = sentence.grammar
    // Anything a model already wrote about this bubble, folded in per token. The answer
    // is keyed on Token.position, so an explanation replaces the template on exactly the
    // component it was written for rather than being appended as a second list.
    val answer = grammar.answerFor(page.id, region.regionId, region.text(), offline.phrases, units, context)
    val breakdown = remember(offline, answer) {
        analysis.grammar.merge(offline, answer.explanations, answer.heads, grammar.label)
    }

    LaunchedEffect(page.id, region.regionId, region.text(), offline, units, context, grammar.identity) {
        grammar.load(page.id, region.regionId, region.text(), offline.phrases, units, context)
    }

    val scroll = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .scrollbar(scroll, MangaPalette.Orange)
            .verticalScroll(scroll)
            .padding(horizontal = ScreenMargin)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SheetHeader(page, region, at, ordered.size, onSelect = { index ->
            ordered.getOrNull(index)?.let { onSelect(it.regionId) }
        })

        TierOne(region, words, regionTokens, settings, analysis)

        // The glossary card is additive now, not a replacement. A sound effect is
        // translated like everything else (section 2.6), so withholding tiers 2 and 3
        // from it would hide work that has already been done.
        if (isSfx(page, region)) {
            SfxTier(sfx, region.text())
        }

        // Translation before the breakdown: the mockup reads meaning first, detail
        // second, which is also the order a learner wants them in.
        val displayed = remember(region, regions, page.kind, context) {
            val model = region.translatorId
            if (model != null && !translation.matchesRequest(regions.map { it.toRegion() }, model,
                    page.kind == com.mangareader.model.PageKind.COVER, context, region.translationKey))
                region.copy(translation = null, translatorId = null) else region
        }
        TierThree(page, displayed, translation)
        if (isSfx(page, region)) TierTwo(
            tokens = words,
            savedWords = savedWords,
            onPin = { token ->
                analysis.pin(token, page.id, region.regionId, region.text())
            },
            onUnpin = { token ->
                analysis.unpin(token.baseForm, token.baseReading.ifEmpty { token.reading })
            },
            dictionaryReady = analysis.dictionaryReady,
        )

        // Tier 4 stays off for a sound effect. Its empty state tells the reader to
        // re-analyse the page, which would be a lie here: stage 6 did run, and ドキドキ
        // has no sentence structure to find.
        if (!isSfx(page, region)) {
            TierFour(
                breakdown = breakdown,
                offline = offline,
                settings = settings,
                grammar = grammar,
                pageId = page.id,
                regionId = region.regionId,
                text = region.text(),
                words = units,
                meanings = answer.meanings,
                savedWords = savedWords,
                dictionaryReady = analysis.dictionaryReady,
                lexicon = analysis.grammarLexicon,
                onPin = { token -> analysis.pin(token, page.id, region.regionId, region.text()) },
                onUnpin = { token -> analysis.unpin(token.baseForm, token.baseReading.ifEmpty { token.reading }) },
            )
        }
    }
}

/** The two reading aids section 2.3 makes settings, passed down rather than looked up. */
data class SheetSettings(val furigana: Boolean, val romaji: Boolean)

@Composable
private fun SheetHeader(
    page: PageEntity,
    region: RegionEntity,
    at: Int,
    total: Int,
    onSelect: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "1. Detection",
            style = MaterialTheme.typography.labelMedium,
            color = MangaPalette.Dim,
        )
        if (isSfx(page, region)) {
            Text(
                "  " + kindLabel(page, region),
                style = MaterialTheme.typography.labelMedium,
                color = MangaPalette.Violet,
            )
        }
        if (isLowConfidence(region)) {
            Text(
                "  low confidence",
                style = MaterialTheme.typography.labelMedium,
                color = MangaPalette.Alarm,
            )
        }
        if (region.ocrTextEdited != null) {
            Text(
                "  edited",
                style = MaterialTheme.typography.labelMedium,
                color = MangaPalette.Violet,
            )
        }
        Box(Modifier.weight(1f))
        IconButton(
            onClick = { onSelect(at - 1) },
            enabled = at > 0,
            modifier = Modifier.size(26.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateBefore,
                contentDescription = "Previous bubble",
                tint = MangaPalette.Ink,
            )
        }
        Text(
            "#" + (region.readingIndex + 1) + " of " + total,
            style = MaterialTheme.typography.labelSmall,
            color = MangaPalette.Faint,
        )
        IconButton(
            onClick = { onSelect(at + 1) },
            enabled = at >= 0 && at < total - 1,
            modifier = Modifier.size(26.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.NavigateNext,
                contentDescription = "Next bubble",
                tint = MangaPalette.Ink,
            )
        }
    }
}

/** Tier 1: the original, with furigana, and the edit affordance. */
@Composable
private fun TierOne(
    region: RegionEntity,
    tokens: List<Token>,
    tokenRows: List<TokenEntity>,
    settings: SheetSettings,
    analysis: AnalysisController,
) {
    var editing by remember(region.pageId, region.regionId) { mutableStateOf(false) }

    if (editing) {
        var value by remember(region.pageId, region.regionId) {
            mutableStateOf(TextFieldValue(region.text()))
        }
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("Recognized text") },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.titleLarge,
        )
        if (value.text == region.text()) {
            tokenRows.filter { row -> row.surface.any(com.mangareader.analysis.Kana::isKanji) }
                .forEach { row -> ReadingEditorRow(row, analysis) }
        } else {
            Text(
                "Save the text, then correct its new word readings.",
                style = MaterialTheme.typography.bodySmall,
                color = MangaPalette.Dim,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(shape = RectangleShape, onClick = { editing = false }) { Text("Cancel") }
            if (region.ocrTextEdited != null) {
                TextButton(shape = RectangleShape,
                    onClick = {
                        analysis.editRegionText(region.pageId, region.regionId, null)
                        editing = false
                    },
                ) { Text("Reset") }
            }
            Box(Modifier.weight(1f))
            TextButton(shape = RectangleShape,
                onClick = {
                    analysis.editRegionText(region.pageId, region.regionId, value.text)
                    editing = false
                },
            ) { Text("Save") }
        }
        Text(
            "Saving re-runs the word breakdown for this bubble only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.weight(1f)) {
            if (region.text().isEmpty()) {
                Text(
                    "(no text recognized)",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FuriganaText(
                    segments = segments(tokens, region.text()),
                    showFurigana = settings.furigana,
                )
            }
        }
        IconButton(onClick = { editing = true }) {
            Icon(Icons.Filled.Edit, contentDescription = "Correct the recognized text")
        }
    }

    if (settings.romaji && tokens.isNotEmpty()) {
        val romaji = tokens.filter { it.romaji.isNotEmpty() }.joinToString(" ") { it.romaji }
        if (romaji.isNotEmpty()) {
            Text(
                romaji,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MangaPalette.Dim,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    Text(
        "detection " + percent(region.score) +
            (region.ocrConfidence?.let { "  ·  OCR " + percent(it) } ?: ""),
        style = MaterialTheme.typography.labelSmall,
        color = MangaPalette.Faint,
        modifier = Modifier.padding(top = 3.dp),
    )
}

@Composable
private fun ReadingEditorRow(row: TokenEntity, analysis: AnalysisController) {
    var reading by remember(row.pageId, row.regionId, row.position, row.userReading) {
        mutableStateOf(row.userReading ?: row.reading)
    }
    val validReading = com.mangareader.analysis.Kana.kanaReading(reading.trim()).isNotEmpty()
    Column {
        Text(row.surface, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = reading,
            onValueChange = { reading = it },
            label = { Text("Reading") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(
                shape = RectangleShape,
                enabled = validReading && reading != (row.userReading ?: row.reading),
                onClick = {
                    analysis.editTokenReading(
                        row.pageId, row.regionId, row.position, row.surface, reading,
                    )
                },
            ) { Text("Save reading") }
            if (row.userReading != null) {
                TextButton(
                    shape = RectangleShape,
                    onClick = {
                        analysis.editTokenReading(
                            row.pageId, row.regionId, row.position, row.surface, null,
                        )
                    },
                ) { Text("Reset") }
            }
        }
        if (reading.isNotBlank() && !validReading) {
            Text(
                "Enter the reading in hiragana or katakana.",
                style = MaterialTheme.typography.bodySmall,
                color = MangaPalette.Alarm,
            )
        }
    }
}

/**
 * Japanese with ruby above it.
 *
 * Compose has no ruby run, so each segment is its own two-line column and a [FlowRow]
 * wraps them. The ruby line is always laid out, empty or not, which is what keeps the
 * base text on one baseline across a line that mixes kanji and kana -- without it the
 * kana segments ride up by the height of the ruby.
 *
 * Section 2.2's "the original text is never hidden" applies here too: turning furigana
 * off hides the ruby, not the line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FuriganaText(
    segments: List<Ruby>,
    showFurigana: Boolean,
    // Tier 1 renders a whole line and takes the default; a tier 4 token box is one word
    // inside a card and would blow the card's width at headline size.
    style: TextStyle = MaterialTheme.typography.headlineSmall,
) {
    val rubyStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp)
    FlowRow(modifier = Modifier.semantics(mergeDescendants = true) {
        contentDescription = segments.joinToString("") { it.text }
    }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        segments.forEach { segment ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (showFurigana) {
                    if (segment.ruby.isEmpty()) {
                        Box(Modifier.height(14.dp))
                    } else {
                        Text(
                            segment.ruby,
                            style = rubyStyle,
                            color = MangaPalette.Violet,
                        )
                    }
                }
                Text(segment.text, style = style)
            }
        }
    }
}

/** Tier 2: the word breakdown. */
@Composable
private fun TierTwo(
    tokens: List<Token>,
    savedWords: List<SavedWordEntity>,
    onPin: (Token) -> Unit,
    onUnpin: (Token) -> Unit,
    dictionaryReady: Boolean,
) {
    var expanded by remember { mutableStateOf(true) }
    val content = tokens.count { it.isContentWord }

    TierHeader(
        title = "3. Words breakdown",
        detail = if (tokens.isEmpty()) {
            ""
        } else {
            "" + tokens.size + " tokens · " + content + " content words"
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )

    AnimatedVisibility(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (tokens.isEmpty()) {
                Text(
                    "No tokens yet — re-analyse the page to run stage 6 over it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tokens.isNotEmpty() && !dictionaryReady) {
                Text(
                    "The dictionary is still unpacking, so these rows have readings but " +
                        "no meanings yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            tokens.forEach { token ->
                val reading = token.baseReading.ifEmpty { token.reading }
                val pinned = savedWords.any {
                    it.baseForm == token.baseForm && it.reading == reading
                }
                TokenRow(
                    token = token,
                    pinned = pinned,
                    onPin = { if (pinned) onUnpin(token) else onPin(token) },
                )
            }
        }
    }
}

@Composable
private fun TokenRow(token: Token, pinned: Boolean, onPin: () -> Unit) {
    // Particles and inflectional endings are dimmed rather than dropped: they are what
    // makes the sentence a sentence, and a learner reading a bubble needs to see that
    // the breakdown accounts for every character on the page.
    val emphasis = if (token.isContentWord) 1f else 0.55f
    Surface(
        color = if (pinned) MangaPalette.LilacTint else MangaPalette.White,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = token.isContentWord, onClick = onPin),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        token.surface,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (token.isContentWord) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                        color = MangaPalette.Ink.copy(alpha = emphasis),
                    )
                    if (token.reading.isNotEmpty() && token.reading != token.surface) {
                        Text(
                            "  " + token.reading,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (token.baseForm != token.surface) {
                        Text(
                            "  ← " + token.baseForm,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    token.posLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (token.glosses.isNotEmpty()) {
                    token.glosses.take(3).forEachIndexed { index, gloss ->
                        Text(
                            "" + (index + 1) + ". " + gloss.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = emphasis),
                        )
                    }
                } else if (token.isContentWord) {
                    Text(
                        "no dictionary entry",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (token.isContentWord) {
                Icon(
                    imageVector = if (pinned) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    contentDescription = if (pinned) "Saved" else "Save this word",
                    tint = if (pinned) MangaPalette.Violet else MangaPalette.Faint,
                )
            }
        }
    }
}

/** One learner-facing word: a meaning, its inflection, and its function in the sentence. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UnifiedWord(word: LexicalUnit, phrase: Phrase?, meaning: String?, pinned: Boolean,
    settings: SheetSettings, dimmed: Boolean,
    morphology: List<Component> = phrase?.components.orEmpty().filter {
        it.token.position in word.from..word.to && !PartOfSpeech.isAttached(it.token.partOfSpeech) &&
            it.relation != com.mangareader.model.Relation.VERBALISER
    }, onPin: () -> Unit) {
    var details by remember(word) { mutableStateOf(false) }
    if (word.partOfSpeech.startsWith("記号")) {
        Text(word.surface, style = MaterialTheme.typography.bodySmall, color = MangaPalette.Dim)
        return
    }
    Surface(color = if (pinned) MangaPalette.LilacTint else MangaPalette.White,
        modifier = Modifier.fillMaxWidth().alpha(if (dimmed) .55f else 1f)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val components = morphology
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FuriganaText(Furigana.align(word.surface, word.reading), settings.furigana,
                        MaterialTheme.typography.titleMedium)
                    if (settings.romaji && word.reading.isNotEmpty()) Text(Romaji.of(word.reading),
                        style = MaterialTheme.typography.bodySmall, color = MangaPalette.Dim)
                }
                IconButton(onClick = onPin, modifier = Modifier.size(48.dp)) {
                    Icon(if (pinned) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                        contentDescription = if (pinned) "Unsave word" else "Save word",
                        tint = if (pinned) MangaPalette.Violet else MangaPalette.Faint)
                }
            }
            Text(word.posLabel + " · " + (phrase?.role?.label ?: "Other"),
                style = MaterialTheme.typography.labelSmall, color = MangaPalette.Dim)
            if (word.lemma != word.surface) Text(word.lemma +
                word.lemmaReading.takeIf { it.isNotEmpty() }?.let { " / $it" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall)
            Text(word.senses.firstOrNull()?.text ?: "No verified dictionary meaning",
                style = MaterialTheme.typography.bodyMedium)
            if (!meaning.isNullOrBlank()) {
                Text(meaning, style = MaterialTheme.typography.bodyMedium)
                Text("In this sentence · model explanation", style = MaterialTheme.typography.labelSmall,
                    color = MangaPalette.Dim)
            }
            if (word.inflections.isNotEmpty()) Text(word.inflections.joinToString(" → "),
                style = MaterialTheme.typography.bodySmall)
            // Attached grammar lives below the card; only independent morphology expands.
            if (word.senses.size > 1 || components.isNotEmpty()) TextButton(shape = RectangleShape,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    onClick = { details = !details }) { Text(if (details) "Less detail" else "Definition & morphology") }
            if (details) {
                word.senses.drop(1).forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                components.forEach { component -> ComponentLine(component, settings.romaji,
                    LearnerBreakdown.componentRomaji(component, phrase?.components.orEmpty())) }
            }
        }
    }
}

/** A vocabulary card followed by its always-visible, old-style grammar notes. */
@Composable
internal fun LearnerEntryView(entry: LearnerEntry, meaning: String?, pinned: Boolean,
    settings: SheetSettings, dimmed: Boolean, onPin: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entry.word?.let { word ->
            UnifiedWord(word, entry.phrase, meaning, pinned, settings, dimmed, entry.morphology, onPin)
        }
        if (entry.grammar.isNotEmpty()) Column(Modifier.fillMaxWidth()
            .alpha(if (dimmed) .55f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            entry.grammar.forEach { line ->
                when (line) {
                    is LearnerGrammar.TokenNote -> Column(Modifier.fillMaxWidth()
                        .drawBehind { drawRect(MangaPalette.Silver, size = Size(1.dp.toPx(), size.height)) }
                        .padding(start = 8.dp)) {
                            ComponentLine(if (line.component.fromModel) line.component.copy(
                                explanation=line.attachmentExplanation,fromModel=false,modelId="")
                                else line.component, settings.romaji, line.romaji)
                            if (line.dictionaryMeanings.isNotEmpty()) {
                                Text("Dictionary meanings.", style = MaterialTheme.typography.labelSmall,
                                    color = MangaPalette.VioletInk)
                                line.dictionaryMeanings.forEach { meaning ->
                                    Text(meaning.text, style = MaterialTheme.typography.bodySmall,
                                        color = MangaPalette.Dim)
                                }
                                Text("JMdict · EDRDG", style = MaterialTheme.typography.labelSmall,
                                    color = MangaPalette.Faint)
                                if (line.dictionaryMeanings.size > 1 || line.dictionaryMeanings.any { ';' in it.text })
                                    Text("Context determines which meaning applies.",
                                        style = MaterialTheme.typography.bodySmall, color = MangaPalette.Dim)
                            }
                            if (line.component.fromModel) {
                                Text(line.component.explanation, style=MaterialTheme.typography.bodySmall)
                                Text(line.component.modelId.ifEmpty { "model" },
                                    style=MaterialTheme.typography.labelSmall, color=MangaPalette.Violet)
                            }
                        }
                    is LearnerGrammar.Pattern -> ModelPatternNote(line.note, line.modelExplanations,
                        line.reading, settings.romaji)
                }
            }
        }
    }
}

/** Section 3.4: recognized string, reading, and a gloss from the bundled glossary. */
@Composable
private fun SfxTier(sfx: SfxMatch?, text: String) {
    Surface(
        color = MangaPalette.LilacTint,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (sfx == null) {
                Text("Not in the SFX glossary", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The glossary is hand-written and grows; this reading is not in " +
                        "it yet. The translation below is the model's reading of it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                return@Column
            }
            Text(
                sfx.text + " — " + sfx.reading,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(sfx.gloss, style = MaterialTheme.typography.bodyMedium)
            Text(
                sfx.kind + describeMatch(sfx.match, text, sfx.text),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.75f),
            )
        }
    }
}

/** How the glossary got there. An inexact match should look inexact. */
private fun describeMatch(match: String, recognized: String, entry: String): String = when (match) {
    "exact" -> ""
    "normalized" -> "  ·  matched after normalising " + recognized
    "repeat" -> "  ·  matched " + recognized + " as a repeat of " + entry
    "prefix" -> "  ·  " + recognized + " matched on its opening"
    else -> "  ·  " + match
}

/**
 * Tier 3: the free translation.
 *
 * Four states, and the two that look alike are the ones worth being careful about. A
 * bubble with no English because *nothing has translated this page* offers to translate
 * it; a bubble with no English because *the model was asked and declined* says so, and
 * says why -- section 7 has the model return null rather than invent text for a bubble it
 * cannot read, and hiding that would turn an honest refusal into an apparent bug.
 *
 * `translatorId` is what separates them: stage 7 stamps it on every bubble it was allowed
 * to see, whether or not that bubble came back with text.
 */
@Composable
private fun TierThree(
    page: PageEntity,
    region: RegionEntity,
    translation: TranslationController,
) {
    val running = translation.running?.takeIf { it.pageId == page.id }
    val translated = !region.translation.isNullOrEmpty()
    val attempted = region.translatorId != null
    var expanded by remember(page.id) { mutableStateOf(true) }

    TierHeader(
        title = "2. Translation",
        detail = when {
            running != null -> "translating…"
            translated -> translation.labelFor(region.translatorId)
            attempted -> "not recognized"
            !translation.configured -> "no translator configured"
            else -> "not translated yet"
        },
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )

    AnimatedVisibility(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                translated -> {
                    Text(
                        region.translation.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MangaPalette.Ink,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f))
                        TextButton(shape = RectangleShape,
                            onClick = { translation.translate(page.id, force = true) },
                            enabled = running == null,
                        ) { Text("Translate again") }
                    }
                }

                attempted -> Text(
                    "The translator could not read this bubble and declined to guess " +
                        "rather than invent a line. Correcting the recognized text above " +
                        "and translating again is the fix.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                !translation.configured -> Text(
                    "The dictionary layer above is fully " +
                        "offline and needs no model, no account and no network. Choose a " +
                        "translator in Settings to fill this in.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                running != null -> TranslationProgress(running)

                else -> {
                    Text(
                        "The whole page goes to the translator in one call, " +
                            "so it can use the surrounding bubbles as context. This " +
                            "translates every bubble on the page, not just this one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(shape = RectangleShape, onClick = { translation.translate(page.id) }) {
                        Text("Translate page")
                    }
                }
            }

            if (running != null && translated) TranslationProgress(running)

            translation.error?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TranslationProgress(running: TranslationController.Running) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (running.fraction < 0f) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(
                progress = { running.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            if (running.total > 0) {
                running.detail + "  " + running.done + " of " + running.total + " bubbles"
            } else {
                running.detail
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Tier 4: the grammar breakdown.
 *
 * One tree, top to bottom, in the order a learner needs it.
 *
 *  1. **What kind of sentence this is** -- type, register and clause structure, three
 *     words in the metadata line, with the evidence the register was read off underneath.
 *  2. **What it is made of** -- a card per 文節, holding a box per token with its furigana
 *     and its part of speech. Tapping a card lights the phrase it attaches to.
 *  3. **What every piece is doing** -- a block per phrase, one line per token, plus any
 *     multi-token pattern that falls inside that phrase with its JLPT level.
 *
 * This replaced a flat chip strip beside a flat note list, which were joined by nothing:
 * the strip showed only each phrase's joined text, so the tokens inside a phrase were
 * invisible at this tier, and a note could not be reached from the phrase containing it.
 *
 * **Tier 2 and tier 4 both list every token, and that is not a duplication to collapse.**
 * Tier 2 says what a word *means* -- dictionary form, glosses, pin it to the saved list.
 * Tier 4 says what it *does* -- the job it holds in this sentence. A learner who knows
 * every word in a line and still cannot read it needs the second thing, which is the whole
 * reason this section exists.
 *
 * All of it is offline. `Grammar` is a pure function of the tokens plus a bundled rule
 * table, so this section is filled in on a fresh install with no model, no account and no
 * network -- the same tier-0 guarantee sections 1 and 3 hold to.
 */
@Composable
private fun TierFour(
    breakdown: GrammarBreakdown,
    offline: GrammarBreakdown,
    settings: SheetSettings,
    grammar: GrammarController,
    pageId: String,
    regionId: Long,
    text: String,
    words: List<LexicalUnit>,
    meanings: Map<Int, String>,
    savedWords: List<SavedWordEntity>,
    dictionaryReady: Boolean,
    lexicon: com.mangareader.analysis.GrammarLexicon,
    onPin: (Token) -> Unit,
    onUnpin: (Token) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    // Which phrase the reader tapped, or -1. Reset when the sheet moves to another bubble,
    // or a selection made on the last region would survive onto this one's cards.
    var selected by remember(regionId) { mutableStateOf(-1) }

    TierHeader(
        title = "3. Sentence breakdown",
        detail = if (breakdown.isEmpty) "" else breakdown.sentenceType.label.lowercase(),
        expanded = expanded,
        onToggle = { expanded = !expanded },
    )

    AnimatedVisibility(expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (breakdown.isEmpty) {
                Text(
                    "No breakdown for this bubble — re-analyse the page to run stage 6 " +
                        "over it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            SentenceMeta(breakdown)

            if (breakdown.phrases.isNotEmpty()) {
                PhraseCards(
                    phrases = breakdown.phrases,
                    selected = selected,
                    settings = settings,
                    onSelect = { id -> selected = if (selected == id) -1 else id },
                )
                DependencyHint(breakdown.phrases, selected)
            }

            if (!dictionaryReady) Text("Dictionary meanings will appear when the dictionary is ready.",
                style = MaterialTheme.typography.bodySmall)
            val entries = remember(words, breakdown, lexicon) { LearnerBreakdown.entries(words, breakdown, lexicon) }
            entries.forEach { entry ->
                val token = entry.bookmark
                val pinned = token != null && savedWords.any { it.baseForm == token.baseForm &&
                    it.reading == token.baseReading.ifEmpty { token.reading } }
                LearnerEntryView(entry, entry.word?.let { meanings[it.id] }, pinned, settings,
                    entry.phrase?.let { dimmed(breakdown.phrases, selected, it.id) } ?: false) {
                    if (token != null) { if (pinned) onUnpin(token) else onPin(token) }
                }
            }

            // Patterns that straddle a phrase boundary. There is no single phrase they
            // are about, so filing them under the first one they touch would misstate
            // their scope -- they get their own short list instead.
            if (breakdown.looseNotes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "across the sentence",
                        style = MaterialTheme.typography.labelSmall,
                        color = MangaPalette.Faint,
                    )
                    breakdown.looseNotes.forEach { note -> ModelPatternNote(note,
                        breakdown.phrases.flatMap { it.components }.filter { it.fromModel &&
                            it.token.position in note.from..note.to },
                        LearnerBreakdown.reading(note, breakdown.phrases.flatMap { it.components }), settings.romaji) }
                }
            }

            ExplainInDepth(
                grammar = grammar,
                offline = offline,
                pageId = pageId,
                regionId = regionId,
                text = text,
                words = words,
            )
        }
    }
}

/**
 * Whether a phrase should be dimmed given the current selection.
 *
 * Nothing is dimmed until something is selected: the resting state is the whole sentence,
 * and a section that starts half-faded looks broken rather than focused.
 */
private fun dimmed(phrases: List<Phrase>, selected: Int, id: Int): Boolean {
    if (selected < 0) return false
    val head = phrases.getOrNull(selected)?.head ?: -1
    return id != selected && id != head
}

/**
 * The metadata line: three words about the whole sentence, then the register's evidence.
 *
 * The register verdict used to be the first row of the note list, which was the only place
 * to put it when this section was a list. It is a property of the sentence rather than of
 * a span of it, so it belongs here beside the other two.
 *
 * All three chips take the same flat fill. Hue already carries depth in this app -- orange
 * is a series, lilac a volume, neutral a page -- and register must not claim a second
 * meaning for it. The word carries the distinction.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SentenceMeta(breakdown: GrammarBreakdown) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MetaChip(breakdown.sentenceType.label)
            MetaChip(registerWord(breakdown.register.level))
            MetaChip(breakdown.clauseStructure.label)
        }
        if (breakdown.register.note.isNotEmpty()) {
            Text(
                buildString {
                    append(breakdown.register.note)
                    if (breakdown.register.evidence.isNotEmpty()) {
                        append(" Read off ")
                        append(breakdown.register.evidence.joinToString(", "))
                        append('.')
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MangaPalette.Dim,
            )
        }
    }
}

@Composable
private fun MetaChip(label: String) {
    Surface(color = MangaPalette.SilverTint) {
        Text(
            label.lowercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MangaPalette.Dim,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The phrase cards: one per 文節, in reading order, each holding its own tokens.
 *
 * The dashes are the app's own affordance for a bounded thing you can act on --
 * [DashedAction] uses the same stroke for the button at the foot of every folder screen --
 * and these are tappable, so they get it too.
 *
 * **Colour is keyed on the phrase's part of speech, not on its role.** The mockup coloured
 * adverbial and subject alike and object and predicate alike, which maps to nothing; the
 * app already has a rule that does. [posColour] puts orange on the predicative and violet
 * on the nominal, so the predicate is usually the only orange card on screen -- which is
 * exactly what a learner scanning for the verb is looking for. Nothing here tints a fill:
 * the stroke and the label carry it, and fills still mean series / volume / page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhraseCards(
    phrases: List<Phrase>,
    selected: Int,
    settings: SheetSettings,
    onSelect: (Int) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        phrases.forEach { phrase ->
            PhraseCard(
                phrase = phrase,
                dimmed = dimmed(phrases, selected, phrase.id),
                settings = settings,
                onClick = { onSelect(phrase.id) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhraseCard(
    phrase: Phrase,
    dimmed: Boolean,
    settings: SheetSettings,
    onClick: () -> Unit,
) {
    val ink = posColour(phrase.pos)
    val stroke = posStroke(phrase.pos)
    Column(
        Modifier
            .alpha(if (dimmed) 0.3f else 1f)
            .heightIn(min = 48.dp)
            .semantics { contentDescription = phrase.text }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            phrase.role.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = posWeight(phrase.pos),
            color = ink,
        )
        // A five-token phrase is wider than the 362 dp of content the sheet has, so the
        // tokens flow inside the card rather than clipping at its edge.
        FlowRow(
            modifier = Modifier
                .dashedBorder(stroke, 2.dp, 7.dp, 5.dp)
                .padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            phrase.components.forEach { component ->
                TokenBox(component, stroke, settings)
            }
        }
        // Romaji obeys the same setting tier 1 does. `Settings.kt` calls that toggle "the
        // one place the app has an opinion about how Japanese should be learned"; tier 4
        // does not get to overrule it three sections further down.
        if (settings.romaji && phrase.romaji.isNotEmpty()) {
            Text(
                phrase.romaji,
                style = MaterialTheme.typography.labelSmall,
                color = MangaPalette.NeutralInkSoft,
            )
        }
    }
}

/** One token: its furigana, its surface, and the word class underneath. */
@Composable
private fun TokenBox(component: Component, stroke: Color, settings: SheetSettings) {
    Column(
        Modifier
            .border(1.dp, stroke)
            .background(MangaPalette.White)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FuriganaText(
            segments = component.token.furigana.ifEmpty {
                listOf(Ruby(component.token.surface, ""))
            },
            showFurigana = settings.furigana,
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            PartOfSpeech.shortLabel(component.token.partOfSpeech),
            style = MaterialTheme.typography.labelSmall,
            color = MangaPalette.Faint,
        )
    }
}

/**
 * One line about the dependency, and only when something is selected.
 *
 * The alternative was a caption under every card naming its head, which is what the first
 * draft did and what the mockup review threw out: the caption is as wide as the head
 * phrase's text while the card is only as wide as its own tokens, so a long head ran under
 * three neighbours and the row of them read as stray underlining. Saying it once, on
 * demand, in a line that has the width for it, is both quieter and clearer.
 */
@Composable
private fun DependencyHint(phrases: List<Phrase>, selected: Int) {
    val phrase = phrases.getOrNull(selected)
    Text(
        text = when {
            phrase == null -> "Tap a phrase to see what it attaches to."
            phrase.head < 0 ->
                phrase.text + " is the root — everything else hangs off it."
            else -> phrase.text + " attaches to " + phrases[phrase.head].text +
                " (" + phrases[phrase.head].role.label.lowercase() + ")."
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (phrase == null) MangaPalette.Faint else MangaPalette.Dim,
    )
}

/**
 * One phrase's explanation block: a coloured left rule, the role, and a line per token.
 *
 * [IntrinsicSize.Min] is what makes the rule the height of the text beside it. The sheet
 * scrolls, so the row's height constraint is unbounded and a bare `fillMaxHeight` would
 * measure to nothing and draw no rule at all.
 */
@Composable
private fun PhraseBlock(phrase: Phrase, notes: List<GrammarNote>, dimmed: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) 0.3f else 1f)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(posStroke(phrase.pos)),
        )
        Column(Modifier.padding(start = 11.dp)) {
            Text(
                phrase.role.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = posWeight(phrase.pos),
                color = posColour(phrase.pos),
                modifier = Modifier.padding(bottom = 2.dp),
            )
            phrase.components.forEach { component -> ComponentLine(component) }
            notes.forEach { note -> PatternNote(note) }
        }
    }
}

/** `surface (part of speech) — relation. explanation` */
@Composable
private fun ComponentLine(component: Component, romaji: Boolean = false,
    transcription: String = Romaji.of(component.token.reading)) {
    Column(Modifier.padding(bottom = 3.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                component.token.surface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MangaPalette.Ink,
            )
            Text(
                " (" + PartOfSpeech.shortLabel(component.token.partOfSpeech) + ")",
                style = MaterialTheme.typography.labelSmall,
                color = MangaPalette.Faint,
                modifier = Modifier.padding(bottom = 1.dp),
            )
        }
        GrammarRomaji(transcription, romaji)
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = MangaPalette.VioletInk)) {
                    append(component.relation.label)
                    append(". ")
                }
                append(component.explanation)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MangaPalette.Ink,
        )
        // Provenance only when there is something to say. An offline explanation is the
        // section's normal state and does not need a badge announcing it; a model's is the
        // exception and does, because a rule firing and a model guessing are different
        // kinds of claim and should not look alike.
        if (component.fromModel) {
            Text(
                component.modelId.ifEmpty { "model" },
                style = MaterialTheme.typography.labelSmall,
                color = MangaPalette.Violet,
            )
        }
    }
}

/**
 * One multi-token pattern from the bundled table, indented under its phrase.
 *
 * A pattern is not the sum of its tokens -- 〜なきゃ explained token by token says
 * "negative" and "conditional" and misses that the whole thing means *have to* -- so these
 * survive the per-token layer rather than being replaced by it.
 */
@Composable
private fun PatternNote(note: GrammarNote, reading: String = "", romaji: Boolean = false) {
    Column(
        Modifier
            .padding(top = 3.dp)
            .drawBehind {
                drawRect(
                    color = MangaPalette.Silver,
                    size = Size(1.dp.toPx(), size.height),
                )
            }
            .padding(start = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                note.fragment,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MangaPalette.Ink,
            )
            // JLPT is difficulty, not provenance, which is why it sits with the fragment.
            if (note.jlpt.isNotEmpty()) {
                Text(
                    note.jlpt,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MangaPalette.Dim,
                    modifier = Modifier
                        .padding(start = 5.dp)
                        .border(1.dp, MangaPalette.Silver)
                        .padding(horizontal = 3.dp),
                )
            }
        }
        GrammarRomaji(Romaji.of(reading), romaji)
        if (note.gloss.isNotEmpty()) {
            Text(
                note.gloss,
                style = MaterialTheme.typography.bodySmall,
                color = MangaPalette.VioletInk,
            )
        }
        Text(
            note.explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MangaPalette.Dim,
        )
    }
}

@Composable
private fun ModelPatternNote(note: GrammarNote, modelExplanations: List<Component>,
    reading: String = "", romaji: Boolean = false) {
    Column {
        PatternNote(note, reading, romaji)
        modelExplanations.distinctBy { it.explanation to it.modelId }.forEach { component ->
            Text(component.explanation, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp))
            Text(component.modelId.ifEmpty { "model" }, style = MaterialTheme.typography.labelSmall,
                color = MangaPalette.Violet, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun GrammarRomaji(transcription: String, enabled: Boolean) {
    if (enabled && transcription.isNotEmpty()) Text(transcription,
        style = MaterialTheme.typography.bodySmall, color = MangaPalette.Dim)
}

/**
 * The model half's four states, modelled on `TierThree`'s handling of the same problem.
 *
 * The states that look alike are the ones worth being careful about, and the important
 * one here is the first: **not configured is not an error.** The section above it is
 * complete and correct without a model, so this reads as an upgrade that is available
 * rather than as something missing -- the same line tier 3 takes when it has no
 * translator.
 */
@Composable
private fun ExplainInDepth(
    grammar: GrammarController,
    offline: GrammarBreakdown,
    pageId: String,
    regionId: Long,
    text: String,
    words: List<LexicalUnit> = emptyList(),
) {
    val running = grammar.running == regionId
    val done = !grammar.answerFor(pageId, regionId).isEmpty

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            !grammar.configured -> Text(
                "The notes above are the bundled rule table's, and need no model, no " +
                    "account and no network. Choosing a translator in Settings adds a " +
                    "model's reading of this line on top of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            running -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "Explaining this bubble…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                TextButton(shape = RectangleShape,
                    onClick = {
                        // The *offline* phrases, deliberately, even on "Explain again":
                        // sending prose the model already wrote would ask it to improve
                        // its own answer rather than the table's.
                        grammar.explain(pageId, regionId, text, offline.phrases, words)
                    },
                ) { Text(if (done) "Explain again" else "Explain in depth") }
                Text(
                    // One bubble, not the page: the opposite of tier 3's note, and worth
                    // saying for the same reason tier 3 says its version.
                    "One short call to " + grammar.label + ", for this bubble only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MangaPalette.Faint,
                )
            }
        }

        grammar.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The register verdict as the header says it: one lower-case word. */
private fun registerWord(level: RegisterLevel): String = level.name.lowercase()

/**
 * A numbered section header, the way the mockup writes them: "2. Translation", small and
 * grey, with the chevron on the right for the two that fold.
 */
@Composable
private fun TierHeader(title: String, detail: String, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MangaPalette.Dim,
        )
        if (detail.isNotEmpty()) {
            Text(
                "  " + detail,
                style = MaterialTheme.typography.labelSmall,
                color = MangaPalette.Faint,
            )
        }
        Box(Modifier.weight(1f))
        Icon(
            if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MangaPalette.Ink,
        )
    }
}

/**
 * Page-level (text, ruby) segments, the Kotlin of
 * `harness/pipeline/analyse.py::furigana_html_segments`.
 *
 * Falls back to the raw string when stage 6 has not run for this region -- an
 * un-analysed page still shows its text, without ruby.
 */
private fun segments(tokens: List<Token>, fallback: String): List<Ruby> {
    if (tokens.isEmpty()) return listOf(Ruby(fallback, ""))
    return tokens.flatMap { token ->
        token.furigana.ifEmpty { listOf(Ruby(token.surface, "")) }
    }
}

private fun RegionEntity.text(): String = ocrTextEdited ?: ocrText
