package com.mangareader.ui

import androidx.compose.ui.graphics.RectangleShape
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mangareader.database.MangaDatabase
import com.mangareader.database.SeriesEntity
import com.mangareader.database.VolumeEntity
import com.mangareader.model.PointF
import com.mangareader.model.PipelineStage
import com.mangareader.pipeline.PageStore
import com.mangareader.pipeline.ModelStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * The app shell, in the design from `Design mockups/`.
 *
 * The structure is unchanged from M5 -- three tabs, a modal capture flow, and a
 * series -> volume -> page drill-down inside Library. What changed is that none of the
 * chrome is Material's any more: the mockups draw their own header, their own tab bar
 * and their own footer action, and a `TopAppBar` over a `NavigationBar` cannot be talked
 * into looking like them. `Scaffold` stays only for the snackbar and for somewhere to
 * hang the bottom bar; every inset is handled by the pieces in `Design.kt`.
 */

/** Section 3.1's capture flow is a modal over the tabs, not a fourth tab. */
private sealed interface Route {
    data object Tabs : Route
    data class Camera(val volumeId: String, val returnPageId: String? = null) : Route
    data class Confirm(
        val jpeg: ByteArray,
        val rotationDegrees: Int,
        val volumeId: String,
        val returnPageId: String? = null,
    ) : Route
    data class Reader(val pageId: String) : Route
}

/**
 * Where the user is inside the Library tab. A stack rather than a single value, because
 * three levels of folder need three levels of back -- and it lives beside [Route] rather
 * than inside it so that drilling into a series keeps the tab bar, which is still where
 * the user is.
 */
private sealed interface Folder {
    data object Series : Folder
    data class Volumes(val seriesId: String) : Folder
    data class Pages(val seriesId: String, val volumeId: String) : Folder
}

/** Which creation/edit dialog is open, if any. */
private sealed interface Dialog {
    data class Series(val existing: SeriesEntity?) : Dialog
    data class Volume(
        val seriesId: String,
        val existing: VolumeEntity?,
        val suggestedNumber: Int,
    ) : Dialog

    data class ConfirmDelete(val what: String, val detail: String, val onConfirm: () -> Unit) :
        Dialog

    data class ConfirmArchive(
        val volume: VolumeEntity,
        val retained: Int,
        val discarded: Int,
    ) : Dialog

    /** A volume looking for a series, or a page looking for a volume. */
    data class Move(
        val title: String,
        val empty: String,
        val targets: List<MoveTarget>,
        val onPick: (String) -> Unit,
    ) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MangaReaderApp(
    restoredFromVault: Boolean = false,
    restoredFromBackup: Boolean = false,
    startupComplete: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PageStore(context) }
    val database = remember { MangaDatabase.get(context) }
    // Owned here rather than by the reader: analysis outlives the screen that starts
    // it, so walking back to the library mid-page does not cancel the OCR.
    val analysis = remember { AnalysisController(context, scope) }
    val settings = remember { ReaderSettings(context, scope) }
    // Stage 7 gets its own controller for the same reason and one more: a translation is
    // a paid call or a minute of CPU, so it must not be cancelled by the analysis job or
    // cancel it in turn.
    val translation = remember { TranslationController(context, scope, settings) }
    // Tier 4's model half. Separate from [translation] because it is a different job
    // shape -- one bubble on demand rather than a page in the background -- but it reads
    // the same tier, the same key and the same on-device model out of that controller
    // rather than resolving settings a second time.
    val grammar = remember { GrammarController(context, scope, settings, translation) }
    // And the folders get one for a weaker version of the same reason: copying a picked
    // cover is disk work that must outlive the dialog that asked for it.
    val library = remember { LibraryController(context, scope) }
    // The library's storage: where it lives, moving an old install's files into the vault,
    // and the export that is the only copy an uninstall cannot reach.
    val storage = remember { StorageController(context, scope, settings, restoredFromVault) }
    val series by remember { database.series().observeAll() }.collectAsState(emptyList())
    val volumes by remember { database.volumes().observeAll() }.collectAsState(emptyList())
    val pages by remember { database.pages().observeAll() }.collectAsState(emptyList())
    val savedWords by remember { database.savedWords().observeAll() }.collectAsState(emptyList())
    // Bubble counts for the page grid's captions -- one grouped query for the whole app,
    // rather than a flow per card.
    val regionCounts by remember { database.regions().observeCounts() }.collectAsState(emptyList())
    val furigana by settings.furigana.collectAsState()
    val romaji by settings.romaji.collectAsState()
    // All four, unconditionally: which one is in force depends on where the user is
    // standing, and a collect inside that `when` would be a composable called from a
    // branch -- which is exactly what Compose's rules forbid.
    val seriesSort by settings.sort(SortScope.SERIES).collectAsState()
    val volumeSort by settings.sort(SortScope.VOLUMES).collectAsState()
    val pageSort by settings.sort(SortScope.PAGES).collectAsState()
    val wordSort by settings.sort(SortScope.WORDS).collectAsState()
    val sheetSettings = SheetSettings(furigana = furigana, romaji = romaji)

