package com.mangareader.ui

import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.mangareader.R

internal data class TutorialClip(
    @RawRes val video: Int,
    @DrawableRes val poster: Int,
    val description: String,
)

internal class TutorialPlaybackState(paused: Boolean = false, position: Long = 0L) {
    var paused by mutableStateOf(paused)
    var position by mutableLongStateOf(position)
}

@Composable
internal fun rememberTutorialPlaybackState(section: Int): TutorialPlaybackState =
    rememberSaveable(section, saver = listSaver(
        save = { listOf(it.paused, it.position) },
        restore = { TutorialPlaybackState(it[0] as Boolean, it[1] as Long) },
    )) { TutorialPlaybackState() }

/** Only a visible, foreground tutorial owns a decoder; never requests audio focus. */
@Composable
internal fun TutorialAnimation(
    clip: TutorialClip,
    playback: TutorialPlaybackState = rememberTutorialPlaybackState(clip.video),
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var visible by remember { mutableStateOf(false) }
    var failed by remember(clip.video) { mutableStateOf(false) }
    Column(Modifier.widthIn(max = 350.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(350f / 250f)
            .onGloballyPositioned { coordinates ->
                // boundsInRoot clips through the scroll viewport, including when the
                // clip is completely above/below it; a hidden video owns no decoder.
                val bounds = coordinates.boundsInRoot()
                visible = coordinates.isAttached && bounds.width > 0f && bounds.height > 0f
            }.testTag("tutorialAnimation")) {
            Image(painterResource(clip.poster), contentDescription = "Demonstration: ${clip.description}",
                modifier = Modifier.fillMaxSize())
            if (foreground && visible && !failed) {
                TutorialVideo(clip.video, playback.paused, playback.position,
                    onPosition = { playback.position = it }, onFailure = { failed = true })
            }
        }
        if (failed) {
            Text("Animation unavailable. Follow the instructions below.",
                style = MaterialTheme.typography.bodySmall)
        } else {
            TextButton(shape = RectangleShape, onClick = { playback.paused = !playback.paused },
                modifier = Modifier.testTag("tutorialPlayback").semantics {
                    stateDescription = if (playback.paused) "Paused" else "Playing"
                }) {
                Text(if (playback.paused) "Resume animation" else "Pause animation")
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TutorialVideo(
    @RawRes video: Int,
    paused: Boolean,
    position: Long,
    onPosition: (Long) -> Unit,
    onFailure: () -> Unit,
) {
    val context = LocalContext.current
    var rendered by remember(video) { mutableStateOf(false) }
    val player = remember(context, video) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(Uri.parse("android.resource://${context.packageName}/$video")))
            seekTo(position)
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { onFailure() }
            override fun onRenderedFirstFrame() { rendered = true }
        }
        player.addListener(listener)
        player.prepare()
        onDispose {
            onPosition(player.currentPosition)
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player, paused) { player.playWhenReady = !paused }
    AndroidView(
        factory = {
            (LayoutInflater.from(it).inflate(R.layout.tutorial_video_player, null) as PlayerView).apply {
                this.player = player
                isFocusable = false
                keepScreenOn = false
            }
        },
        update = { it.player = player; it.alpha = if (rendered) 1f else 0f },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}
