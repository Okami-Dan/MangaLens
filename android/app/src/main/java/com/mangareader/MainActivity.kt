package com.mangareader

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.res.painterResource
import com.mangareader.pipeline.Vault
import com.mangareader.ui.MangaReaderApp
import com.mangareader.ui.MangaReaderTheme
import com.mangareader.ui.MangaPalette
import com.mangareader.ui.StartupArtworkSession
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val vault by lazy { Vault.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Both of these run before anything can touch Room. Room creates the database
        // file on first access and caches its handle, and an empty database that exists is
        // indistinguishable from a library that never did -- so this is the only moment
        // either swap can be made. A few megabytes of copy on the main thread is the
        // honest price for that ordering guarantee; moving it off would mean racing the
        // first composition for the database.
        //
        // The restore goes first because the user asked for it by name: a backup they
        // picked outranks a snapshot the app took on its own.
        val installed = vault.installPendingRestore()
        // And then the snapshot, for the launch after *Clear data*, where the app-private
        // database is gone and the vault still holds a copy of it.
        val recovered = vault.recover()

        val artworkSelection = StartupArtworkSession.image(applicationContext)
        setContent {
            MangaReaderTheme {
                var artworkIndex by remember { mutableStateOf<Int?>(null) }
                var artworkVisible by remember {
                    mutableStateOf(StartupArtworkSession.timer.remaining(SystemClock.elapsedRealtime()) > 0)
                }
                LaunchedEffect(Unit) {
                    artworkIndex = artworkSelection.await()
                }
                LaunchedEffect(artworkIndex) {
                    if (artworkVisible && artworkIndex != null) {
                        // Start timing when the selected artwork can actually be drawn.
                        withFrameNanos { }
                        StartupArtworkSession.timer.start(SystemClock.elapsedRealtime())
                        delay(StartupArtworkSession.timer.remaining(SystemClock.elapsedRealtime()))
                        artworkVisible = false
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    Box(if (artworkVisible) Modifier.clearAndSetSemantics { } else Modifier) {
                        MangaReaderApp(
                            restoredFromVault = recovered,
                            restoredFromBackup = installed,
                            startupComplete = !artworkVisible,
                        )
                    }
                    if (artworkVisible) {
                        BackHandler { }
                        Box(Modifier.fillMaxSize().background(MangaPalette.Paper).pointerInput(Unit) {
                            awaitEachGesture {
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                } while (event.changes.any { it.pressed })
                            }
                        }) {
                            artworkIndex?.let { index ->
                                val images = listOf(R.drawable.startup_screen_1, R.drawable.startup_screen_2,
                                    R.drawable.startup_screen_3, R.drawable.startup_screen_4)
                                Image(
                                    painter = painterResource(images[index]),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The library's checkpoint. `onStop` is the last moment the app is certainly still
     * alive, and everything written since the previous one -- a series created, a page
     * captured, a word pinned -- is only in the app-private database until this runs.
     *
     * Fire-and-forget on the vault's own scope rather than `lifecycleScope`, which is
     * cancelled on the way out of this very transition.
     */
    override fun onStop() {
        super.onStop()
        vault.snapshotSoon()
    }
}
