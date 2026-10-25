package com.mangareader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.mangareader.R

internal enum class HelpDialog { ABOUT, TUTORIAL }

private const val GITHUB_URL = "https://github.com/Okami-Dan/MangaLens"

@Composable
internal fun ReleaseHelpHost(
    preferences: ReleasePreferences,
    startupComplete: Boolean,
    requestedDialog: HelpDialog?,
    onDismiss: () -> Unit,
) {
    var automaticDialog by rememberSaveable { mutableStateOf<HelpDialog?>(null) }
    var tutorialChecked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(startupComplete) {
        if (startupComplete && !tutorialChecked) {
            val shown = preferences.tutorialWasShown()
            tutorialChecked = true
            if (!shown && requestedDialog == null) automaticDialog = HelpDialog.TUTORIAL
        }
    }
    val dismiss = { automaticDialog = null; onDismiss() }
    if (startupComplete) {
        when (requestedDialog ?: automaticDialog) {
            HelpDialog.ABOUT -> AboutAppDialog(onDismiss = dismiss)
            HelpDialog.TUTORIAL -> TutorialDialog(onDismiss = dismiss, onShown = preferences::markTutorialShown)
            null -> Unit
        }
    }
}

/** Copy from text/MangaLens text.docx; layout notes and blank links are not displayed. */
@Composable
internal fun AboutAppDialog(onDismiss: () -> Unit) {
    HelpPopup(title = "About Manga Lens", onDismiss = onDismiss, actions = {
        TextButton(shape = RectangleShape, onClick = onDismiss) { Text("Close") }
    }) {
        HelpParagraph("This app was developed with the physical manga experience in mind. Apart from providing straightforward LLM-powered translations, it encourages the reader to immerse themselves in the Japanese language structure. This way it can also be used both as a means to interact with your physical manga collection and as an immersive tool for language learning. This app is not intended as a replacement for proper literary translation.")
        HelpParagraph("Manga Lens encourages ethical usage of LLMs, relying on on-device compute so the authors’ works are never sent to training pools. If, however, you wish to use your own API token (including major AI companies’ services), the LLM only sees per-page text. No artwork is sent to the servers and the app does not collect any data.")
        HelpParagraph("Manga Lens is developed using AI and will remain free for personal use, ad-less and open-source.")
        HelpParagraph("If you want to know more about the technology behind this app, visit the official repository on GitHub:")
        GitHubButton()
        HelpParagraph("If you want to support the developer, consider a one-time tip to my Patreon account.")
        HelpParagraph("Tipping is entirely optional and won’t affect your experience with the app.")
    }
}

private data class TutorialSection(
    val title: String,
    val paragraphs: List<String>,
    val note: String? = null,
    val github: Boolean = false,
    val clip: TutorialClip? = null,
)

private val tutorialSections = listOf(
    TutorialSection(
        "Welcome to Manga Lens",
        listOf(
            "Welcome to Manga Lens: a modern way to interact with Japanese manga!",
            "To start using the app, create a new series by tapping ‘+ new series’ in Library. Create your first volume and take a photo of a page. The text will automatically be recognized and lexical and grammatical structures explained! You can check this tutorial later in the Options menu.",
            "Text recognition is prepared automatically the first time you open the app. Once setup finishes, it works offline.",
        ),
        clip = TutorialClip(R.raw.tutorial_welcome, R.drawable.tutorial_welcome_poster, "Create a library and review text recognition setup"),
    ),
    TutorialSection(
        "Setting up your translation engine",
        listOf("By default, translations in Manga Lens are turned off. You can configure the provider of your choice in Options → Translation, including on-device compute and remote API services. Note that the app does not collect any of your data or send artwork to any remote services."),
        note = "See ‘About app’ in the Options menu for more info on how data is treated.",
        clip = TutorialClip(R.raw.tutorial_translation, R.drawable.tutorial_translation_poster, "Choose a translation provider"),
    ),
    TutorialSection(
        "Capturing photos",
        listOf("When a new page is captured, you may be offered the option to adjust the frame. Drag the corners of the frame, or tap ‘Whole frame’ to use the entire photo. Tap ‘Use’ to start text recognition. You may recapture the photo or take a picture of the next page by tapping the capture button."),
        clip = TutorialClip(R.raw.tutorial_capture, R.drawable.tutorial_capture_poster, "Capture, crop and recognize a page"),
    ),
    TutorialSection(
        "Reading speech bubbles",
        listOf("Tap on the recognized text to see its breakdown. If you need romanization, you can turn it on in the Options menu. Check the phrase transcription, lexical and grammatical breakdown. If you have configured a translation engine, you can translate by tapping the Translate button in the bottom corner of the page viewer or in the bubble view screen."),
        clip = TutorialClip(R.raw.tutorial_reading, R.drawable.tutorial_reading_poster, "Read transcription, breakdown and Romaji"),
    ),
    TutorialSection(
        "Bookmarking your words",
        listOf("See a word that is worth remembering? Tap the bookmark icon to add it to your Words menu. If you wish to revisit the page this word was bookmarked on, just tap the word. To unpin the word, tap the bookmark icon again."),
        clip = TutorialClip(R.raw.tutorial_bookmarks, R.drawable.tutorial_bookmarks_poster, "Save and revisit a word"),
    ),
    TutorialSection(
        "Managing your library",
        listOf(
            "Long press on an item card to open additional options.",
            "Finished a volume and don’t want to store the pages? You can archive it: doing so will delete the pages without pinned words on them. To avoid deleting necessary pages, consider making a separate manual backup file in the Options menu.",
            "A page or a volume accidentally ended up in the wrong place? You can move it to a destination of your choice.",
            "Sort your items at the top of the screen if your library gets difficult to navigate.",
        ),
        clip = TutorialClip(R.raw.tutorial_library, R.drawable.tutorial_library_poster, "Back up, move, sort and archive"),
    ),
    TutorialSection(
        "Bulk import",
        listOf("Although this app is designed to interact with physical manga, you can bulk import multiple photos of pages or entire PDF scans of manga by long pressing on a volume card. Note that recognition will be done on a per-page basis. You can read the pages as soon as each individual one is recognized; there’s no need to wait for a complete analysis."),
        clip = TutorialClip(R.raw.tutorial_import, R.drawable.tutorial_import_poster, "Import PNG and PDF pages"),
    ),
    TutorialSection(
        "Enjoy your manga reading experience!",
        listOf(
            "For more information on Manga Lens, check ‘About app’ in the Options menu or visit the GitHub page.",
            "If you like what you see, consider tipping to my Patreon account. This will allow for more creative projects and improvements to Manga Lens! Tipping is entirely optional.",
            "Thank you for using Manga Lens!",
        ),
        github = true,
    ),
)

