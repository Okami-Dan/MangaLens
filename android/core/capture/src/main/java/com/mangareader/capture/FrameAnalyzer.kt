package com.mangareader.capture

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Runs [Quality] over the preview stream so section 3.1's framing guidance can warn
 * before the shutter rather than after OCR.
 *
 * Costs almost nothing: CameraX hands back YUV_420_888, whose first plane is the
 * luminance channel, and that is already the 8-bit single-channel image [Quality] wants
 * -- no colour conversion, no copy of the chroma planes.
 *
 * Frames arrive faster than they need assessing, so this samples rather than analysing
 * every one; CameraX's `STRATEGY_KEEP_ONLY_LATEST` drops the rest for us.
 */
class FrameAnalyzer(
    private val minIntervalMillis: Long = 350,
    private val onResult: (Quality.Assessment) -> Unit,
) : ImageAnalysis.Analyzer {

    private var lastRun = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastRun < minIntervalMillis) return
            lastRun = now
            if (!Rectify.ensureLoaded()) return

            onResult(Quality.assess(luminance(image)))
        } finally {
            // Must always close, or the pipeline stalls after a couple of frames.
            image.close()
        }
    }

    private fun luminance(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        // rowStride can exceed width when the plane is padded, so build the Mat at the
        // stride and then crop, rather than assuming a tight buffer.
        val stride = plane.rowStride
        val mat = Mat(image.height, stride, CvType.CV_8UC1)
        mat.put(0, 0, bytes)
        return if (stride == image.width) {
            mat
        } else {
            mat.submat(0, image.height, 0, image.width)
        }
    }
}