    // Stage 6's dictionary is an APK asset and has to be unpacked before SQLite can
    // open it. Doing it here means the 53 MB copy overlaps the user finding their way
    // to the camera rather than landing in the middle of their first page.
    LaunchedEffect(Unit) { analysis.ensureDictionary() }
    AutoStartCoreModelSetup(analysis.modelsReady, startupComplete, analysis::installModels)

    // Same reasoning for the library's own files: an install that predates the vault has
    // its pages in `filesDir`, where the next reinstall would delete them. The move runs
    // in the background and is resumable, so it costs the user nothing but a progress
    // line in Options.
    LaunchedEffect(Unit) { storage.adoptIfNeeded() }

    var selected by remember { mutableStateOf(MangaTab.LIBRARY) }
    var route by remember { mutableStateOf<Route>(Route.Tabs) }
    var folders by remember { mutableStateOf<List<Folder>>(listOf(Folder.Series)) }
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var helpDialog by rememberSaveable { mutableStateOf<HelpDialog?>(null) }
    var proposedQuad by remember { mutableStateOf<List<PointF>?>(null) }
    var quadWasFound by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val workManager = remember { WorkManager.getInstance(context) }
    val importInfos by remember(workManager) {
        workManager.getWorkInfosForUniqueWorkFlow(PageImportWorker.WORK_NAME)
    }.collectAsState(emptyList())
    val importInfo = importInfos.lastOrNull()
    val importActive = importInfo?.state == WorkInfo.State.RUNNING ||
        importInfo?.state == WorkInfo.State.ENQUEUED
    var pendingImportVolumeId by remember { mutableStateOf<String?>(null) }

