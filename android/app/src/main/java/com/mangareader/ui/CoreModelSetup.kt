package com.mangareader.ui

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.mangareader.R
import androidx.core.view.WindowInsetsControllerCompat
import com.mangareader.pipeline.ModelStore
import java.util.Locale

@Composable
internal fun AutoStartCoreModelSetup(ready: Boolean, startupComplete: Boolean, onStart: () -> Unit) {
    LaunchedEffect(ready, startupComplete) {
        if (startupComplete && !ready) onStart()
    }
}

/** Required setup is visible before the library or tutorial, rather than hidden in Options. */
@Composable
fun CoreModelSetupScreen(
    progress: ModelStore.Progress?,
    error: String?,
    downloadBytes: Long,
    localBytes: Long,
    onRetry: () -> Unit,
    onImport: () -> Unit,
) {
    TransparentSetupNavigationBar()
    Box(
        Modifier.fillMaxSize().background(MangaPalette.Paper).testTag("coreModelSetup"),
    ) {
        Image(
            painter = painterResource(R.drawable.startup_screen_2),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            MangaPalette.Paper.copy(alpha = 0.94f),
            MangaPalette.Paper.copy(alpha = 0.18f),
            MangaPalette.Paper.copy(alpha = 0.28f),
        ))))
        Column(
            Modifier.fillMaxSize().navigationBarsPadding(),
        ) {
            ScreenHeader("Preparing Manga Lens", modifier = Modifier.padding(horizontal = 16.dp))
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 36.dp, vertical = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .background(MangaPalette.Paper.copy(alpha = 0.97f)).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text("Text recognition needs a few files before you can read your first page. " +
                        "Setup starts automatically and only needs to finish once.")
                    if (downloadBytes > 0) {
                        Text("One-time download: ${modelMegabytes(downloadBytes)}. Uses your current internet connection.")
                    }
                    if (localBytes > 0) {
                        Text("Also preparing ${modelMegabytes(localBytes)} already included with the app.")
                    }
                    Text("After setup, text recognition works offline. Optional translation models are downloaded separately.")
                    when {
                        progress != null -> {
                            Text(progress.label)
                            if (progress.bytesTotal > 0) {
                                LinearProgressIndicator(progress = { progress.fraction.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth())
                                Text("${modelMegabytes(progress.bytesDone)} of ${modelMegabytes(progress.bytesTotal)} prepared")
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                        error != null -> {
                            Text("Setup could not finish", style = MaterialTheme.typography.titleMedium)
                            Text(error, color = MaterialTheme.colorScheme.error)
                            Text("Check your internet connection and free storage, then try again. Completed files are kept.")
                            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Retry setup") }
                        }
                        else -> {
                            Text("Starting text recognition setup")
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    TextButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Text("Import existing model files", color = MangaPalette.Ink)
                    }
                }
            }
        }
    }
}

/** Let the artwork reach the screen edge without Android's three-button navigation scrim. */
@Composable
private fun TransparentSetupNavigationBar() {
    val view = LocalView.current
    val window = remember(view) {
        generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()?.window
    }
    DisposableEffect(window, view) {
        if (window == null) return@DisposableEffect onDispose { }
        val controller = WindowInsetsControllerCompat(window, view)
        val previousLightIcons = controller.isAppearanceLightNavigationBars
        val previousContrast = if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced else false
        val previousColor = if (Build.VERSION.SDK_INT < 29) window.navigationBarColor else 0
        controller.isAppearanceLightNavigationBars = true
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        } else {
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        onDispose {
            controller.isAppearanceLightNavigationBars = previousLightIcons
            if (Build.VERSION.SDK_INT >= 29) {
                window.isNavigationBarContrastEnforced = previousContrast
            } else {
                window.navigationBarColor = previousColor
            }
        }
    }
}

internal fun modelMegabytes(bytes: Long): String = if (bytes in 1..999_999)
    String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
else String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
