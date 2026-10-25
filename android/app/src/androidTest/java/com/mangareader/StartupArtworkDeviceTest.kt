package com.mangareader

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangareader.ui.StartupArtworkSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the real frame clock, without Compose test-clock advancement or clearing data. */
@RunWith(AndroidJUnit4::class)
class StartupArtworkDeviceTest {
    @Test fun recreationAndWarmRelaunchKeepTheSelectionAndOriginalDeadline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= 27) {
                    activity.setShowWhenLocked(true)
                    activity.setTurnScreenOn(true)
                }
            }
            val startedBy = SystemClock.elapsedRealtime() + 5000L
            while (remaining() == 2500L && SystemClock.elapsedRealtime() < startedBy) SystemClock.sleep(20)
            assertTrue("Artwork must start on a visible frame", remaining() in 1L..2499L)
            val selection = StartupArtworkSession.image(context)
            val before = remaining()
            screenshot("startup-before-recreation")
            scenario.recreate()
            assertSame(selection, StartupArtworkSession.image(context))
            assertTrue("Recreation must not reset the deadline", remaining() <= before)
            screenshot("startup-after-recreation")
            SystemClock.sleep(remaining() + 150L)
            assertEquals(0L, remaining())
            screenshot("startup-finished")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            assertEquals("A warm activity launch must skip the artwork", 0L, remaining())
            assertTrue(runBlocking { StartupArtworkSession.image(context).await() } in 0..3)
        }
    }

    private fun remaining() = StartupArtworkSession.timer.remaining(SystemClock.elapsedRealtime())

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = instrumentation.targetContext.getExternalFilesDir("release-ui")!!
        folder.mkdirs()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
