package com.mangareader.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import com.mangareader.model.TranslationContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mangareader.database.PageEntity
import com.mangareader.R
import com.mangareader.database.RegionEntity
import com.mangareader.database.SavedWordEntity
import com.mangareader.database.TokenEntity
import com.mangareader.model.PageKind
import com.mangareader.model.PipelineStage
import com.mangareader.model.RegionKind
import com.mangareader.ocr.MangaOcr
import com.mangareader.pipeline.PageAnalyzer
import com.mangareader.pipeline.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Section 3.2's reading screen, in the design from `Design mockups/Page view.pdf`.
 *
 * The photograph runs edge to edge with the tab bar still under it, the way the mockup
 * draws it -- the reader is a place inside the app, not a mode the app leaves for. Two
 * marks sit over the page: a dashed amber frame with a solid corner tab on every detected
 * bubble, and capture/translate controls at bottom right.
 *
 * The bubble sheet is [BubbleSheet], shown here as a panel over the lower part of the
 * photograph rather than a `ModalBottomSheet`: the mockup has no scrim, keeps the tab bar
 * visible underneath, and squares the sheet's top corners like everything else.
 */
@Composable
fun ReaderScreen(
    page: PageEntity,
    regions: List<RegionEntity>,
    tokens: List<TokenEntity>,
    savedWords: List<SavedWordEntity>,
    settings: SheetSettings,
    analysis: AnalysisController,
    translation: TranslationController,
    grammar: GrammarController,
    tab: MangaTab,
    onTab: (MangaTab) -> Unit,
    onBack: () -> Unit,
    onCapture: (PageEntity) -> Unit,
) {
    val running = analysis.running?.takeIf { it.pageId == page.id }
    val translating = translation.running?.takeIf { it.pageId == page.id }
    var selected by remember(page.id) { mutableStateOf<Long?>(null) }
    val series by remember(page.id) { translation.observeContext(page.id) }
        .collectAsState(initial = TranslationContext())
    LaunchedEffect(page.id, page.volumeId, page.kind, series, translation.contextNote,
        regions.map { it.translationKey }) {
        translation.validateStoredContext(page.id)
    }
    LaunchedEffect(page.id, page.stage, analysis.dictionaryReady) {
        if (page.stage.index >= PipelineStage.ANALYSED.index) analysis.refreshAnalysis(page.id)
    }

    // Opening an unanalysed page starts the work rather than waiting to be asked: the
    // user arrives here straight from Use on the confirm screen, and section 3.1 step 5
    // has the pipeline running at that point.
    LaunchedEffect(page.id, page.stage, analysis.modelsReady, analysis.running?.pageId) {
        // Keyed on the stage, not on `regions`, which arrives from Room a frame late --
        // an emptiness test here would re-analyse every page the moment it opened.
        if (page.stage.index < PipelineStage.ANALYSED.index && analysis.modelsReady &&
            analysis.running == null && analysis.error == null
        ) {
            analysis.analyse(page.id)
        }
    }

    // Back closes the sheet before it leaves the page: the sheet is the thing most
    // recently opened, so it is the thing back should take away.
    BackHandler(enabled = selected != null) { selected = null }

    val visibleRegions = regions.filter(::isVisibleRegion)
    val region = visibleRegions.firstOrNull { it.regionId == selected }

    Column(
        Modifier
            .fillMaxSize()
            .background(MangaPalette.Paper),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MangaPalette.Plate),
        ) {
            PageCanvas(
                page = page,
                regions = visibleRegions,
                selected = selected,
                onSelect = { selected = it },
            )

            ReaderBar(
                label = "Page " + (page.readingIndex + 1),
                onBack = onBack,
                modifier = Modifier.align(Alignment.TopStart),
            )

            // Stage 7 is opt-in per page (docs/on-device-llm.md section 6): it costs
            // money on tier 2 and about a minute on tier 1, so it is a tap rather than
            // something that happens when a page opens.
            Column(
                modifier = Modifier.align(Alignment.BottomEnd)
                    .padding(end = ScreenMargin, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ReaderActionBadge(
                    drawable = R.drawable.ic_new_page,
                    description = "Capture a new page",
                    enabled = true,
                    onClick = { onCapture(page) },
                )
                if (translation.configured && regions.isNotEmpty()) {
                    ReaderActionBadge(
                        drawable = R.drawable.ic_translate_page,
                        description = "Translate page",
                        enabled = translating == null && running == null,
                        onClick = { translation.translate(page.id) },
                    )
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!analysis.modelsReady) {
                    ModelsCard(analysis)
                }
                running?.let { StagePanel(it) }
                translating?.let { TranslatePanel(it) }
                (analysis.error ?: translation.error)?.let { message ->
                    ReaderPanel {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MangaPalette.Alarm,
                        )
                    }
                }
                if (running == null && page.stage.index < PipelineStage.ANALYSED.index && analysis.modelsReady) {
                    ReaderPanel {
                        Text(
                            "Not analysed yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            PageAnalyzer.Stage.entries
                                .filter { it.number > 0 && it != PageAnalyzer.Stage.TRANSLATE }
                                .joinToString("  →  ") { it.label },
                            style = MaterialTheme.typography.bodySmall,
                            color = MangaPalette.Dim,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Box(
                            Modifier
                                .padding(top = 11.dp)
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(MangaPalette.Orange)
                                .clickable { analysis.analyse(page.id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Analyse page",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xFF231A08),
                            )
                        }
                    }
                }
            }

            // The sheet lives inside the plate so the tab bar stays put underneath it,
            // which is how the mockup's overlay frame is drawn.
            Box(Modifier.align(Alignment.BottomCenter)) {
                SheetHost(region = region, onDismiss = { selected = null }) { shown ->
                    BubbleSheet(
                        page = page,
                        regions = regions,
                        region = shown,
                        tokens = tokens,
                        savedWords = savedWords,
                        settings = settings,
                        analysis = analysis,
                        translation = translation,
                        grammar = grammar,
                        onSelect = { next -> selected = next },
                    )
                }
            }
        }

        MangaTabBar(selected = tab, onSelect = onTab)
    }
}

