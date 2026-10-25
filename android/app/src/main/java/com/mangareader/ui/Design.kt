package com.mangareader.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mangareader.R
import kotlin.math.ceil
import kotlin.math.max

/**
 * The parts every screen in `Design mockups/` is built from.
 *
 * The mockups are drawn at a 402 pt frame width, which transfers to dp one for one, so
 * the numbers in here are the measured ones: 20 dp margins, 176 x 269 cards ten apart,
 * a 34 dp tab icon, an ink rule above the action button and none above the tab bar.
 */

/** Horizontal margin on every screen. */
val ScreenMargin = 20.dp

/**
 * How deep in the library a screen sits, and the colour that says so.
 *
 * This is the mockups' one piece of colour coding and it does real work: it is how the
 * user knows whether they are looking at series, volumes or pages without reading the
 * header. Every tinted surface on a screen takes its colour from here.
 */
enum class Depth(
    val band: Color,
    val onBand: Color,
    val actionFill: Color,
    val actionStroke: Color,
) {
    SERIES(MangaPalette.Orange, MangaPalette.White, MangaPalette.OrangeTint, MangaPalette.Orange),
    VOLUME(MangaPalette.Lilac, MangaPalette.White, MangaPalette.LilacTint, MangaPalette.Violet),
    PAGE(MangaPalette.Silver, MangaPalette.Ink, MangaPalette.Silver, MangaPalette.Dim),
    ;

    /** The scrollbar rides the same signal as the card bands. */
    val thumb: Color get() = actionStroke
}

/**
 * Part-of-speech colour for section 3.3 tier 4's structure strip.
 *
 * **Hue is the family; weight and shade are the member.** Orange is predicative -- verbs,
 * i-adjectives, auxiliaries. Violet is nominal -- nouns and the prenominals that attach to
 * them. Neutral is everything that glues the sentence together. Verb and i-adjective
 * sitting close together in orange is the encoding working rather than failing: they are
 * both things a sentence can end on, and a learner scanning the strip for "where does this
 * predicate" should find one colour, not two.
 *
 * Within a family the distinction is [posWeight], not a second hue. Two dark steps of one
 * ramp are not reliably tellable apart at 10sp and should not be asked to be.
 *
 * This does not touch [Depth]. Colour here lands on small label text only; the chunk chips
 * themselves are flat [MangaPalette.SilverTint], so nothing in tier 4 puts a new hue on a
 * fill and the series/volume/page signal is untouched.
 *
 * Keyed on the first level of the IPADIC tag, which is what `Chunk.pos` carries.
 */
fun posColour(pos: String): Color = when (pos.substringBefore(",")) {
    "動詞" -> MangaPalette.OrangeInk          // verb
    "形容詞" -> MangaPalette.OrangeInkSoft      // i-adjective
    "助動詞" -> MangaPalette.OrangeInkSoft      // auxiliary
    "名詞" -> MangaPalette.VioletInk          // noun
    "連体詞" -> MangaPalette.VioletInkSoft      // prenominal
    "副詞" -> MangaPalette.NeutralInk         // adverb
    else -> MangaPalette.NeutralInkSoft         // particles, conjunctions, interjections
}

/** The within-family distinction, since two dark steps of one ramp cannot carry it. */
fun posWeight(pos: String): FontWeight = when (pos.substringBefore(",")) {
    "動詞", "名詞", "副詞" -> FontWeight.SemiBold
    "形容詞", "連体詞", "接続詞" -> FontWeight.Medium
    else -> FontWeight.Normal
}

/**
 * The same encoding at stroke weight, for tier 4's phrase cards and token boxes.
 *
 * [posColour] is tuned for 10sp text on paper and every value is measured against it --
 * which makes those colours too dark and too close together to read as a *border*. A
 * stroke is a shape, not a glyph, so it can take the brand mid-tone the ink ramps were
 * derived from and say the same thing louder: orange is predicative, violet is nominal,
 * silver is everything that glues the sentence together.
 *
 * Three values, not seven. The within-family distinction ([posWeight]) lives on the role
 * label above the card, where there is text to carry it.
 *
 * This still does not touch [Depth]. A dashed stroke is not a fill, and the card's ground
 * stays paper -- so the series / volume / page signal keeps its exclusive claim on tinted
 * surfaces, exactly as `posColour` promises for the labels.
 */
fun posStroke(pos: String): Color = when (pos.substringBefore(",")) {
    "動詞", "形容詞", "助動詞" -> MangaPalette.Orange
    "名詞", "連体詞" -> MangaPalette.Violet
    else -> MangaPalette.Silver
}

