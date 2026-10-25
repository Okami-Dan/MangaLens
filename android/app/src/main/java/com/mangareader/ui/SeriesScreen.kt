package com.mangareader.ui

import androidx.compose.ui.graphics.RectangleShape
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.mangareader.database.PageEntity
import com.mangareader.database.SeriesEntity
import com.mangareader.database.VolumeEntity

/**
 * The main menu: one card per series, which is what the shelf actually looks like.
 *
 * Before M5 this place was a flat grid of every page ever captured, ordered by capture
 * time -- photograph two volumes in one sitting and they interleaved. A series holds
 * volumes and a volume holds pages, so this is the top of a two-level drill-down and
 * nothing more; no pipeline stage knows these rows exist.
 */
@Composable
fun SeriesScreen(
    series: List<SeriesEntity>,
    volumes: List<VolumeEntity>,
    pages: List<PageEntity>,
    modifier: Modifier = Modifier,
    onOpen: (SeriesEntity) -> Unit,
    onEdit: (SeriesEntity) -> Unit,
    onDelete: (SeriesEntity) -> Unit,
) {
    if (series.isEmpty()) {
        Placeholder(
            modifier,
            "No series yet",
            "Tap New series and name the work you are reading. It starts with one volume; " +
                "capture pages into that.",
        )
        return
    }

    FolderGrid(Depth.SERIES, modifier) {
        items(series, key = { it.id }) { entry ->
            val own = volumes.filter { it.seriesId == entry.id }.sortedBy { it.number }
            FolderCard(
                // A series with no cover of its own borrows the first cover on its shelf,
                // and that in turn falls back to a page. Better a real image from inside
                // the series than a placeholder over a library that has plenty of them.
                cover = entry.coverPath
                    ?: own.firstNotNullOfOrNull { volumeCover(it, pages) },
                title = entry.title,
                placeholder = entry.japaneseTitle,
                footer = countLabel(own.size, "volume"),
                depth = Depth.SERIES,
                onOpen = { onOpen(entry) },
                actions = listOf(
                    CardAction("Edit") { onEdit(entry) },
                    CardAction("Delete", destructive = true) { onDelete(entry) },
                ),
            )
        }
    }
}

/**
 * A volume's cover: its own, or the first page on it. The page grid's thumbnails already
 * exist at the right size, so the fallback costs nothing to produce.
 */
internal fun volumeCover(volume: VolumeEntity, pages: List<PageEntity>): String? =
    volume.coverPath ?: pages
        .filter { it.volumeId == volume.id }
        .minByOrNull { it.readingIndex }
        ?.let { it.thumbnailPath ?: it.imagePath }

internal fun countLabel(count: Int, noun: String): String = when (count) {
    0 -> "No ${noun}s"
    1 -> "1 $noun"
    else -> "$count ${noun}s"
}

/**
 * New series, and the same dialog reopened to edit one.
 *
 * Only the name is required; the Japanese title and cover are optional.
 */
@Composable
fun SeriesDialog(
    existing: SeriesEntity?,
    onDismiss: () -> Unit,
    onConfirm: (title: String, japaneseTitle: String, wikiUrl: String, cover: Uri?) -> Unit,
) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var japaneseTitle by remember { mutableStateOf(existing?.japaneseTitle.orEmpty()) }
    var cover by remember { mutableStateOf<Uri?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MangaPalette.Paper,
        title = { Text(if (existing == null) "New series" else "Edit series") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    value = japaneseTitle,
                    onValueChange = { japaneseTitle = it },
                    label = { Text("Japanese name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                CoverPicker(picked = cover, existing = existing?.coverPath) { cover = it }
            }
        },
        confirmButton = {
            TextButton(shape = RectangleShape,
                enabled = title.isNotBlank(),
                onClick = { onConfirm(title, japaneseTitle, existing?.wikiUrl.orEmpty(), cover) },
            ) {
                Text(if (existing == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(shape = RectangleShape, onClick = onDismiss) { Text("Cancel") } },
    )
}