/** "<- Page 1" over the photograph, white with a shadow so it survives any artwork. */
@Composable
private fun ReaderBar(label: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .statusBarsPadding()
            .padding(start = ScreenMargin, end = ScreenMargin, top = 8.dp)
            .fillMaxWidth(),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
            BackArrow(onBack, tint = Color.White)
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge.copy(
                    color = Color.White,
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = Color(0x99000000),
                        offset = Offset(0f, 1f),
                        blurRadius = 6f,
                    ),
                ),
                modifier = Modifier.padding(start = 10.dp),
            )
            Box(Modifier.weight(1f))
            Wordmark()
        }
    }
}

@Composable
private fun ReaderActionBadge(
    drawable: Int,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Image(
        painter = painterResource(drawable),
        contentDescription = description,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier
            .size(width = 56.dp, height = 31.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    )
}

/** A flat paper panel over the photograph. Square, like everything else. */
@Composable
private fun ReaderPanel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MangaPalette.Paper)
            .padding(14.dp),
    ) { content() }
}

/**
 * Slides the sheet in and out.
 *
 * Its own function because `AnimatedVisibility` has scoped overloads: called inside the
 * plate's `Box` it resolves against the enclosing `ColumnScope` and will not compile.
 * Here there is no scope to collide with.
 */
@Composable
private fun SheetHost(
    region: RegionEntity?,
    onDismiss: () -> Unit,
    content: @Composable (RegionEntity) -> Unit,
) {
    // The last region stays around so the panel has something to draw on the way out.
    var lingering by remember { mutableStateOf<RegionEntity?>(null) }
    if (region != null) lingering = region

    AnimatedVisibility(
        visible = region != null,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        lingering?.let { shown ->
            SheetPanel(onDismiss = onDismiss) { content(shown) }
        }
    }
}

/**
 * The bubble sheet's frame: the grab handle, and the drag that handle promises.
 *
 * Dragging past a third of the sheet dismisses it; anything shorter springs back. The
 * gesture is on the handle rather than the whole panel because the panel scrolls, and a
 * sheet that sometimes scrolls and sometimes closes under the same downward drag is worse
 * than one that always scrolls.
 */
