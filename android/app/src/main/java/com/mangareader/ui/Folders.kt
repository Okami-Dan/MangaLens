package com.mangareader.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangareader.pipeline.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The pieces the series grid and the volume grid share.
 *
 * Both are folders of the same shape -- a cover, a name, a count of what is inside, and a
 * long-press menu -- so they are one card here rather than two that drift apart. The page
 * grid in `LibraryScreen` uses the same card at [Depth.PAGE]; a page has no menu, and its
 * band is neutral so the photographs carry the grid.
 */

/**
 * The card, measured off the mockups: 176 wide, a 217 cover over a 52 band. Held as a
 * ratio and a height so it survives a phone that is not 402 dp across.
 */
private const val COVER_RATIO = 176f / 217f
private val BandHeight = 52.dp

/** Cards sit 10 apart in both directions, inside a 20 margin. */
private val CardGap = 10.dp

/**
 * Decodes an image path off the main thread. The same `produceState` +
 * `BitmapFactory.decodeFile` shape `LibraryScreen.PageCard` uses -- this project loads
 * bitmaps directly rather than through an image library.
 *
 * The path is resolved through the [Vault], because that is the form the database now
 * stores: `pages/ab12_thumb.jpg`, not an absolute path into an install that a reinstall
 * would have thrown away. This is the one place every cover and thumbnail in the app is
 * loaded, so it is the one place that has to know.
 */
@Composable
internal fun rememberImage(path: String?): ImageBitmap? {
    val context = LocalContext.current
    val vault = remember(context) { Vault.get(context) }
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            path?.let { vault.file(it) }
                ?.takeIf { it.exists() }
                ?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?.asImageBitmap()
        }
    }
    return bitmap
}

/**
 * A picked image, previewed before anything is copied to disk. Downsampled hard: this is
 * a thumbnail inside a dialog the user may still cancel, and a 12 MP pick decoded at full
 * size to fill 72 dp would be the most expensive thing on the screen.
 */
@Composable
private fun rememberPicked(uri: Uri?): ImageBitmap? {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            uri ?: return@withContext null
            runCatching {
                context.contentResolver.openInputStream(uri).use { stream ->
                    BitmapFactory.decodeStream(
                        stream,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = 8 },
                    )
                }
            }.getOrNull()?.asImageBitmap()
        }
    }
    return bitmap
}

/**
 * Two columns, ten apart, twenty from the edges -- and the scrollbar outside all of it.
 *
 * The margins are content padding rather than padding on the grid itself, so the thumb
 * the mockups want can ride the screen edge instead of being inset with the cards.
 */
@Composable
internal fun FolderGrid(
    depth: Depth,
    modifier: Modifier = Modifier,
    content: LazyGridScope.() -> Unit,
) {
    val state = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = state,
        modifier = modifier
            .fillMaxSize()
            .gridScrollbar(state, depth.thumb, columns = 2),
        contentPadding = PaddingValues(
            start = ScreenMargin,
            end = ScreenMargin,
            top = 15.dp,
            bottom = 10.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(CardGap),
        verticalArrangement = Arrangement.spacedBy(CardGap),
        content = content,
    )
}

/**
 * One entry in a card's long-press menu.
 *
 * A list rather than the fixed edit/delete pair the card used to take, because the three
 * grids do not want the same three things: a page has nothing to edit -- its number is
 * its position in the volume -- but it does want somewhere else to go.
 */
internal data class CardAction(
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * One folder: a cover, then a coloured band carrying the name and what is inside.
 *
 * [cover] is whatever the caller resolved -- the picked image, or the fallback standing
 * in for it. With neither, the card shows the work's Japanese title, which is the one
 * piece of it the app reliably knows before a cover has been chosen; failing that, the
 * initial. Either beats an empty rectangle in a grid of fresh series.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderCard(
    cover: String?,
    title: String,
    placeholder: String?,
    footer: String,
    depth: Depth,
    onOpen: () -> Unit,
    actions: List<CardAction> = emptyList(),
) {
    val image = rememberImage(cover)
    var menu by remember { mutableStateOf(false) }
    val hasMenu = actions.isNotEmpty()

    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = if (hasMenu) ({ menu = true }) else null,
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_RATIO)
                .background(MangaPalette.SilverTint),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    // The mockups crop covers from the top: a tankoubon puts its title
                    // there, and centring the crop cuts it off.
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = placeholder?.takeIf { it.isNotBlank() }
                        ?: title.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MangaPalette.Faint,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }

            if (hasMenu) {
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    actions.forEach { action ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    action.label,
                                    // The one place the alarm colour is earned: the item
                                    // that cannot be undone should not read like the
                                    // ones that can.
                                    color = if (action.destructive) {
                                        MangaPalette.Alarm
                                    } else {
                                        MangaPalette.Ink
                                    },
                                )
                            },
                            onClick = {
                                menu = false
                                action.onClick()
                            },
                        )
                    }
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .height(BandHeight)
                .background(depth.band)
                .padding(start = 11.dp, end = 11.dp, top = 8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = depth.onBand,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = footer,
                style = MaterialTheme.typography.bodySmall,
                color = depth.onBand.copy(alpha = if (depth == Depth.PAGE) 0.62f else 0.92f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/**
 * The cover row inside a creation dialog: a preview, and a button that opens the system
 * photo picker.
 *
 * `PickVisualMedia` needs no storage permission and falls back to `ACTION_OPEN_DOCUMENT`
 * below API 33, which is why it is used at this project's `minSdk = 26`.
 *
 * [existing] is the path already on the row. It is shown until the user picks something
 * else, so editing a folder never looks like the cover was lost.
 */
@Composable
internal fun CoverPicker(
    picked: Uri?,
    existing: String?,
    onPicked: (Uri) -> Unit,
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onPicked) }
    val preview = rememberPicked(picked)
    val fallback = rememberImage(existing.takeIf { picked == null })
    val shown = preview ?: fallback

    val pick = {
        launcher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .width(56.dp)
                .aspectRatio(COVER_RATIO)
                .background(MangaPalette.SilverTint)
                .clickable { pick() },
            contentAlignment = Alignment.Center,
        ) {
            if (shown != null) {
                Image(
                    bitmap = shown,
                    contentDescription = "Cover",
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Box(Modifier.weight(1f)) {
            DashedAction(
                label = if (shown == null) "+ pick a cover" else "change cover",
                depth = Depth.SERIES,
                onClick = { pick() },
            )
        }
    }
}
