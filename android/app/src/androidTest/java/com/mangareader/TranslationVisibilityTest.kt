package com.mangareader

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangareader.database.PageEntity
import com.mangareader.database.RegionEntity
import com.mangareader.model.*
import com.mangareader.translate.cacheKey
import com.mangareader.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TranslationVisibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepVisible() {
        compose.runOnUiThread {
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 27) {
                compose.activity.setShowWhenLocked(true)
                compose.activity.setTurnScreenOn(true)
            }
        }
    }

    @Test fun readerShowsLocalAndRemoteTranslationsWhenOtherRegionsAreHidden() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val task = SupervisorJob()
        val scope = CoroutineScope(task + Dispatchers.Main)
        val file = File(context.cacheDir, "visibility-${UUID.randomUUID()}.preferences_pb")
        val id = "visibility-${UUID.randomUUID()}"
        val page = PageEntity(id, "nonexistent-test-volume", 0, "nonexistent-test.jpg",
            null, null, 100, 100, PipelineStage.TRANSLATED, PageKind.INTERIOR,
            true, 0f, false, null, "Test", 1, "")
        val source = listOf(
            Region(1, RegionKind.TEXT_BUBBLE, Box(.2f,.2f,.8f,.8f), 1f,
                readingIndex = 0, ocrText = "こんにちは", ocrConfidence = .99f),
            Region(2, RegionKind.TEXT_FREE, Box(.01f,.01f,.1f,.1f), 1f,
                readingIndex = 1, ocrText = "低信頼", ocrConfidence = .42f),
        )
        val models = listOf("lfm2-350m-enjp-mt-q4_k_m", "qwen3-1.7b-q4_k_m", "remote:test:translator")
        fun rows(model: String): List<RegionEntity> {
            val key = cacheKey(source, model)
            assertNotEquals(key, cacheKey(source.take(1), model))
            return source.map { RegionEntity.of(id, it).copy(translation = "Hello from $model",
                translatorId = model, translationKey = key) }
        }
        val regions = mutableStateOf(rows(models.first()))
        val mounted = mutableStateOf(true)
        try {
            val settings = ReaderSettings(PreferenceDataStoreFactory.create(
                scope = scope, produceFile = { file }), scope)
            val analysis = AnalysisController(context, scope)
            val translation = TranslationController(context, scope, settings)
            val grammar = GrammarController(context, scope, settings, translation)
            compose.setContent {
                MangaReaderTheme {
                    if (mounted.value) ReaderScreen(page, regions.value, emptyList(), emptyList(),
                        SheetSettings(false, false), analysis, translation, grammar,
                        MangaTab.LIBRARY, {}, {}, {})
                }
            }
            // The page is square and centered: its center remains a valid bubble hit
            // at any phone aspect ratio. Exercise ReaderScreen's actual sheet wiring.
            compose.onRoot().performTouchInput { click(center) }
            for (model in models) {
                compose.runOnIdle { regions.value = rows(model) }
                compose.onNodeWithText("Hello from $model").performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription("Next bubble").assertIsNotEnabled()
            }
            // An actual OCR edit must still withhold stale English.
            compose.runOnIdle {
                regions.value = regions.value.map {
                    if (it.regionId == 1L) it.copy(ocrTextEdited = "変更") else it
                }
            }
            compose.onNodeWithText("Hello from ${models.last()}").assertDoesNotExist()
        } finally {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            runBlocking { task.cancelAndJoin() }
            file.delete()
        }
    }
}
