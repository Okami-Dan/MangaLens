package com.mangareader.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.view.MotionEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mangareader.capture.FrameAnalyzer
import com.mangareader.capture.Quality
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Section 3.1's capture screen.
 *
 * The live guidance is deliberately advisory, not blocking: it names what is wrong with
 * the frame but never refuses the shutter. Section 10 calls print capture quality the
 * dominant risk, and the mitigation the plan asks for is a fast retake loop plus a
 * warning -- not a gate that argues with someone who can see their own page.
 *
 * **Lifecycle.** Binding happens in a `LaunchedEffect` and unbinding in a matching
 * `DisposableEffect`, not inside `AndroidView`'s factory. The factory version left the
 * use cases bound to a destroyed PreviewView every time this screen was left for the
 * confirm step, so section 3.1's one-tap Retake came back to a black preview: the
 * camera was still streaming into a surface that no longer existed, and the rebind
 * raced the stale one. The screen is entered and left once per retake, which is exactly
 * the path that has to be reliable here.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraScreen(
    onCaptured: (ByteArray, Int) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val fixture = remember(context) {
        TutorialCameraSource.load(context)?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bitmap ->
                bytes to bitmap.asImageBitmap()
            }
        }
    }

    var granted by remember {
        mutableStateOf(
            fixture != null || context.checkSelfPermission(Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) request.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        PermissionPrompt(
            onGrant = { request.launch(Manifest.permission.CAMERA) },
            onCancel = onCancel,
        )
        return
    }

    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE (TextureView) rather than the default PERFORMANCE
            // (SurfaceView). SurfaceView owns a separate hardware layer that survives
            // detach badly across the capture -> confirm -> retake cycle, and it also
            // composites outside the view hierarchy, so a screenshot of this screen is
            // blank. TextureView costs a little more power and makes both problems go
            // away; on a page-at-a-time capture flow that is the right trade.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    var torchOn by remember { mutableStateOf(false) }
    var capturing by remember { mutableStateOf(false) }
    var assessment by remember { mutableStateOf<Quality.Assessment?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    // Held across the bind so dispose can detach the analyzer before the executor it
    // runs on is shut down.
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) {
        if (fixture != null) return@LaunchedEffect
        val resolved = context.cameraProvider()
        // Recorded before binding, not after: if this effect is cancelled partway --
        // the user backing out during the provider await -- dispose still has to be
        // able to unbind, or the camera stays claimed and the next visit is black.
        provider = resolved

        analysis.setAnalyzer(executor, FrameAnalyzer { assessment = it })
        val preview = Preview.Builder().build().apply {
            surfaceProvider = previewView.surfaceProvider
        }
        // Unbind whatever a previous visit left behind before claiming the camera.
        resolved.unbindAll()
        camera = resolved.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            imageCapture,
            analysis,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            analysis.clearAnalyzer()
            provider?.unbindAll()
            executor.shutdown()
        }
    }

    LaunchedEffect(torchOn, camera) { camera?.cameraControl?.enableTorch(torchOn) }

    DisposableEffect(previewView, camera, capturing) {
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP && !capturing) {
                camera?.let { boundCamera ->
                    // PreviewView's factory accounts for FIT_CENTER letterboxing and
                    // rotation; raw view fractions would focus the wrong camera point.
                    val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or
                            FocusMeteringAction.FLAG_AWB,
                    ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                    boundCamera.cameraControl.startFocusAndMetering(action)
                }
            }
            true
        }
        onDispose { previewView.setOnTouchListener(null) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (fixture != null) {
            Image(
                bitmap = fixture.second,
                contentDescription = "Camera preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { previewView },
            )
        }

        IconButton(
            onClick = onCancel,
            modifier = Modifier.align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars).padding(12.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White)
        }

        // Torch stays visible at all times -- section 3.1: glossy paper under overhead
        // light is the worst case, and side lighting sometimes helps and sometimes hurts,
        // so it is the user's call frame by frame.
        IconButton(
            onClick = { torchOn = !torchOn },
            modifier = Modifier.align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars).padding(12.dp),
        ) {
            Icon(
                if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                contentDescription = "Torch",
                tint = Color.White,
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            GuidanceChips(assessment)

            Surface(
                shape = RectangleShape,
                color = Color.White,
                modifier = Modifier.size(76.dp),
            ) {
                IconButton(
                    enabled = !capturing,
                    onClick = {
                        capturing = true
                        if (fixture != null) {
                            onCaptured(fixture.first.copyOf(), 0)
                        } else imageCapture.takePicture(
                            executor,
                            object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val buffer = image.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    val rotation = image.imageInfo.rotationDegrees
                                    image.close()
                                    capturing = false
                                    onCaptured(bytes, rotation)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    capturing = false
                                }
                            },
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {}
            }
        }
    }
}

/** `ProcessCameraProvider.getInstance` as a suspend call, without pulling in Guava. */
private suspend fun Context.cameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { continuation.resume(future.get()) },
            ContextCompat.getMainExecutor(this),
        )
    }

@Composable
private fun GuidanceChips(assessment: Quality.Assessment?) {
    val warnings = assessment?.warnings.orEmpty()
    if (warnings.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        warnings.forEach { warning ->
            AssistChip(
                onClick = {},
                shape = RectangleShape,
                label = {
                    Text(
                        when (warning) {
                            Quality.Warning.BLURRY -> "Hold still — blurry"
                            Quality.Warning.DARK -> "Too dark"
                            Quality.Warning.GLARE -> "Glare on the page"
                        },
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = Color(0xCC000000),
                    labelColor = Color.White,
                ),
            )
        }
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Camera access needed", style = MaterialTheme.typography.titleMedium)
        Text(
            "The primary input is a photo of a printed page. " +
                "Nothing leaves the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(shape = RectangleShape, onClick = onGrant) { Text("Grant access") }
        Button(shape = RectangleShape, onClick = onCancel) { Text("Back") }
    }
}