@Composable
private fun SheetPanel(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    var dragged by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val threshold = with(density) { 96.dp.toPx() }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.42f)
            .graphicsLayer { translationY = max(0f, dragged) }
            .background(MangaPalette.Paper),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 11.dp, bottom = 13.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> dragged += delta },
                    onDragStopped = {
                        if (dragged > threshold) onDismiss()
                        dragged = 0f
                    },
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Grip()
        }
        Box(Modifier.weight(1f)) { content() }
        // The one rule the reader has. It only exists while the sheet is up, which is
        // the only time two sheets of paper meet -- the panel and the tab bar under it.
        InkRule()
    }
}

/** The zoom/pan surface: the page image with the region overlay drawn on top of it. */
@Composable
private fun PageCanvas(
    page: PageEntity,
    regions: List<RegionEntity>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
) {
    val context = LocalContext.current
    val vault = remember(context) { Vault.get(context) }
    val bitmap by produceState<ImageBitmap?>(null, page.id, page.imagePath) {
        value = withContext(Dispatchers.IO) {
            val file = vault.display(page.imagePath)
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }
    }

    var scale by remember(page.id) { mutableFloatStateOf(1f) }
    var offset by remember(page.id) { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val viewport = Size(
            with(LocalDensity.current) { maxWidth.toPx() },
            with(LocalDensity.current) { maxHeight.toPx() },
        )
        val aspect = page.width.toFloat() / page.height.coerceAtLeast(1)

        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y,
                )
                .pointerInput(page.id, viewport) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val next = (scale * zoom).coerceIn(1f, 6f)
                        // Pan in screen pixels; the layer scales what it is given, so
                        // the raw delta is already in the right space.
                        val panned = offset + pan
                        val limitX = max(0f, (size.width * next - viewport.width) / 2f)
                        val limitY = max(0f, (size.height * next - viewport.height) / 2f)
                        scale = next
                        offset = Offset(
                            panned.x.coerceIn(-limitX, limitX),
                            panned.y.coerceIn(-limitY, limitY),
                        )
                    }
                }
                .pointerInput(page.id, regions) {
                    // Pointer positions arrive in this node's own coordinates, before
                    // the layer transform, so a tap maps to the page by size alone --
                    // no inverse of the zoom is needed here.
                    detectTapGestures { tap ->
                        onSelect(
                            hit(
                                regions,
                                tap.x / size.width.toFloat(),
                                tap.y / size.height.toFloat(),
                            ),
                        )
                    }
                },
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Page",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            RegionOverlay(page, regions, selected, scale)
        }
    }
}

/**
 * Section 3.2's "a low-opacity tint or corner dot. Non-intrusive."
 *
 * The mockup settles that: a 30% amber wash, a dashed frame at 2 dp, and a solid tab in
 * the top-left corner. The numbered badge that used to sit there is gone -- the mockup
 * has no room for it and the sheet header says "#3 of 7" anyway, which is the same fact
 * in the place the user is actually reading.
 *
 * Strokes and the tab are divided by the zoom so they stay the same size on screen at 6x
 * as at 1x; otherwise the overlay swamps the art when zoomed.
 */
@Composable
private fun RegionOverlay(
    page: PageEntity,
    regions: List<RegionEntity>,
    selected: Long?,
    scale: Float,
) {
    val density = LocalDensity.current

    Canvas(Modifier.fillMaxSize()) {
        regions.forEach { region ->
            val colour = when {
                isLowConfidence(region) -> MangaPalette.Alarm
                isSfx(page, region) -> MangaPalette.Violet
                else -> MangaPalette.Orange
            }
            val isSelected = region.regionId == selected
            val left = region.box.left * size.width
            val top = region.box.top * size.height
            val width = (region.box.right - region.box.left) * size.width
            val height = (region.box.bottom - region.box.top) * size.height

            drawRect(
                color = colour,
                topLeft = Offset(left, top),
                size = Size(width, height),
                alpha = if (isSelected) 0.52f else 0.30f,
            )
            val stroke = with(density) { 2.dp.toPx() } / scale
            drawRect(
                color = colour,
                topLeft = Offset(left + stroke / 2f, top + stroke / 2f),
                size = Size(width - stroke, height - stroke),
                style = Stroke(
                    width = stroke,
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(
                            with(density) { 7.dp.toPx() } / scale,
                            with(density) { 5.dp.toPx() } / scale,
                        ),
                        0f,
                    ),
                ),
            )
            drawRect(
                color = colour,
                topLeft = Offset(left, top),
                size = Size(
                    with(density) { 9.dp.toPx() } / scale,
                    with(density) { 7.dp.toPx() } / scale,
                ),
            )
        }
    }
}