@Composable
internal fun TutorialDialog(onDismiss: () -> Unit, onShown: suspend () -> Unit = {}) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    // Keep saved playback state in the caller's registry, outside Dialog's view.
    val playback = rememberTutorialPlaybackState(page)
    val section = tutorialSections[page]
    LaunchedEffect(Unit) {
        // Do not consume first-run onboarding while it is hidden behind startup art.
        withFrameNanos { }
        // Once displayed, even an immediate Skip or rotation must finish the disk write.
        withContext(NonCancellable) { onShown() }
    }
    HelpPopup(title = "Tutorial", onDismiss = onDismiss, actions = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(shape = RectangleShape, onClick = { page-- }, enabled = page > 0) { Text("Back") }
            TextButton(shape = RectangleShape, onClick = {
                if (page == tutorialSections.lastIndex) onDismiss() else page++
            }) { Text(if (page == tutorialSections.lastIndex) "Done" else "Next") }
        }
        if (page != tutorialSections.lastIndex) {
            TextButton(shape = RectangleShape, onClick = onDismiss) { Text("Skip") }
        }
    }, contentKey = page) {
        Text("Section ${page + 1} of ${tutorialSections.size}", style = MaterialTheme.typography.labelMedium,
            color = MangaPalette.Dim, modifier = Modifier.testTag("tutorialProgress"))
        Text(section.title, style = MaterialTheme.typography.titleMedium)
        section.clip?.let { TutorialAnimation(it, playback) }
        section.paragraphs.forEach { HelpParagraph(it) }
        section.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MangaPalette.Dim) }
        if (section.github) GitHubButton()
    }
}

@Composable
private fun HelpPopup(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    contentKey: Any = Unit,
    content: @Composable () -> Unit,
) {
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.9f
    val density = LocalDensity.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Dialog creates another Android view; retain the caller's layout/text scaling.
        CompositionLocalProvider(LocalDensity provides density) {
            Surface(color = MangaPalette.Paper, shape = RectangleShape,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth()
                    .heightIn(max = maxHeight).testTag("helpPopup")) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    key(contentKey) {
                        val scroll = rememberScrollState()
                        Column(Modifier.weight(1f, fill = false).fillMaxWidth()
                            .scrollbar(scroll, Depth.SERIES.thumb).verticalScroll(scroll).testTag("helpContent"),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
                    }
                    actions()
                }
            }
        }
    }
}

@Composable
private fun HelpParagraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun GitHubButton() {
    val uriHandler = LocalUriHandler.current
    var unavailable by remember { mutableStateOf(false) }
    TextButton(shape = RectangleShape, onClick = {
        unavailable = runCatching { uriHandler.openUri(GITHUB_URL) }.isFailure
    }) { Text("GitHub") }
    if (unavailable) {
        SelectionContainer { Text(GITHUB_URL, style = MaterialTheme.typography.bodySmall) }
    }
}