    fun enqueueImport(uris: List<Uri>, pdf: Boolean) {
        val volumeId = pendingImportVolumeId ?: return
        pendingImportVolumeId = null
        if (uris.isEmpty()) return
        if (importActive) {
            scope.launch { snackbar.showSnackbar("Another import is already running.") }
            return
        }
        scope.launch {
            val granted = mutableListOf<Uri>()
            try {
                withContext(Dispatchers.IO) {
                    uris.forEach { uri ->
                        context.contentResolver.takePersistableUriPermission(
                            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        granted += uri
                    }
                    val ordered = uris.map { uri ->
                        uri to Regex("\\d+").replace(
                            PageImportWorker.displayName(context, uri).lowercase()) {
                            it.value.padStart(12, '0')
                        }
                    }.sortedWith(compareBy<Pair<Uri, String>> { it.second }.thenBy { it.first.toString() })
                        .map { it.first }
                    PageImportWorker.enqueue(context, volumeId, ordered, pdf = pdf)
                }
            } catch (failure: Exception) {
                granted.forEach { uri ->
                    runCatching { context.contentResolver.releasePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
                snackbar.showSnackbar("Could not start import: ${failure.message}")
            }
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        enqueueImport(listOfNotNull(uri), pdf = true)
    }
    val pngPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        enqueueImport(uris, pdf = false)
    }

    LaunchedEffect(importInfo?.id, importInfo?.state) {
        when (importInfo?.state) {
            WorkInfo.State.SUCCEEDED -> {
                PageImportWorker.cleanupAbandoned(context)
                snackbar.showSnackbar(
                    importInfo.outputData.getString(PageImportWorker.KEY_SUMMARY) ?: "Import complete.")
            }
            WorkInfo.State.FAILED -> {
                PageImportWorker.cleanupAbandoned(context)
                snackbar.showSnackbar(
                    importInfo.outputData.getString(PageImportWorker.KEY_ERROR) ?: "Import failed.")
            }
            WorkInfo.State.CANCELLED -> PageImportWorker.cleanupAbandoned(context)
            else -> Unit
        }
    }

    val folder = folders.last()
    // The page grid's volume. Reader capture takes its target from the opened page
    // instead, since a Saved words link may open a page from another volume.
    val openVolumeId = (folder as? Folder.Pages)?.volumeId

    // A model staged with `adb push` after the app started is invisible until something
    // looks again; every route change is cheap enough to look (four File.length calls).
    LaunchedEffect(route) { analysis.refreshModels() }

    // Said once, on the launch it happened. Somebody who has just cleared the app's data
    // and found their shelf intact should be told why rather than left to assume.
    if (restoredFromVault) {
        LaunchedEffect(Unit) {
            snackbar.showSnackbar("Library restored from device storage.")
        }
    }

    // And the louder version, for the restart at the end of a restore: this one is the
    // confirmation that the file the user picked actually held their library.
    if (restoredFromBackup) {
        LaunchedEffect(Unit) {
            snackbar.showSnackbar("Library restored from your backup.")
        }
    }

    library.error?.let { message ->
        LaunchedEffect(message) {
            snackbar.showSnackbar(message)
            library.clearError()
        }
    }

    // The routes are plain state rather than a NavHost, so nothing teaches the system
    // back button about them -- without this it falls through to the Activity and the
    // app closes from inside the reader. Confirm goes back to the viewfinder, since
    // section 3.1 wants retake to be one tap in either direction. Inside the tabs the
    // same button walks back up the folder stack, one level per press.
    BackHandler(enabled = route != Route.Tabs || folders.size > 1) {
        when {
            route is Route.Confirm -> {
                val confirm = route as Route.Confirm
                route = Route.Camera(confirm.volumeId, confirm.returnPageId)
            }
            route is Route.Camera -> {
                val camera = route as Route.Camera
                route = camera.returnPageId?.let { Route.Reader(it) } ?: Route.Tabs
            }
            route != Route.Tabs -> route = Route.Tabs
            else -> folders = folders.dropLast(1)
        }
    }

    if (!analysis.modelsReady) {
        val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            analysis.importModels(it)
        }
        val pending = (analysis.models as? ModelStore.Status.Incomplete)?.pending.orEmpty()
        CoreModelSetupScreen(
            progress = analysis.install,
            error = analysis.modelError,
            downloadBytes = pending.filter { it.source == ModelStore.Source.REMOTE }.sumOf { it.artifact.bytes },
            localBytes = pending.filter { it.source == ModelStore.Source.BUNDLED || it.source == ModelStore.Source.STAGED }
                .sumOf { it.artifact.bytes },
            onRetry = analysis::installModels,
            onImport = { modelPicker.launch(arrayOf("*/*")) },
        )
        return
    }

    when (val current = route) {
        is Route.Reader -> {
            val page by remember(current.pageId) {
                database.pages().observe(current.pageId)
            }.collectAsState(null)
            val regions by remember(current.pageId) {
                database.regions().observePage(current.pageId)
            }.collectAsState(emptyList())

            val tokens by remember(current.pageId) {
                database.tokens().observePage(current.pageId)
            }.collectAsState(emptyList())

            page?.let {
                ReaderScreen(
                    page = it,
                    regions = regions,
                    tokens = tokens,
                    savedWords = savedWords,
                    settings = sheetSettings,
                    analysis = analysis,
                    translation = translation,
                    grammar = grammar,
                    tab = selected,
                    onTab = { tab ->
                        selected = tab
                        route = Route.Tabs
                    },
                    onBack = { route = Route.Tabs },
                    onCapture = {
                        route = Route.Camera(it.volumeId, returnPageId = it.id)
                    },
                )
            }
            return
        }

        is Route.Camera -> {
            CameraScreen(
                onCaptured = { jpeg, rotation ->
                    proposedQuad = null
                    route = Route.Confirm(
                        jpeg, rotation, current.volumeId, current.returnPageId,
                    )
                },
                onCancel = {
                    route = current.returnPageId?.let { Route.Reader(it) } ?: Route.Tabs
                },
            )
            return
        }

        is Route.Confirm -> {
            // Propose corners off the UI thread; the quad search resizes and runs two
            // passes of contour work, which is not free on a 12 MP still.
            LaunchedEffect(current) {
                val (quad, found) = store.proposeQuad(current.jpeg, current.rotationDegrees)
                proposedQuad = quad
                quadWasFound = found
            }
            ConfirmScreen(
                jpeg = current.jpeg,
                rotationDegrees = current.rotationDegrees,
                initialQuad = proposedQuad,
                quadWasFound = quadWasFound,
                busy = busy,
                onRetake = { route = Route.Camera(current.volumeId, current.returnPageId) },
                onUse = { framing ->
                    busy = true
                    scope.launch {
                        val stored = runCatching {
                            store.store(
                                jpeg = current.jpeg,
                                rotationDegrees = current.rotationDegrees,
                                framing = framing,
                                volumeId = current.volumeId,
                            )
                        }
                        busy = false
                        // Section 3.1 step 5: Use runs the pipeline. The reader is where
                        // that progress is shown, so the page opens rather than dropping
                        // the user back on the library grid to wonder.
                        stored.onSuccess { route = Route.Reader(it.id) }
                        stored.onFailure {
                            route = current.returnPageId?.let { Route.Reader(it) } ?: Route.Tabs
                            snackbar.showSnackbar("Could not save: ${it.message}")
                        }
                    }
                },
            )
            return
        }

        Route.Tabs -> Unit
    }

    val openSeries = (folder as? Folder.Volumes)?.seriesId
        ?: (folder as? Folder.Pages)?.seriesId
    val seriesRow = series.firstOrNull { it.id == openSeries }
    val volumeRow = volumes.firstOrNull { it.id == openVolumeId }

    // The depth colours every tinted surface on the screen: the card bands, the footer
    // action, the scrollbar. Words and Options are not part of the drill-down, so they
    // take the app's own accent.
    val depth = when {
        selected != MangaTab.LIBRARY -> Depth.SERIES
        folder is Folder.Volumes -> Depth.VOLUME
        folder is Folder.Pages -> Depth.PAGE
        else -> Depth.SERIES
    }

    // Which grid the header's sort menu is talking about. Options has nothing to sort.
    val sortScope = when {
        selected == MangaTab.WORDS -> SortScope.WORDS
        selected == MangaTab.OPTIONS -> null
        folder is Folder.Volumes -> SortScope.VOLUMES
        folder is Folder.Pages -> SortScope.PAGES
        else -> SortScope.SERIES
    }
    val sortOrder = when (sortScope) {
        SortScope.SERIES -> seriesSort
        SortScope.VOLUMES -> volumeSort
        SortScope.PAGES -> pageSort
        SortScope.WORDS -> wordSort
        null -> null
    }

    Scaffold(
        containerColor = MangaPalette.Paper,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.background(MangaPalette.Paper)) {
                // One action per depth: make a shelf, put a volume on it, photograph
                // into it. Words and Options have nothing to add, so they show no rule
                // and no button rather than a disabled one.
                if (selected == MangaTab.LIBRARY) {
                    Column(
                        Modifier.padding(
                            start = ScreenMargin,
                            end = ScreenMargin,
                            bottom = 9.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        InkRule()
                        when (folder) {
                            is Folder.Series -> DashedAction(
                                label = "+ new series",
                                depth = Depth.SERIES,
                                onClick = { dialog = Dialog.Series(null) },
                            )

                            is Folder.Volumes -> DashedAction(
                                label = "+ new volume",
                                depth = Depth.VOLUME,
                                onClick = {
                                    scope.launch {
                                        dialog = Dialog.Volume(
                                            seriesId = folder.seriesId,
                                            existing = null,
                                            suggestedNumber = library.nextNumber(folder.seriesId),
                                        )
                                    }
                                },
                            )

                            is Folder.Pages -> DashedAction(
                                label = "+ new page",
                                depth = Depth.PAGE,
                                onClick = { route = Route.Camera(folder.volumeId) },
                            )
                        }
                    }
                }
                MangaTabBar(selected = selected, onSelect = { selected = it })
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (importActive) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().padding(horizontal = ScreenMargin),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        importInfo?.progress?.getString(PageImportWorker.KEY_LABEL)
                            ?: "Import queued…",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(shape = RectangleShape, onClick = {
                        importInfo?.id?.let(workManager::cancelWorkById)
                    }) { Text("Cancel") }
                }
            }
            ScreenHeader(
                title = when {
                    selected == MangaTab.WORDS -> "Saved words"
                    selected == MangaTab.OPTIONS -> "Options"
                    folder is Folder.Series -> "My library"
                    else -> seriesRow?.title ?: "My library"
                },
                subtitle = (folder as? Folder.Pages)?.let { volumeRow?.label },
                onBack = if (selected == MangaTab.LIBRARY && folders.size > 1) {
                    { folders = folders.dropLast(1) }
                } else {
                    null
                },
                trailing = {
                    if (sortScope != null && sortOrder != null) {
                        SortMenu(
                            current = sortOrder,
                            options = sortScope.options,
                            depth = depth,
                        ) { settings.setSort(sortScope, it) }
                    }
                },
            )

            Box(Modifier.weight(1f)) {
                when (selected) {
                    MangaTab.LIBRARY -> when (folder) {
                        is Folder.Series -> SeriesScreen(
                            series = sortSeries(series, seriesSort),
                            volumes = volumes,
                            pages = pages,
                            onOpen = { folders = folders + Folder.Volumes(it.id) },
                            onEdit = { dialog = Dialog.Series(it) },
                            onDelete = { entry ->
                                dialog = Dialog.ConfirmDelete(
                                    what = entry.title,
                                    detail = "Every volume, page and translation in it goes too.",
                                ) { library.deleteSeries(entry.id) }
                            },
                        )

                        is Folder.Volumes -> VolumesScreen(
                            volumes = sortVolumes(
                                volumes.filter { it.seriesId == folder.seriesId },
                                volumeSort,
                            ),
                            pages = pages,
                            onOpen = { folders = folders + Folder.Pages(folder.seriesId, it.id) },
                            onEdit = { volume ->
                                scope.launch {
                                    dialog = Dialog.Volume(
                                        seriesId = folder.seriesId,
                                        existing = volume,
                                        suggestedNumber = volume.number,
                                    )
                                }
                            },
                            onMove = { volume ->
                                dialog = Dialog.Move(
                                    title = "Move " + volume.label,
                                    empty = "There is nowhere else to put it — this " +
                                        "is your only series.",
                                    targets = series
                                        .filter { it.id != volume.seriesId }
                                        .map { MoveTarget(it.id, it.title, it.japaneseTitle) },
                                ) { library.moveVolume(volume.id, it) }
                            },
                            onImportPdf = { volume ->
                                if (analysis.modelsReady && !importActive) {
                                    pendingImportVolumeId = volume.id
                                    pdfPicker.launch(arrayOf("application/pdf"))
                                } else scope.launch { snackbar.showSnackbar(
                                    if (!analysis.modelsReady) "Install OCR models in Options first."
                                    else "Another import is already running.") }
                            },
                            onImportPng = { volume ->
                                if (analysis.modelsReady && !importActive) {
                                    pendingImportVolumeId = volume.id
                                    pngPicker.launch(arrayOf("image/png"))
                                } else scope.launch { snackbar.showSnackbar(
                                    if (!analysis.modelsReady) "Install OCR models in Options first."
                                    else "Another import is already running.") }
                            },
                            onRetryOcr = { volume ->
                                if (analysis.modelsReady && !importActive && pages.any {
                                        it.volumeId == volume.id &&
                                            it.stage.index < PipelineStage.ANALYSED.index
                                    }) {
                                    PageImportWorker.enqueue(context, volume.id, emptyList(), retryOnly = true)
                                } else scope.launch { snackbar.showSnackbar(
                                    if (!analysis.modelsReady) "Install OCR models in Options first."
                                    else if (importActive) "Another import is already running."
                                    else "No pages need OCR.") }
                            },
                            onArchive = { volume ->
                                val volumePages = pages.filter { it.volumeId == volume.id }
                                val pinnedIds = savedWords.mapNotNull { it.sourcePageId }.toSet()
                                val retained = volumePages.count { it.id in pinnedIds }
                                dialog = Dialog.ConfirmArchive(
                                    volume = volume,
                                    retained = retained,
                                    discarded = volumePages.size - retained,
                                )
                            },
                            onDelete = { volume ->
                                dialog = Dialog.ConfirmDelete(
                                    what = volume.label,
                                    detail = "Every page and translation in it goes too.",
                                ) { library.deleteVolume(volume.id) }
                            },
                        )

                        is Folder.Pages -> LibraryScreen(
                            pages = sortPages(
                                pages.filter { it.volumeId == folder.volumeId },
                                pageSort,
                            ),
                            regionCounts = remember(regionCounts) {
                                regionCounts.associate { it.pageId to it.regions }
                            },
                            onOpen = { route = Route.Reader(it.id) },
                            onMove = { page ->
                                dialog = Dialog.Move(
                                    title = "Move page " + (page.readingIndex + 1),
                                    empty = "There is nowhere else to put it — this " +
                                        "is your only volume.",
                                    // Flat, and labelled with the series, because a page
                                    // moves between shelves as often as within one.
                                    targets = volumes
                                        .filter { it.id != page.volumeId }
                                        .map { volume ->
                                            volume to series.firstOrNull {
                                                it.id == volume.seriesId
                                            }
                                        }
                                        .sortedWith(
                                            compareBy(
                                                { it.second?.title.orEmpty() },
                                                { it.first.number },
                                            ),
                                        )
                                        .map { (volume, owner) ->
                                            MoveTarget(
                                                id = volume.id,
                                                label = owner?.title.orEmpty(),
                                                detail = volume.label,
                                            )
                                        },
                                ) { library.movePage(page.id, it) }
                            },
                            onDelete = { page ->
                                dialog = Dialog.ConfirmDelete(
                                    what = "page " + (page.readingIndex + 1),
                                    detail = "Its bubbles and translations go too, and " +
                                        "any word saved off it keeps its sentence but " +
                                        "loses the link back.",
                                ) { library.deletePage(page.id) }
                            },
                        )
                    }

                    MangaTab.WORDS -> WordsScreen(
                        words = sortWords(savedWords, wordSort),
                        onRemove = { analysis.unpin(it.baseForm, it.reading) },
                        onOpen = { pageId -> route = Route.Reader(pageId) },
                    )

                    MangaTab.OPTIONS -> SettingsScreen(
                        analysis = analysis,
                        translation = translation,
                        grammar = grammar,
                        storage = storage,
                        settings = settings,
                        furigana = furigana,
                        romaji = romaji,
                        onAbout = { helpDialog = HelpDialog.ABOUT },
                        onTutorial = { helpDialog = HelpDialog.TUTORIAL },
                    )
                }
            }
        }
    }

