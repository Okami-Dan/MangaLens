package com.mangareader.capture

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Section 3.1's live framing guidance: "a warning when the frame is too skewed, too
 * dark, or blurry (Laplacian variance below threshold)".
 *
 * Section 10 puts this first among the risks -- "print capture quality is the dominant
 * risk, everything downstream inherits it" -- so this runs on the preview stream and
 * tells the user *before* the shutter, rather than leaving them to discover it after
 * OCR returns nonsense.
 *
 * Thresholds are starting points, not measured ones. The fixture set in `test_input/`
 * is four usable photographs; it contains no deliberately bad captures to calibrate
 * against, so these are conservative enough to avoid nagging on frames that turned out
 * fine. Tighten them once there are real rejects to tune on.
 */
object Quality {

    /** Laplacian variance below this reads as soft focus or motion blur. */
    const val BLUR_THRESHOLD = 100.0

    /** Mean luminance below this is too dark for the detector to be reliable. */
    const val DARK_THRESHOLD = 60.0

    /** Fraction of pixels at 255 above which glare is eating the page. */
    const val GLARE_FRACTION = 0.06

    enum class Warning { BLURRY, DARK, GLARE }

    data class Assessment(
        val focus: Double,
        val brightness: Double,
        val glare: Double,
        val warnings: Set<Warning>,
    ) {
        val isUsable: Boolean get() = warnings.isEmpty()
    }

    /**
     * Assess a preview frame. [gray] must be 8-bit single channel; callers on the
     * CameraX analyser path already have the Y plane, which is exactly that and costs
     * no conversion.
     */
    fun assess(gray: Mat): Assessment {
        Rectify.ensureLoaded()

        // Downscale first. Laplacian variance is scale-dependent, so the threshold is
        // only meaningful against a fixed working size -- and this runs per preview
        // frame, where full resolution would be wasted work.
        val small = Mat()
        Imgproc.resize(gray, small, Size(640.0, 640.0 * gray.rows() / gray.cols()))

        val laplacian = Mat()
        Imgproc.Laplacian(small, laplacian, CvType.CV_64F)
        val mean = MatOfDouble()
        val stdDev = MatOfDouble()
        Core.meanStdDev(laplacian, mean, stdDev)
        val focus = stdDev.get(0, 0)[0].let { it * it }

        val brightness = Core.mean(small).`val`[0]

        val blown = Mat()
        Imgproc.threshold(small, blown, 254.0, 255.0, Imgproc.THRESH_BINARY)
        val glare = Core.countNonZero(blown).toDouble() / small.total()

        val warnings = buildSet {
            if (focus < BLUR_THRESHOLD) add(Warning.BLURRY)
            if (brightness < DARK_THRESHOLD) add(Warning.DARK)
            if (glare > GLARE_FRACTION) add(Warning.GLARE)
        }
        return Assessment(focus, brightness, glare, warnings)
    }
}
