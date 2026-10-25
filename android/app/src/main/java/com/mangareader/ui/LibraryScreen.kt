package com.mangareader.ui

import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mangareader.database.PageEntity
import com.mangareader.model.PipelineStage

/**
 * Section 3.5's library, one volume of it.
 *
 * Captured pages persist locally; each stores the corrected image plus (from M2 on) all
 * pipeline output, so reopening is instant. Until M5 this was every page in the app on
 * one grid; it is now the leaf of series -> volume -> page, and the caller passes the
 * pages of the volume the user opened.
 *
 * The card is the same one the shelves use, at [Depth.PAGE]: the band drops its colour so
 * the photographs carry the grid, and the caption counts bubbles rather than naming the
 * crop, because how many bubbles are on a page is the thing worth knowing before opening
 * it.
 */
@Composable
fun LibraryScreen(
    pages: List<PageEntity>,
    modifier: Modifier = Modifier,
    regionCounts: Map<String, Int> = emptyMap(),
    onOpen: (PageEntity) -> Unit,
    onMove: (PageEntity) -> Unit,
    onDelete: (PageEntity) -> Unit,
) {
    if (pages.isEmpty()) {
        Placeholder(
            modifier,
            "No pages in this volume",
            "Tap + new page and photograph one.",
        )
        return
    }

    FolderGrid(Depth.PAGE, modifier) {
        items(pages, key = { it.id }) { page ->
            FolderCard(
                cover = page.thumbnailPath ?: page.imagePath,
                title = "Page " + (page.readingIndex + 1),
                placeholder = null,
                footer = pageCaption(page, regionCounts[page.id]),
                depth = Depth.PAGE,
                onOpen = { onOpen(page) },
                // No Edit: a page has no field of its own to change. Its number is its
                // position in the volume, which is what Move is for.
                actions = listOf(
                    CardAction("Move to volume…") { onMove(page) },
                    CardAction("Delete", destructive = true) { onDelete(page) },
                ),
            )
        }
    }
}

/**
 * What the band says under a page.
 *
 * A page that has been through the pipeline reports its bubbles; one that has not says
 * so, because "0 bubbles" and "not looked at yet" mean very different things to someone
 * deciding whether the detector missed something.
 */
internal fun pageCaption(page: PageEntity, regions: Int?): String = when {
    page.stage.index < PipelineStage.ANALYSED.index -> "not analysed"
    regions == null || regions == 0 -> "no bubbles found"
    regions == 1 -> "1 bubble"
    else -> "$regions bubbles"
}