/** The "ML" wordmark, top right of every screen. */
@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_wordmark),
        contentDescription = null,
        modifier = modifier.height(21.dp),
    )
}

/**
 * A screen's header: an optional back arrow, the title, an optional second line, and the
 * wordmark. Two lines is the pages screen -- series in ink, volume in the depth colour.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColour: Color = MangaPalette.Violet,
    onBack: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = ScreenMargin, end = ScreenMargin, top = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            if (onBack != null) {
                BackArrow(onBack, Modifier.padding(end = 10.dp, top = 2.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = subtitleColour,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                // Inside the title's own column, not a sibling of the row above: a back
                // arrow pushes the title right by its own width, and trailing() has to
                // follow it to the same edge rather than fall back to the header's raw
                // margin. Series and volumes screens carry a back arrow; the library
                // root does not, which is why this only ever showed up one level down.
                trailing()
            }
            Wordmark(Modifier.padding(start = 12.dp, top = 2.dp))
        }
    }
}

/**
 * The order a grid is in, and a menu to change it. Lives in [ScreenHeader]'s trailing
 * slot, left-aligned directly under the title (and the subtitle, on a folder that has
 * one) -- the same edge everything else in the header starts from.
 *
 * A line of text rather than a control with a box around it. The mockups put exactly two
 * kinds of chrome on a folder screen -- the header and the dashed action at the foot --
 * and a bordered dropdown between them would read as a third. It takes the depth colour
 * for the same reason everything tinted does: it belongs to the shelf being looked at.
 */
@Composable
fun SortMenu(
    current: SortOrder,
    options: List<SortOrder>,
    depth: Depth,
    modifier: Modifier = Modifier,
    onPick: (SortOrder) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    Row(
        modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Box {
            Text(
                text = current.label + "  ▾",
                style = MaterialTheme.typography.labelLarge,
                color = depth.actionStroke,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { open = true }
                    .padding(vertical = 2.dp),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.label,
                                // The current order is named in the label above, so the
                                // menu only has to say which row that is.
                                color = if (option == current) {
                                    depth.actionStroke
                                } else {
                                    MangaPalette.Ink
                                },
                            )
                        },
                        onClick = {
                            open = false
                            onPick(option)
                        },
                    )
                }
            }
        }
    }
}

