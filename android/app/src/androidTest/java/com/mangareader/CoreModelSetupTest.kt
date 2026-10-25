package com.mangareader

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import com.mangareader.pipeline.ModelStore
import com.mangareader.ui.AutoStartCoreModelSetup
import com.mangareader.ui.CoreModelSetupScreen
import com.mangareader.ui.MangaReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** UI-only tests: never install, remove, or download models in the device's real library. */
@RunWith(AndroidJUnit4::class)
class CoreModelSetupTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepVisible() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 27) {
                compose.activity.setShowWhenLocked(true)
                compose.activity.setTurnScreenOn(true)
            }
        }
    }

    @Test fun startupWaitsForArtworkAndRecompositionDoesNotRestartSetup() {
        val startupComplete = mutableStateOf(false)
        val ready = mutableStateOf(false)
        val progress = mutableStateOf<ModelStore.Progress?>(null)
        var starts = 0
        compose.setContent {
            MangaReaderTheme {
                AutoStartCoreModelSetup(ready.value, startupComplete.value) { starts++ }
                if (ready.value) Text("Library is ready") else CoreModelSetupScreen(
                    progress.value, null, 168_505_603, 116_714_443, {}, {},
                )
            }
        }
        compose.runOnIdle { assertEquals(0, starts); startupComplete.value = true }
        compose.runOnIdle {
            assertEquals(1, starts)
            progress.value = ModelStore.Progress("Downloading bubble detector", 0, 4, 80_000_000, 285_220_046)
        }
        compose.onNodeWithText("Downloading bubble detector").assertIsDisplayed()
        compose.onNodeWithText("80.0 MB of 285.2 MB prepared").assertIsDisplayed()
        compose.onNodeWithText("One-time download: 168.5 MB. Uses your current internet connection.").assertIsDisplayed()
        compose.runOnIdle {
            if (Build.VERSION.SDK_INT >= 29) assertFalse(compose.activity.window.isNavigationBarContrastEnforced)
        }
        screenshot("setup-progress")
        compose.runOnIdle { assertEquals(1, starts); ready.value = true }
        compose.onNodeWithText("Library is ready").assertIsDisplayed()
        compose.onNodeWithText("Preparing Manga Lens").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, starts)
            if (Build.VERSION.SDK_INT >= 29) assertTrue(compose.activity.window.isNavigationBarContrastEnforced)
        }
    }

    @Test fun existingInstallationSkipsDownloadButMissingFilesTriggerSetup() {
        val ready = mutableStateOf(true)
        var starts = 0
        compose.setContent { AutoStartCoreModelSetup(ready.value, true) { starts++ } }
        compose.runOnIdle { assertEquals(0, starts); ready.value = false }
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun failedSetupOffersRetryAndFileImportWithoutOpeningOptions() {
        val progress = mutableStateOf<ModelStore.Progress?>(null)
        var retries = 0
        var imports = 0
        compose.setContent {
            MangaReaderTheme {
                CoreModelSetupScreen(progress.value, "Connection interrupted", 168_505_603, 116_714_443,
                    onRetry = {
                        retries++
                        progress.value = ModelStore.Progress("Preparing text recognition", 0, 0, 0, 0)
                    },
                    onImport = { imports++ },
                )
            }
        }
        // A newly awakened device can finish launching the Activity after Compose's
        // first idle, before its window has registered a semantics root.
        compose.waitUntil(timeoutMillis = 15_000) {
            runCatching { compose.onNodeWithTag("coreModelSetup").fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithText("Setup could not finish").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Import existing model files").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, imports) }
        compose.onNodeWithText("Retry setup").performScrollTo().performClick()
        compose.onNodeWithText("Preparing text recognition").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry setup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    private fun screenshot(name: String) {
        val folder = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("setup-ui")!!
        folder.mkdirs()
        val bitmap = compose.onNodeWithTag("coreModelSetup").captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
