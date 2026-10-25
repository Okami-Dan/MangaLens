package com.mangareader

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangareader.analysis.Grammar
import com.mangareader.analysis.LearnerBreakdown
import com.mangareader.analysis.LearnerGrammar
import com.mangareader.model.Gloss
import com.mangareader.pipeline.DictionaryStore
import com.mangareader.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SentenceUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepTestActivityVisible() {
        compose.runOnUiThread {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 27) {
                compose.activity.setShowWhenLocked(true)
                compose.activity.setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            }
        }
    }

    @Test fun combinedWordFitsMobileWidthsAndLargeText(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionaries = DictionaryStore(context)
        dictionaries.install()
        val sentence = dictionaries.analyser().use { it.sentence("もう読んじゃったの？", Grammar(dictionaries.grammar())) }
        val original = LearnerBreakdown.entries(sentence.words, sentence.grammar).first { it.word?.lemma == "読む" }
        // Always exercise the expanded-definition layout even if the installed dictionary has one sense.
        val entry = original.copy(word = original.word!!.copy(senses = listOf(
            Gloss(1,"v5m","","to read",true), Gloss(1,"v5m","","to interpret",false))))
        val word = entry.word!!
        val phrase = entry.phrase!!
        val width = mutableStateOf(320)
        val font = mutableStateOf(1f)
        val pinned = mutableStateOf(false)
        val selected = mutableStateOf(-1)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, font.value)) {
                MangaReaderTheme {
                    Column(Modifier.width(width.value.dp).testTag("sheet")
                        .verticalScroll(rememberScrollState()).padding(8.dp)) {
                        PhraseCards(sentence.grammar.phrases,selected.value,SheetSettings(true,true)) { selected.value = it }
                        Spacer(Modifier.height(12.dp))
                        LearnerEntryView(entry,"Have already finished reading",pinned.value,
                            SheetSettings(true,true),false) { pinned.value = !pinned.value }
                    }
                }
            }
        }
        for (w in listOf(320,360,412)) for (f in listOf(1f,1.3f)) {
            compose.runOnIdle { width.value = w; font.value = f; pinned.value = false }
            compose.waitForIdle()
            val panel = compose.onNodeWithTag("sheet").fetchSemanticsNode().boundsInRoot
            val button = compose.onNodeWithContentDescription("Save word").fetchSemanticsNode().boundsInRoot
            assertTrue("save target outside sheet at $w/$f",button.left >= panel.left && button.right <= panel.right)
            assertTrue("save target smaller than 48 dp", button.height >= 48 * context.resources.displayMetrics.density)
            assertTrue("save target too narrow", button.width >= 48 * context.resources.displayMetrics.density)
            val title = compose.onNodeWithContentDescription(word.surface).fetchSemanticsNode().boundsInRoot
            assertTrue("bookmark is not upper right", button.left > title.left && button.top <= title.bottom)
            val definition = compose.onNodeWithText("to read").fetchSemanticsNode().boundsInRoot
            val detail = compose.onNodeWithText("Definition & morphology").fetchSemanticsNode().boundsInRoot
            assertTrue("detail text not left aligned", kotlin.math.abs(definition.left-detail.left) < 2f)
            entry.grammar.forEach { line ->
                val explanation = when (line) {
                    is LearnerGrammar.Pattern -> line.note.explanation
                    is LearnerGrammar.TokenNote -> line.component.explanation
                }
                compose.onNodeWithText(explanation, substring=true).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText("jat").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Definition & morphology").performScrollTo().performClick()
            compose.onNodeWithText("Less detail").assertIsDisplayed()
            compose.onNodeWithText("Less detail").performClick()
            compose.onNodeWithContentDescription("Save word").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Unsave word").assertIsDisplayed()
            compose.onNodeWithContentDescription("Unsave word").performClick()
            compose.onNodeWithContentDescription("Save word").assertIsDisplayed()
            compose.onNodeWithText("Adverbial").performScrollTo()
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            val screenshot = File(context.cacheDir,"sentence-$w-${(f*100).toInt()}.png")
            screenshot.outputStream().use { output ->
                compose.onNodeWithTag("sheet").captureToImage().asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
            }
        }
        compose.onNodeWithContentDescription(phrase.text).performClick()
        compose.runOnIdle { assertEquals(phrase.id,selected.value) }
    }

    @Test fun auxiliaryNotesHaveNoVocabularyCardsAndStayVisible(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionaries = DictionaryStore(context)
        dictionaries.install()
        val sentence = dictionaries.analyser().use { it.sentence("負けっぷりに", Grammar(dictionaries.grammar())) }
        val entry = LearnerBreakdown.entries(sentence.words, sentence.grammar).single()
        val romaji = mutableStateOf(true)
        var savedBase: String? = null
        compose.setContent {
            MangaReaderTheme {
                Column(Modifier.width(360.dp).testTag("construction").padding(8.dp)) {
                    LearnerEntryView(entry,null,false,SheetSettings(true,romaji.value),false) {
                        savedBase = entry.bookmark!!.baseForm
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("負けっぷり").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Save word").assertCountEquals(1)
        compose.onNodeWithText("Grammatical particle").assertDoesNotExist()
        compose.onNodeWithText("No verified dictionary meaning").assertDoesNotExist()
        val manner = entry.grammar.filterIsInstance<LearnerGrammar.Pattern>().single()
        compose.onNodeWithText(manner.note.explanation).assertIsDisplayed()
        val particle = entry.grammar.filterIsInstance<LearnerGrammar.TokenNote>().single()
        compose.onNodeWithText(particle.component.token.surface).assertIsDisplayed()
        compose.onNodeWithText("ppuri").assertIsDisplayed()
        compose.onNodeWithText("ni").assertIsDisplayed()
        compose.runOnIdle { romaji.value = false }
        compose.onNodeWithText("ppuri").assertDoesNotExist()
        compose.onNodeWithText("ni").assertDoesNotExist()
        compose.onNodeWithText(manner.note.explanation).assertIsDisplayed()
        compose.runOnIdle { romaji.value = true }
        compose.onNodeWithContentDescription("Save word").performClick()
        compose.runOnIdle { assertEquals("負ける", savedBase) }
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        File(context.cacheDir,"sentence-ppuri.png").outputStream().use { output ->
            compose.onNodeWithTag("construction").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
        }
    }

    @Test fun grammarOnlyFragmentShowsOneCompleteNoteWithoutBookmark(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionaries = DictionaryStore(context)
        dictionaries.install()
        val sentence = dictionaries.analyser().use { it.sentence("かな", Grammar(dictionaries.grammar())) }
        val entry = LearnerBreakdown.entries(sentence.words, sentence.grammar).single()
        val romaji = mutableStateOf(true)
        compose.setContent {
            MangaReaderTheme { Column(Modifier.width(360.dp).padding(8.dp)) {
                LearnerEntryView(entry,"must not be shown",false,SheetSettings(true,romaji.value),false) {}
            } }
        }
        compose.onNodeWithText("かな").assertIsDisplayed()
        compose.onNodeWithText("I wonder").assertIsDisplayed()
        compose.onNodeWithText("か").assertDoesNotExist()
        compose.onNodeWithText("な").assertDoesNotExist()
        compose.onNodeWithContentDescription("Save word").assertDoesNotExist()
        compose.onNodeWithText("must not be shown").assertDoesNotExist()
        compose.onNodeWithText("No verified dictionary meaning").assertDoesNotExist()
        compose.onNodeWithText("kana").assertIsDisplayed()
        compose.runOnIdle { romaji.value = false }
        compose.onNodeWithText("kana").assertDoesNotExist()
        compose.onNodeWithText("かな").assertIsDisplayed()
    }

    @Test fun prefixJoinsFollowingPhraseAndHasVisibleRomaji(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionaries = DictionaryStore(context)
        dictionaries.install()
        val sentence = dictionaries.analyser().use { it.sentence("最後のひと試合入るか", Grammar(dictionaries.grammar())) }
        val entries = LearnerBreakdown.entries(sentence.words, sentence.grammar)
        val romaji = mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.3f)) {
                MangaReaderTheme {
                    Column(Modifier.width(320.dp).testTag("prefix-sheet")
                        .verticalScroll(rememberScrollState()).padding(8.dp)) {
                        PhraseCards(sentence.grammar.phrases,-1,SheetSettings(true,romaji.value)) {}
                        entries.forEach { entry ->
                            LearnerEntryView(entry,null,false,SheetSettings(true,romaji.value),false) {}
                        }
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("最後の").assertIsDisplayed()
        compose.onNodeWithContentDescription("ひと試合").assertIsDisplayed()
        compose.onNodeWithText("saigonohito").assertDoesNotExist()
        compose.onNodeWithText("hitoshiai").assertIsDisplayed()
        compose.onNodeWithText("hito").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Adds ‘one’",substring=true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { romaji.value = false }
        compose.onNodeWithText("hito").assertDoesNotExist()
        compose.onNodeWithText("hitoshiai").assertDoesNotExist()
        compose.onNodeWithText("Adds ‘one’",substring=true).assertIsDisplayed()
        compose.runOnIdle { romaji.value = true }
        compose.onNodeWithText("saigono").performScrollTo()
        compose.waitForIdle()
        File(context.cacheDir,"sentence-prefix.png").outputStream().use { output ->
            compose.onNodeWithTag("prefix-sheet").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
        }
    }

    @Test fun compoundToshitenoShowsOfflineDefinitionWithoutAnAuxiliaryCard(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionaries = DictionaryStore(context)
        dictionaries.install()
        val sentence = dictionaries.analyser().use { it.sentence("選手としての役割", Grammar(dictionaries.grammar())) }
        val entry = LearnerBreakdown.entries(sentence.words, sentence.grammar).first()
        val romaji = mutableStateOf(true)
        compose.setContent {
            MangaReaderTheme { Column(Modifier.width(360.dp).testTag("toshite-sheet").padding(8.dp)) {
                LearnerEntryView(entry,null,false,SheetSettings(true,romaji.value),false) {}
            } }
        }
        compose.onNodeWithContentDescription("選手").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Save word").assertCountEquals(1)
        compose.onNodeWithText("としての").assertIsDisplayed()
        compose.onNodeWithText("toshiteno").assertIsDisplayed()
        compose.onNodeWithText("as / in the capacity of").assertIsDisplayed()
        compose.onNodeWithText("として marks",substring=true).assertIsDisplayed()
        compose.onNodeWithText("として").assertDoesNotExist()
        compose.onNodeWithText("の").assertDoesNotExist()
        compose.onNodeWithText("No verified dictionary meaning").assertDoesNotExist()
        compose.onNodeWithText("In this sentence · model explanation").assertDoesNotExist()
        compose.runOnIdle { romaji.value = false }
        compose.onNodeWithText("toshiteno").assertDoesNotExist()
        compose.onNodeWithText("as / in the capacity of").assertIsDisplayed()
        compose.runOnIdle { romaji.value = true }
        compose.waitForIdle()
        File(context.cacheDir,"sentence-toshite.png").outputStream().use { output ->
            compose.onNodeWithTag("toshite-sheet").captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
        }
    }

    @Test fun dictionaryGrammarMeaningsStayVisibleAtAllMobileWidths(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionaries = DictionaryStore(context)
        dictionaries.install()
        val sentence = dictionaries.analyser().use { it.sentence("三人", Grammar(dictionaries.grammar())) }
        val merged = Grammar(dictionaries.grammar()).merge(sentence.grammar,
            mapOf(sentence.tokens.last().position to "Counts people in this line."), modelId="test-model")
        val entry = LearnerBreakdown.entries(sentence.words, merged, dictionaries.grammarLexicon).single()
        val note = entry.grammar.filterIsInstance<LearnerGrammar.TokenNote>().single()
        assertTrue(note.dictionaryMeanings.isNotEmpty())
        val width = mutableStateOf(320)
        val font = mutableStateOf(1f)
        val romaji = mutableStateOf(true)
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,font.value)) {
                MangaReaderTheme {
                    Column(Modifier.width(width.value.dp).testTag("grammar-dictionary-sheet")
                        .verticalScroll(rememberScrollState()).padding(8.dp)) {
                        LearnerEntryView(entry,null,false,SheetSettings(true,romaji.value),false) {}
                    }
                }
            }
        }
        for (w in listOf(320,360,412)) for (f in listOf(1f,1.3f)) {
            compose.runOnIdle { width.value=w; font.value=f; romaji.value=true }
            compose.onAllNodesWithContentDescription("Save word").assertCountEquals(1)
            compose.onAllNodesWithText("Dictionary meanings.").assertCountEquals(1)
            compose.onNodeWithText("JMdict · EDRDG").performScrollTo().assertIsDisplayed()
            note.dictionaryMeanings.forEach { meaning ->
                compose.onNodeWithText(meaning.text).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithText("Definition & morphology").performScrollTo().performClick()
            compose.onNodeWithText("Less detail").performClick()
            compose.onNodeWithText(note.component.explanation, substring=true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(note.attachmentExplanation, substring=true).performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("test-model").assertCountEquals(1)
            compose.runOnIdle { romaji.value=false }
            compose.onNodeWithText(note.romaji).assertDoesNotExist()
            compose.onNodeWithText("Dictionary meanings.").performScrollTo().assertIsDisplayed()
            compose.waitForIdle()
            File(context.cacheDir,"grammar-dictionary-$w-${(f*100).toInt()}.png").outputStream().use { output ->
                compose.onNodeWithTag("grammar-dictionary-sheet").captureToImage().asAndroidBitmap()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
            }
        }
        compose.onNodeWithText("In this sentence · model explanation").assertDoesNotExist()
        compose.onNodeWithText("No verified dictionary meaning").assertDoesNotExist()
    }
}
