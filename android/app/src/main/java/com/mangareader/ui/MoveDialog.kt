package com.mangareader.ui

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Where a volume or a page can be moved to.
 *
 * One shape for both, because the picker does not care what it is filing: an id to write
 * into the row, a line to tap, and a second line when there is something worth saying
 * about the destination.
 */
data class MoveTarget(val id: String, val label: String, val detail: String? = null)

/**
 * The destination picker: pick a shelf, and the thing goes there.
 *
 * Deliberately one flat list rather than a series -> volume drill-down. Moving a page is
 * a two-level choice on paper, but the drill-down would put a screen between the user and
 * a list that is, in this app, a couple of dozen rows at the very most -- and the row can
 * say "Blue Period . Volume 3" in one line, which the drill-down cannot say at all.
 *
 * No confirm button. Tapping a destination *is* the confirmation, and unlike Delete this
 * is reversible: the thing that just moved can be moved straight back.
 */
@Composable
fun MoveDialog(
    title: String,
    empty: String,
    targets: List<MoveTarget>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MangaPalette.Paper,
        title = { Text(title) },
        text = {
            if (targets.isEmpty()) {
                Text(empty, style = MaterialTheme.typography.bodyMedium, color = MangaPalette.Dim)
                return@AlertDialog
            }
            LazyColumn(
                modifier = Modifier.heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(targets, key = { it.id }) { target ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MangaPalette.White)
                            .clickable { onPick(target.id) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                    ) {
                        Text(
                            text = target.label,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!target.detail.isNullOrBlank()) {
                            Text(
                                text = target.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MangaPalette.Dim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(shape = RectangleShape, onClick = onDismiss) { Text("Cancel") } },
    )
}
