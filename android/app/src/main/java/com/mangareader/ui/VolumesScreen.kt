package com.mangareader.ui

import androidx.compose.ui.graphics.RectangleShape
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mangareader.database.PageEntity
import com.mangareader.database.VolumeEntity

/** One series' shelf. Same card as the series grid; one level down. */
@Composable
fun VolumesScreen(
    volumes: List<VolumeEntity>,
    pages: List<PageEntity>,
    modifier: Modifier = Modifier,
    onOpen: (VolumeEntity) -> Unit,
    onEdit: (VolumeEntity) -> Unit,
    onMove: (VolumeEntity) -> Unit,
    onImportPdf: (VolumeEntity) -> Unit,
    onImportPng: (VolumeEntity) -> Unit,
    onRetryOcr: (VolumeEntity) -> Unit,
    onArchive: (VolumeEntity) -> Unit,
    onDelete: (VolumeEntity) -> Unit,
) {
    if (volumes.isEmpty()) {
        Placeholder(
            modifier,
            "No volumes yet",
            "Tap Add volume to put one on the shelf.",
        )
        return
    }

    FolderGrid(Depth.VOLUME, modifier) {
        items(volumes, key = { it.id }) { volume ->
            FolderCard(
                cover = volumeCover(volume, pages),
                title = volume.label,
                placeholder = "no cover yet",
                footer = countLabel(pages.count { it.volumeId == volume.id }, "page"),
                depth = Depth.VOLUME,
                onOpen = { onOpen(volume) },
                actions = buildList {
                    add(CardAction("Edit") { onEdit(volume) })
                    add(CardAction("Import PDF") { onImportPdf(volume) })
                    add(CardAction("Import PNG pages") { onImportPng(volume) })
                    if (pages.any {
                            it.volumeId == volume.id &&
                                it.stage.index < com.mangareader.model.PipelineStage.ANALYSED.index
                        }) add(CardAction("Retry OCR") { onRetryOcr(volume) })
                    add(CardAction("Move to series…") { onMove(volume) })
                    add(CardAction("Archive volume", destructive = true) { onArchive(volume) })
                    add(CardAction("Delete", destructive = true) { onDelete(volume) })
                },
            )
        }
    }
}

/**
 * New volume, and the same dialog reopened to edit one.
 *
 * [suggestedNumber] is the next number on the shelf, so the common case -- reading a
 * series in order -- is confirm-and-done. The field stays editable because a shelf is
 * filled in whatever order the volumes are bought.
 */
@Composable
fun VolumeDialog(
    existing: VolumeEntity?,
    suggestedNumber: Int,
    onDismiss: () -> Unit,
    onConfirm: (number: Int, cover: Uri?) -> Unit,
) {
    var number by remember {
        mutableStateOf((existing?.number ?: suggestedNumber).toString())
    }
    var cover by remember { mutableStateOf<Uri?>(null) }
    val parsed = number.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MangaPalette.Paper,
        title = { Text(if (existing == null) "Add volume" else "Edit volume") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = number,
                    // Filtered rather than validated after the fact: a volume number is
                    // digits, and there is nothing useful to say about "12a".
                    onValueChange = { entered ->
                        number = entered.filter(Char::isDigit).take(4)
                    },
                    label = { Text("Volume number") },
                    singleLine = true,
                    isError = parsed == null || parsed <= 0,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                )
                CoverPicker(picked = cover, existing = existing?.coverPath) { cover = it }
            }
        },
        confirmButton = {
            TextButton(shape = RectangleShape,
                enabled = parsed != null && parsed > 0,
                onClick = { onConfirm(parsed ?: 1, cover) },
            ) {
                Text(if (existing == null) "Add" else "Save")
            }
        },
        dismissButton = { TextButton(shape = RectangleShape, onClick = onDismiss) { Text("Cancel") } },
    )
}
