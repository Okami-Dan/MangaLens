package com.mangareader

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.mangareader.ui.MangaReaderTheme
import com.mangareader.ui.TutorialAnimation
import com.mangareader.ui.TutorialClip
import com.mangareader.ui.TutorialDialog
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Decoder-backed checks: no fake player or remote media. Run on the isolated AVD. */
@androidx.annotation.OptIn(UnstableApi::class)
@SdkSuppress(minSdkVersion = 29)
@RunWith(AndroidJUnit4::class)
class TutorialPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun bundledClipsDecodeSilentlyAndLoop() {
        compose.setContent { MangaReaderTheme { TutorialDialog(onDismiss = {}) } }
        repeat(7) { section ->
            val player = awaitPlaying()
            onUi {
                assertEquals(700, player.videoSize.width)
                assertEquals(500, player.videoSize.height)
                assertEquals(0f, player.volume, 0f)
                assertEquals(Player.REPEAT_MODE_ONE, player.repeatMode)
                assertFalse(player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO })
            }
            screenshot("section-${section+1}")
            onUi { player.seekTo(player.duration - 200) }
            compose.waitUntil(10_000) { onUi { player.currentPosition < 600 && player.isPlaying } }
            compose.onNodeWithText("Next").performClick()
            compose.waitUntil(5000) { onUi { player.playbackState == Player.STATE_IDLE } }
        }
        compose.onNodeWithText("Section 8 of 8").assertIsDisplayed()
        compose.onNodeWithTag("tutorialAnimation").assertDoesNotExist()
        assertNull(currentPlayer())
        compose.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test fun explicitPauseRestoresAndSectionEntryRestarts() {
        val open = mutableStateOf(true)
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MangaReaderTheme { if (open.value) TutorialDialog(onDismiss = { open.value = false }) }
        }
        val player = awaitPlaying()
        compose.onNodeWithText("Pause animation").performClick()
        compose.waitUntil(5000) { onUi { !player.isPlaying } }
        val position = onUi { player.currentPosition }
        Thread.sleep(350)
        assertTrue(kotlin.math.abs(position - onUi { player.currentPosition }) < 100)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Resume animation").performScrollTo().assertIsDisplayed()
        compose.waitUntil(5000) { currentPlayer() != null }
        assertFalse(onUi { currentPlayer()!!.playWhenReady })
        compose.onNodeWithText("Resume animation").performClick()
        awaitPlaying()
        compose.onNodeWithText("Next").performClick()
        awaitPlaying()
        compose.onNodeWithText("Back").performClick()
        val restarted = awaitPlaying()
        assertTrue(onUi { restarted.currentPosition } < 1500)
        compose.onNodeWithText("Pause animation").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        compose.waitUntil(5000) { currentPlayer() == null }
        compose.runOnIdle { open.value = true }
        compose.onNodeWithText("Section 1 of 8").assertIsDisplayed()
        val reopened = awaitPlaying()
        assertTrue(onUi { reopened.currentPosition } < 1500)
    }

    @Test fun offscreenAndBackgroundReleaseDecoder() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                MangaReaderTheme {
                    // A fixed viewport and trailing space guarantee complete occlusion
                    // on both the recording AVD and taller physical phone screens.
                    Column(Modifier.height(240.dp).verticalScroll(rememberScrollState())) {
                        Text("Preview start")
                        TutorialAnimation(TutorialClip(R.raw.tutorial_welcome,
                            R.drawable.tutorial_welcome_poster, "Lifecycle preview"))
                        Spacer(Modifier.height(240.dp))
                        Text("Preview end")
                    }
                }
            }
        }
        val first = awaitPlaying()
        compose.onNodeWithText("Preview end")
            .performScrollTo().assertIsDisplayed()
        compose.waitUntil(5000) { currentPlayer() == null }
        assertEquals(Player.STATE_IDLE, onUi { first.playbackState })
        compose.onNodeWithText("Preview start").performScrollTo()
        val returned = awaitPlaying()
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitUntil(5000) { currentPlayer() == null }
        assertEquals(Player.STATE_IDLE, onUi { returned.playbackState })
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitPlaying()
    }

    @Test fun decodeFailureKeepsPosterInstructionsAndNavigation() {
        compose.setContent {
            MangaReaderTheme {
                Column {
                    // A missing local stream exercises the real data-source failure path.
                    TutorialAnimation(TutorialClip(0,
                        R.drawable.tutorial_welcome_poster, "Fallback preview"))
                    Text("Tutorial instructions remain")
                    TextButton(onClick = {}) { Text("Next") }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Animation unavailable. Follow the instructions below.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Demonstration: Fallback preview").assertIsDisplayed()
        compose.onNodeWithText("Tutorial instructions remain").assertIsDisplayed()
        compose.onNodeWithText("Next").assertIsDisplayed()
        assertNull(currentPlayer())
    }

    private fun awaitPlaying(): Player {
        compose.waitUntil(15_000) {
            onUi { currentPlayer()?.let { it.isPlaying && it.videoSize.width == 700 } == true }
        }
        return currentPlayer()!!
    }

    private fun currentPlayer(): Player? = onUi {
        fun players(view: View): List<PlayerView> = when (view) {
            is PlayerView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { players(view.getChildAt(it)) }
            else -> emptyList()
        }
        WindowInspector.getGlobalWindowViews().flatMap { players(it) }
            .filter { it.isAttachedToWindow }.firstNotNullOfOrNull { it.player }
    }

    private fun <T> onUi(block: () -> T): T {
        // Player access must always be on its application looper, including polling.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return block()
        val task = FutureTask(Callable(block))
        InstrumentationRegistry.getInstrumentation().runOnMainSync(task)
        return task.get()
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = context.getExternalFilesDir("tutorial-validation")!!
        directory.mkdirs()
        val bitmap = compose.onNodeWithTag("helpPopup").captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