/** The mock's stage list, as a panel: named stages, not a bare spinner. */
@Composable
private fun StagePanel(running: AnalysisController.Running) {
    ReaderPanel {
        val stage = running.stage
        Text(
            (if (stage.number > 0) stage.number.toString() + " · " else "") + stage.label +
                (if (running.detail.isEmpty()) "" else "  " + running.detail),
            style = MaterialTheme.typography.titleMedium,
        )
        ReaderProgress(running.fraction, MangaPalette.Orange)
        Text(
            // Stage 7 is in the enum so translation can borrow this panel's shape,
            // but it is not part of what analysis runs and does not belong in the
            // strip that says what is coming next.
            PageAnalyzer.Stage.entries
                .filter { it.number > 0 && it != PageAnalyzer.Stage.TRANSLATE }
                .joinToString("  →  ") { it.label },
            style = MaterialTheme.typography.bodySmall,
            color = MangaPalette.Dim,
        )
    }
}

/**
 * Stage 7's progress, in the same frame as [StagePanel].
 *
 * Separate because the two jobs are separate and can be told apart at a glance: analysis
 * counts through five stages, translation counts through bubbles. On tier 1 this panel is
 * on screen for the better part of a minute (`docs/on-device-llm.md` section 4), which is
 * why it says which bubble it is on rather than spinning.
 */
@Composable
private fun TranslatePanel(running: TranslationController.Running) {
    ReaderPanel {
        Text(
            PageAnalyzer.Stage.TRANSLATE.number.toString() + " · " +
                PageAnalyzer.Stage.TRANSLATE.label +
                (
                    if (running.total > 0) {
                        "  " + running.done + " of " + running.total + " bubbles"
                    } else {
                        ""
                    }
                    ),
            style = MaterialTheme.typography.titleMedium,
        )
        ReaderProgress(running.fraction, MangaPalette.Violet)
        Text(
            "The whole page goes in one call, so every bubble has the others as " +
                "context. Leaving this screen does not cancel it.",
            style = MaterialTheme.typography.bodySmall,
            color = MangaPalette.Dim,
        )
    }
}

/** A square progress bar in the depth's colour. */
@Composable
private fun ReaderProgress(fraction: Float, colour: Color) {
    val modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 9.dp)
        .height(6.dp)
    if (fraction >= 0f) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = modifier,
            color = colour,
            trackColor = MangaPalette.Silver,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(
            modifier = modifier,
            color = colour,
            trackColor = MangaPalette.Silver,
            strokeCap = androidx.compose.ui.graphics.StrokeCap.Butt,
            gapSize = 0.dp,
        )
    }
}

/**
 * Whether stage 6 ruled this region onomatopoeia.
 *
 * The page-kind clause is gone: `SfxClassifier` vetoes a cover before it scores
 * anything, so a cover simply never produces a `TEXT_SFX` region and there is nothing
 * left here to correct for.
 */
internal fun isSfx(page: PageEntity, region: RegionEntity): Boolean =
    region.kind == RegionKind.TEXT_SFX

internal fun isLowConfidence(region: RegionEntity): Boolean {
    val confidence = region.ocrConfidence ?: return false
    return confidence < MangaOcr.LOW_CONFIDENCE
}

internal fun kindLabel(page: PageEntity, region: RegionEntity): String = when {
    isSfx(page, region) -> "Sound effect"
    page.kind == PageKind.COVER -> "Cover text"
    else -> "Dialogue"
}

internal fun percent(value: Float) = (value * 100f).roundToInt().toString() + "%"

/** Unused by the reader now that the bar says "Page n", but the sheet still wants it. */
internal fun regionSummary(page: PageEntity, regions: List<RegionEntity>): String {
    if (regions.isEmpty()) return if (page.rectified) "rectified" else "whole frame"
    val sfx = regions.count { it.kind == RegionKind.TEXT_SFX }
    val dialogue = regions.size - sfx
    return if (page.kind == PageKind.COVER) {
        regions.size.toString() + " text"
    } else {
        dialogue.toString() + " dialogue · " + sfx + " SFX"
    }
}
