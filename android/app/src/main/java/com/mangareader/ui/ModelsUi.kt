package com.mangareader.ui

import androidx.compose.ui.graphics.RectangleShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mangareader.pipeline.ModelStore

/**
 * The models' state, as a card. Section 4 stage 5 asks for "a progress screen with
 * checksum verification" on first launch; this is that, reused in two places -- the
 * Settings tab, where it is a status panel, and in front of the reader's analyse
 * action, where it is the thing standing between a captured page and its text.
 */
@Composable
fun ModelsCard(
    analysis: AnalysisController,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val progress = analysis.install
    val status = analysis.models

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                progress != null -> {
                    Text(progress.label, style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        megabytes(progress.bytesDone) + " of " + megabytes(progress.bytesTotal) +
                            "  ·  file " + (progress.index + 1) + " of " + progress.total,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                status is ModelStore.Status.Ready -> InstalledSection(analysis, compact)

                status is ModelStore.Status.Incomplete -> IncompleteSection(analysis, status)
            }

            analysis.modelError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun InstalledSection(analysis: AnalysisController, compact: Boolean) {
    if (compact) {
        Text("Models installed", style = MaterialTheme.typography.titleSmall)
        return
    }
    var staged by remember { mutableStateOf(analysis.stagedBytes) }
    Text("Models installed", style = MaterialTheme.typography.titleSmall)
    Text(
        "Detector and OCR, " + megabytes(ModelStore.ARTIFACTS.sumOf { it.bytes }) +
            " in app storage. Checksums verified on install.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (staged > 0) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                megabytes(staged) + " still staged in shared storage",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(shape = RectangleShape, onClick = { analysis.clearStaging(); staged = analysis.stagedBytes }) {
                Text("Free it")
            }
        }
    }
}

@Composable
private fun IncompleteSection(
    analysis: AnalysisController,
    status: ModelStore.Status.Incomplete,
) {
    // Multiple, because the OCR graphs come as a set: encoder, decoder and vocabulary go
    // in one pick rather than three trips through the picker. The filter is "*/*" because
    // an .onnx has no registered MIME type, and a filtered picker would show the user an
    // empty folder and no way to tell why.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> analysis.importModels(uris) }

    Text("Models not installed", style = MaterialTheme.typography.titleSmall)
    Text(
        "Detection and OCR run on device from ONNX graphs (" +
            megabytes(ModelStore.ARTIFACTS.sumOf { it.bytes }) + "). The OCR pair ships " +
            "with the app; missing files are prepared automatically on startup.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    status.pending.forEach { pending ->
        Text(
            BULLET + pending.artifact.label + DASH + megabytes(pending.artifact.bytes) + ", " +
                when (pending.source) {
                    ModelStore.Source.STAGED -> "ready to copy"
                    ModelStore.Source.BUNDLED -> "bundled, ready to unpack"
                    ModelStore.Source.REMOTE -> "download"
                    ModelStore.Source.NONE -> "pick the file"
                },
            style = MaterialTheme.typography.bodySmall,
        )
    }

    // Downloadable/stageable/bundled and pick-a-file are independent, not either/or:
    // gating this button on *every* pending artifact having a source used to leave a
    // fresh device with no button at all whenever even one artifact needed a manual
    // pick, only a picker with nothing local to point it at yet. `ModelStore.install`
    // already only acts on the non-NONE subset, so the button below is sized and
    // enabled to match what it actually does, independent of what else is pending.
    val fetchable = status.pending.filter { it.source != ModelStore.Source.NONE }
    if (fetchable.isNotEmpty()) {
        Button(shape = RectangleShape, onClick = { analysis.installModels() }, modifier = Modifier.fillMaxWidth()) {
            Text("Retry setup (" + modelMegabytes(fetchable.sumOf { it.artifact.bytes }) + ")")
        }
    }
    // Named per-artifact rather than the old fixed "encoder, decoder and vocab.txt"
    // text: the OCR pair is bundled now (see ModelStore's class note), so in a normal
    // build this list is empty and the block below never renders. It only appears if a
    // build genuinely shipped without a source for something, and should name whatever
    // that actually is rather than a hardcoded three files that may no longer apply.
    val pickable = status.pending.filter { it.source == ModelStore.Source.NONE }
    if (pickable.isNotEmpty()) {
        Text(
            "No upstream copy for " + pickable.joinToString(", ") { it.artifact.name } +
                ", so " + (if (pickable.size == 1) "it has" else "they have") +
                " to come from a file. The checksum is pinned either way, so a file " +
                "that is not the one expected is refused rather than installed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Start,
        )
    }

    Button(shape = RectangleShape,
        onClick = { picker.launch(arrayOf("*/*")) },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Import from file") }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Or stage them over adb: adb push var/models/manga-ocr-onnx-int8/. " +
                "/sdcard/Android/media/com.mangareader/models/manga-ocr/",
            style = MaterialTheme.typography.labelSmall,
            color = MangaPalette.Faint,
            modifier = Modifier.weight(1f),
        )
        TextButton(shape = RectangleShape, onClick = { analysis.refreshModels() }) { Text("Check again") }
    }
}

fun megabytes(bytes: Long): String = when {
    bytes >= 1L shl 20 -> String.format("%.0f MB", bytes / 1048576.0)
    else -> String.format("%.0f KB", bytes / 1024.0)
}

private const val BULLET = "· "
private const val DASH = " — "