    ReleaseHelpHost(settings.release, startupComplete, helpDialog) { helpDialog = null }

    when (val open = dialog) {
        is Dialog.Series -> SeriesDialog(
            existing = open.existing,
            onDismiss = { dialog = null },
            onConfirm = { title, japanese, wiki, cover ->
                if (open.existing == null) {
                    library.createSeries(title, japanese, wiki, cover)
                } else {
                    library.editSeries(open.existing.id, title, japanese, wiki, cover)
                }
                dialog = null
            },
        )

        is Dialog.Volume -> VolumeDialog(
            existing = open.existing,
            suggestedNumber = open.suggestedNumber,
            onDismiss = { dialog = null },
            onConfirm = { number, cover ->
                if (open.existing == null) {
                    library.createVolume(open.seriesId, number, cover)
                } else {
                    library.editVolume(open.existing.id, number, cover)
                }
                dialog = null
            },
        )

        is Dialog.Move -> MoveDialog(
            title = open.title,
            empty = open.empty,
            targets = open.targets,
            onDismiss = { dialog = null },
            onPick = { target ->
                open.onPick(target)
                dialog = null
            },
        )

        is Dialog.ConfirmDelete -> AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = MangaPalette.Paper,
            title = { Text("Delete ${open.what}?") },
            text = { Text(open.detail + " This cannot be undone.") },
            confirmButton = {
                TextButton(shape = RectangleShape,
                    // No stack change: the card being deleted is one level *below*
                    // where the user is standing, so the grid they are looking at is
                    // still there -- with one fewer card in it.
                    onClick = {
                        open.onConfirm()
                        dialog = null
                    },
                ) { Text("Delete", color = MangaPalette.Alarm) }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { dialog = null }) { Text("Cancel") } },
        )

        is Dialog.ConfirmArchive -> AlertDialog(
            onDismissRequest = { dialog = null },
            containerColor = MangaPalette.Paper,
            title = { Text("Archive ${open.volume.label}?") },
            text = {
                Text(
                    "Keep ${open.retained} ${if (open.retained == 1) "page" else "pages"} " +
                        "linked from saved words. Permanently delete ${open.discarded} " +
                        "${if (open.discarded == 1) "page" else "pages"} without saved word " +
                        "links. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(shape = RectangleShape, onClick = {
                    library.archiveVolume(open.volume.id)
                    dialog = null
                }) { Text("Continue", color = MangaPalette.Alarm) }
            },
            dismissButton = { TextButton(shape = RectangleShape, onClick = { dialog = null }) { Text("Cancel") } },
        )

        null -> Unit
    }
}

@Composable
internal fun Placeholder(
    modifier: Modifier = Modifier,
    title: String,
    detail: String,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MangaPalette.Dim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
