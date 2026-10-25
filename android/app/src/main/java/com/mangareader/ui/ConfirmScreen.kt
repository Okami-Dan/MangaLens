package com.mangareader.ui

import androidx.compose.ui.graphics.RectangleShape
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Canvas
import com.mangareader.model.PointF
import com.mangareader.pipeline.PageStore
import kotlin.math.roundToInt

/**
 * Section 3.1's confirmation screen: the corrected page with **Retake** / **Use**.
 *
 * "Retake must be one tap -- bad captures are the dominant failure mode with print."
 * So Retake is a peer of Use, not a back gesture, and it is on the left where a thumb
 * reaches it.
 *
 * The four handles are section 10's escape hatch for stage 2: the quad search declines
 * on three of the four fixture photographs, and when it declines the handles start at
 * `Rectify.defaultQuad`'s inset rather than at nothing.
 */
@Composable
fun ConfirmScreen(
    jpeg: ByteArray,
    rotationDegrees: Int,
    initialQuad: List<PointF>?,
    quadWasFound: Boolean,
    busy: Boolean,
    onRetake: () -> Unit,
    onUse: (PageStore.Framing) -> Unit,
) {
    val bitmap = remember(jpeg, rotationDegrees) { decodePreview(jpeg, rotationDegrees) }
    var quad by remember(initialQuad) { mutableStateOf(initialQuad) }
    var useQuad by remember(initialQuad) { mutableStateOf(quadWasFound) }
    var quadEdited by remember(initialQuad) { mutableStateOf(false) }

    LaunchedEffect(initialQuad) { quad = initialQuad }

    Column(
        Modifier.fillMaxSize().background(Color.Black)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap == null) {
                Text("Could not decode capture", color = Color.White)
                return@BoxWithConstraints
            }

            // Fit the bitmap inside the box, then map normalized quad coordinates onto
            // the drawn rectangle -- not onto the box, or the handles sit off the page
            // on any aspect ratio but the exact one.
            val boxWidth = with(LocalDensity.current) { maxWidth.toPx() }
            val boxHeight = with(LocalDensity.current) { maxHeight.toPx() }
            val scale = minOf(boxWidth / bitmap.width, boxHeight / bitmap.height)
            val drawnWidth = bitmap.width * scale
            val drawnHeight = bitmap.height * scale
            val originX = (boxWidth - drawnWidth) / 2
            val originY = (boxHeight - drawnHeight) / 2

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Captured page",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            if (useQuad) {
                val corners = quad ?: emptyList()
                Box(
                    Modifier.fillMaxSize().pointerInput(drawnWidth, drawnHeight) {
                        // Scoped to this gesture handler rather than the file: two
                        // confirm screens must not share a drag target.
                        var dragging = -1
                        detectDragGestures(
                            onDragStart = { start ->
                                dragging = nearestCorner(start, quad.orEmpty(), originX,
                                    originY, drawnWidth, drawnHeight)
                            },
                            onDragEnd = { dragging = -1 },
                            onDragCancel = { dragging = -1 },
                        ) { change, _ ->
                            change.consume()
                            val index = dragging
                            if (index >= 0) {
                                val x = ((change.position.x - originX) / drawnWidth)
                                    .coerceIn(0f, 1f)
                                val y = ((change.position.y - originY) / drawnHeight)
                                    .coerceIn(0f, 1f)
                                // The *current* corners, not the list this composition
                                // captured. The key above is the drawn geometry alone:
                                // keying it on the corner count -- always four -- meant
                                // the handler was never restarted, so every drag rebuilt
                                // the quad from the corners the first composition saw and
                                // undid the drag before it. One handle appeared to move;
                                // the rest snapped back.
                                quad = quad.orEmpty().toMutableList()
                                    .also { it[index] = PointF(x, y) }
                                quadEdited = true
                            }
                        }
                    },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        if (corners.size != 4) return@Canvas
                        val points = corners.map {
                            Offset(originX + it.x * drawnWidth, originY + it.y * drawnHeight)
                        }
                        for (index in points.indices) {
                            drawLine(
                                color = Color(0xFFAEC6FF),
                                start = points[index],
                                end = points[(index + 1) % points.size],
                                strokeWidth = 3f,
                            )
                        }
                        points.forEach { point ->
                            val topLeft = Offset(point.x - 26f, point.y - 26f)
                            drawRect(Color(0xFFAEC6FF), topLeft = topLeft, size = Size(52f, 52f))
                            drawRect(
                                Color.Black, topLeft = topLeft, size = Size(52f, 52f),
                                style = Stroke(width = 3f),
                            )
                        }
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = when {
                    !useQuad -> "Using the whole frame. Tap Adjust to place corners."
                    quadWasFound -> "Page edges detected. Drag a corner to adjust."
                    else -> "No page edge found — drag the corners onto the page."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.75f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(shape = RectangleShape,
                    onClick = onRetake,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("Retake") }

                OutlinedButton(shape = RectangleShape,
                    onClick = { useQuad = !useQuad },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text(if (useQuad) "Whole frame" else "Adjust") }

                Button(shape = RectangleShape,
                    onClick = {
                        onUse(
                            when {
                                !useQuad -> PageStore.Framing.WholeFrame
                                quadWasFound && !quadEdited -> PageStore.Framing.Automatic
                                else -> PageStore.Framing.Manual(requireNotNull(quad))
                            },
                        )
                    },
                    enabled = !busy && (!useQuad || quad?.size == 4),
                    modifier = Modifier.weight(1f),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.fillMaxSize(0.5f),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Use")
                    }
                }
            }
        }
    }
}

private fun nearestCorner(
    position: Offset,
    corners: List<PointF>,
    originX: Float,
    originY: Float,
    width: Float,
    height: Float,
): Int {
    if (corners.size != 4) return -1
    var best = -1
    var bestDistance = Float.MAX_VALUE
    corners.forEachIndexed { index, corner ->
        val dx = originX + corner.x * width - position.x
        val dy = originY + corner.y * height - position.y
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            best = index
        }
    }
    // Only grab a handle if the touch actually landed near one, so a stray drag on the
    // page does not yank the closest corner across the screen.
    return if (bestDistance < 160f * 160f) best else -1
}

/**
 * A downscaled preview. The full capture is 12 MP; decoding it at full size here just
 * to draw it a few hundred pixels wide is a needless allocation on a 6 GB device
 * (docs/on-device-llm.md: memory is the binding constraint).
 */
private fun decodePreview(jpeg: ByteArray, rotationDegrees: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    val sample = maxOf(1, (maxOf(bounds.outWidth, bounds.outHeight) / 1600f).roundToInt())

    val bitmap = BitmapFactory.decodeByteArray(
        jpeg, 0, jpeg.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null

    if (rotationDegrees % 360 == 0) return bitmap
    val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