/** The left-pointing mark in the mockups: a long shaft and a short open head. */
@Composable
fun BackArrow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MangaPalette.Ink,
) {
    Canvas(
        modifier
            .size(width = 26.dp, height = 20.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        val w = 2.4.dp.toPx()
        val midY = size.height / 2f
        val right = size.width - 1.dp.toPx()
        val left = 2.dp.toPx()
        drawLine(tint, Offset(right, midY), Offset(left, midY), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val head = 6.dp.toPx()
        drawLine(tint, Offset(left + head, midY - head), Offset(left, midY), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(tint, Offset(left + head, midY + head), Offset(left, midY), w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** The hairline the mockups put above the action button, and nowhere else. */
@Composable
fun InkRule(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.5.dp)
            .background(MangaPalette.Ink),
    )
}

/**
 * The dashed-outline action at the foot of every folder screen: new series, new volume,
 * new page. Tinted by [depth], so the button says which shelf it will add to.
 */
@Composable
fun DashedAction(
    label: String,
    depth: Depth,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(depth.actionFill)
            .dashedBorder(depth.actionStroke, 2.dp, 7.dp, 5.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = depth.actionStroke,
        )
    }
}

/** A square dashed rectangle around the element, drawn behind its content. */
fun Modifier.dashedBorder(
    colour: Color,
    width: Dp,
    on: Dp,
    off: Dp,
): Modifier = drawBehind {
    val stroke = width.toPx()
    drawRect(
        color = colour,
        topLeft = Offset(stroke / 2f, stroke / 2f),
        size = Size(size.width - stroke, size.height - stroke),
        style = Stroke(
            width = stroke,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(on.toPx(), off.toPx()), 0f),
        ),
    )
}

/**
 * The three-tab bar. No rule above it -- the mockups separate it by space alone, and the
 * icons are the hand-drawn ones from `Design mockups/`, so they are drawables rather than
 * anything Material ships.
 */
@Composable
fun MangaTabBar(
    selected: MangaTab,
    onSelect: (MangaTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MangaPalette.Paper)
            .navigationBarsPadding()
            .padding(top = 19.dp, bottom = 14.dp),
    ) {
        MangaTab.entries.forEach { tab ->
            val current = tab == selected
            Column(
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onSelect(tab) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = painterResource(tab.icon),
                    contentDescription = tab.label,
                    alpha = if (current) 1f else 0.42f,
                    modifier = Modifier.height(34.dp),
                )
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MangaPalette.Ink.copy(alpha = if (current) 1f else 0.42f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

enum class MangaTab(val label: String, val icon: Int) {
    LIBRARY("library", R.drawable.ic_tab_library),
    WORDS("words", R.drawable.ic_tab_words),
    OPTIONS("options", R.drawable.ic_tab_options),
}

/* --------------------------------------------------------------------------------
 * Scrollbars
 *
 * Compose ships none, and the mockups want a specific one: no track, no buttons, a
 * square 4 dp thumb in the depth's colour, hugging the right edge of the screen rather
 * than the padded content -- which is why the grids carry their margins as content
 * padding and the scrolling container itself runs edge to edge.
 * ------------------------------------------------------------------------------ */

private val ThumbWidth = 4.dp
private val ThumbMinLength = 28.dp

/** Draws [fraction] of the way down a thumb covering [portion] of the track. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawThumb(
    colour: Color,
    portion: Float,
    fraction: Float,
    minLength: Float,
    width: Float,
) {
    if (portion >= 1f || portion <= 0f) return
    val length = max(minLength, size.height * portion)
    val top = (size.height - length) * fraction.coerceIn(0f, 1f)
    drawRect(
        color = colour,
        topLeft = Offset(size.width - width, top),
        size = Size(width, length),
    )
}

/** Scrollbar for the folder grids. */
fun Modifier.gridScrollbar(state: LazyGridState, colour: Color, columns: Int): Modifier =
    drawWithContent {
        drawContent()
        val info = state.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
        val rows = ceil(info.totalItemsCount / columns.toFloat()).toInt()
        if (rows <= 0) return@drawWithContent
        val spacing = info.visibleItemsInfo
            .firstOrNull { it.index >= first.index + columns }
            ?.let { (it.offset.y - first.offset.y - first.size.height).toFloat() }
            ?: 0f
        val rowStride = first.size.height + max(0f, spacing)
        val content = rows * rowStride - max(0f, spacing) +
            info.beforeContentPadding + info.afterContentPadding
        if (content <= size.height) return@drawWithContent
        val scrolled = (state.firstVisibleItemIndex / columns) * rowStride +
            state.firstVisibleItemScrollOffset
        drawThumb(
            colour = colour,
            portion = size.height / content,
            fraction = scrolled / (content - size.height),
            minLength = ThumbMinLength.toPx(),
            width = ThumbWidth.toPx(),
        )
    }

/** Scrollbar for the word list. */
fun Modifier.listScrollbar(state: LazyListState, colour: Color): Modifier =
    drawWithContent {
        drawContent()
        val info = state.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
        if (info.totalItemsCount == 0) return@drawWithContent
        val stride = first.size.toFloat().takeIf { it > 0f } ?: return@drawWithContent
        val content = info.totalItemsCount * stride +
            info.beforeContentPadding + info.afterContentPadding
        if (content <= size.height) return@drawWithContent
        val scrolled = state.firstVisibleItemIndex * stride + state.firstVisibleItemScrollOffset
        drawThumb(
            colour = colour,
            portion = size.height / content,
            fraction = scrolled / (content - size.height),
            minLength = ThumbMinLength.toPx(),
            width = ThumbWidth.toPx(),
        )
    }

/** Scrollbar for anything using a plain [ScrollState] -- Options, and the bubble sheet. */
fun Modifier.scrollbar(state: ScrollState, colour: Color): Modifier =
    drawWithContent {
        drawContent()
        val max = state.maxValue
        if (max <= 0 || max == Int.MAX_VALUE) return@drawWithContent
        val content = size.height + max
        drawThumb(
            colour = colour,
            portion = size.height / content,
            fraction = state.value / max.toFloat(),
            minLength = ThumbMinLength.toPx(),
            width = ThumbWidth.toPx(),
        )
    }

/**
 * The bubble sheet's grab handle. A real affordance in the mockups, so it is one here:
 * the sheet it sits in is dismissible, and this is where the eye goes to do it.
 */
@Composable
fun Grip(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .width(44.dp)
                .height(4.dp)
                .background(MangaPalette.Dim),
        )
    }
}

/** A small ink-outlined icon button, for the actions that sit in a header. */
@Composable
fun InkIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MangaPalette.Ink,
) {
    Box(
        modifier
            .size(34.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else tint.copy(alpha = 0.32f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Vertical spacer, for the places a gap is content rather than padding. */
@Composable
fun Gap(height: Dp) {
    Spacer(Modifier.height(height))
}
