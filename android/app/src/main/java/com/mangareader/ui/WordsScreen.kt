package com.mangareader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangareader.database.SavedWordEntity

/**
 * The Words tab: section 3.3's "tapping a token pins it / adds it to a saved list", seen
 * from the other end.
 *
 * No mockup covers this screen, so it is drawn in the same language as the ones that do:
 * white cards on paper, square, the depth accent used once per row. That once is the rule
 * down the left of the sentence -- it is the thing this screen exists for, since a word
 * without its context is the flashcard problem the app is trying to avoid, and the orange
 * ties it back to the bubble it came out of.
 */
@Composable
fun WordsScreen(
    words: List<SavedWordEntity>,
    modifier: Modifier = Modifier,
    onRemove: (SavedWordEntity) -> Unit,
    onOpen: (String) -> Unit,
) {
    if (words.isEmpty()) {
        Placeholder(
            modifier,
            "No saved words",
            "Open a page, tap a bubble, and tap any word in the breakdown to pin it " +
                "here with the sentence it came from.",
        )
        return
    }

    val state = rememberLazyListState()
    LazyColumn(
        state = state,
        modifier = modifier
            .fillMaxSize()
            .listScrollbar(state, Depth.SERIES.thumb),
        contentPadding = PaddingValues(
            start = ScreenMargin,
            end = ScreenMargin,
            top = 18.dp,
            bottom = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        items(words, key = { it.id }) { word ->
            WordRow(word, onRemove = { onRemove(word) }, onOpen = onOpen)
        }
    }
}

@Composable
private fun WordRow(word: SavedWordEntity, onRemove: () -> Unit, onOpen: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MangaPalette.White)
            .clickable(enabled = word.sourcePageId != null) {
                word.sourcePageId?.let(onOpen)
            }
            .padding(start = 13.dp, end = 6.dp, top = 12.dp, bottom = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    word.baseForm,
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp),
                )
                if (word.reading.isNotEmpty() && word.reading != word.baseForm) {
                    Text(
                        "  " + word.reading,
                        style = MaterialTheme.typography.bodySmall,
                        color = MangaPalette.Dim,
                    )
                }
            }
            Text(
                word.posLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MangaPalette.Faint,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                word.gloss.ifEmpty { "no dictionary entry" },
                style = MaterialTheme.typography.bodyMedium,
                color = if (word.gloss.isEmpty()) MangaPalette.Faint else MangaPalette.Ink,
                modifier = Modifier.padding(top = 5.dp),
            )
            if (word.context.isNotEmpty()) {
                // IntrinsicSize.Min is what lets the rule run the full height of the
                // sentence beside it rather than collapsing to nothing.
                Row(
                    Modifier
                        .padding(top = 7.dp)
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(MangaPalette.Orange),
                    )
                    Text(
                        word.context,
                        style = MaterialTheme.typography.bodySmall,
                        color = MangaPalette.Dim,
                        modifier = Modifier.padding(start = 9.dp),
                    )
                }
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.Bookmark,
                contentDescription = "Unpin " + word.baseForm,
                tint = MangaPalette.Violet,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
