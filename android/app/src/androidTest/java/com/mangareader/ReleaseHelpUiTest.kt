package com.mangareader

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangareader.ui.AboutAppDialog
import com.mangareader.ui.MangaReaderTheme
import com.mangareader.ui.TutorialDialog
import com.mangareader.ui.ReleaseHelpHost
import com.mangareader.ui.ReleasePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap
import android.os.Build
import android.view.WindowManager
import org.junit.Before
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReleaseHelpUiTest {
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

    @Test fun tutorialNavigatesRestoresAndReopensAtTheBeginning() {
        val open = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MangaReaderTheme { if (open.value) TutorialDialog(onDismiss = { open.value = false }) }
        }
        compose.onNodeWithText("Section 1 of 8").assertIsDisplayed()
        screenshot("tutorial-welcome")
        compose.onNodeWithText("Back").assertIsNotEnabled()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Section 2 of 8").assertIsDisplayed()
        screenshot("tutorial-translation")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Section 2 of 8").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Section 1 of 8").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        compose.onNodeWithTag("helpPopup").assertDoesNotExist()
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText("Section 1 of 8").assertIsDisplayed()
        repeat(7) { compose.onNodeWithText("Next").performClick() }
        compose.onNodeWithText("Section 8 of 8").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("helpPopup").assertDoesNotExist()
    }

    @Test fun aboutScrollsAtLargeFontAndKeepsCloseAccessible() {
        val open = mutableStateOf(true)
        compose.setContent {
            // Render at an effective 320dp width with enlarged text on the real phone.
            val density = LocalDensity.current.density * LocalConfiguration.current.screenWidthDp / 320f
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                MangaReaderTheme { if (open.value) AboutAppDialog(onDismiss = { open.value = false }) }
            }
        }
        compose.onNodeWithText("Close").assertIsDisplayed()
        screenshot("about-narrow-large-text")
        compose.onNodeWithText("GitHub").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Tipping is entirely optional and won’t affect your experience with the app.")
            .performScrollTo().assertIsDisplayed()
        screenshot("about-bottom")
        compose.onNodeWithText("Close").performClick()
        assertTrue(!open.value)
    }

    @Test fun tutorialKeepsNavigationVisibleAtNarrowWidthAndLargeFont() {
        compose.setContent {
            val density = LocalDensity.current.density * LocalConfiguration.current.screenWidthDp / 320f
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                MangaReaderTheme { TutorialDialog(onDismiss = {}) }
            }
        }
        repeat(8) { page ->
            compose.onNodeWithText("Section ${page + 1} of 8").assertIsDisplayed()
            compose.onNodeWithText("Back").assertIsDisplayed()
            val action = if (page == 7) "Done" else "Next"
            compose.onNodeWithText(action).assertIsDisplayed()
            if (page == 1) compose.onNodeWithText("See ‘About app’ in the Options menu for more info on how data is treated.")
                .performScrollTo().assertIsDisplayed()
            screenshot("tutorial-narrow-$page")
            if (page != 7) compose.onNodeWithText("Next").performClick()
        }
    }

    @Test fun automaticTutorialWaitsForStartupAndOnlyAppearsOnce(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val job = SupervisorJob()
        val file = File(context.cacheDir, "release-${UUID.randomUUID()}.preferences_pb")
        try {
            val preferences = ReleasePreferences(PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file }))
            val startupComplete = mutableStateOf(false)
            val mounted = mutableStateOf(true)
            compose.setContent {
                MangaReaderTheme {
                    if (mounted.value) ReleaseHelpHost(preferences, startupComplete.value, null) {}
                }
            }
            compose.onNodeWithTag("helpPopup").assertDoesNotExist()
            compose.runOnIdle { startupComplete.value = true }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Section 1 of 8").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Skip").performClick()
            compose.onNodeWithTag("helpPopup").assertDoesNotExist()
            // Wait for the durable write before simulating another app composition.
            assertTrue(preferences.tutorialWasShown())
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.runOnIdle { mounted.value = true }
            compose.waitForIdle()
            compose.onNodeWithTag("helpPopup").assertDoesNotExist()
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun tutorialLayoutsAt360And412DpWithEnlargedFonts() {
        val width = mutableStateOf(360f)
        val font = mutableStateOf(1f)
        val open = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current.density * LocalConfiguration.current.screenWidthDp / width.value
            CompositionLocalProvider(LocalDensity provides Density(density, font.value)) {
                MangaReaderTheme { if (open.value) TutorialDialog(onDismiss = { open.value = false }) }
            }
        }
        for (requestedWidth in listOf(360f, 412f)) {
            for (requestedFont in listOf(1f, 1.5f)) {
                compose.runOnIdle { width.value = requestedWidth; font.value = requestedFont; open.value = true }
                repeat(8) { page ->
                    compose.onNodeWithText("Section ${page+1} of 8").assertIsDisplayed()
                    compose.onNodeWithText("Back").assertIsDisplayed()
                    val action = if (page == 7) "Done" else "Next"
                    compose.onNodeWithText(action).assertIsDisplayed()
                    if (page == 0) screenshot("tutorial-${requestedWidth.toInt()}-font-$requestedFont")
                    compose.onNodeWithText(action).performClick()
                }
                compose.onNodeWithTag("helpPopup").assertDoesNotExist()
            }
        }
    }

    private fun screenshot(name: String) {
        val folder = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("release-ui")!!
        folder.mkdirs()
        val bitmap = compose.onNodeWithTag("helpPopup").captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
