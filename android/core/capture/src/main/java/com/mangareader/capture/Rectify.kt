package com.mangareader.capture

import com.mangareader.model.PointF
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stage 2 -- rectify. A port of `harness/pipeline/rectify.py`.
 *
 * Plan section 4 calls for ML Kit's Document Scanner here, and says to "feed it the
 * captured still, not the preview stream". That is not something the API can do:
 * `GmsDocumentScanning` only exposes `getStartScanIntent()`, which launches Google's
 * own full-screen scanner flow, and there is no entry point that accepts a Bitmap.
 *
 * So the harness's own rectifier is the one that ships. Two reasons beyond necessity:
 * it is already tuned against the photographs in `test_input/` -- the two-pass quad
 * search, and the shadow flattening and paper white balance that the Python comments
 * say those dim indoor shots need -- and keeping it means the rectified image on the
 * phone matches the desktop's, so section 9's M2 check can diff on-device output
 * against `var/run_final/` and mean something. ML Kit's dewarp would break that.
 *
 * **Colour order.** The Python reads BGR (cv2.imread); this works in RGB, because
 * that is what a Bitmap gives. `Imgproc.COLOR_RGB2GRAY` applies the same luminance
 * weights to RGB that `COLOR_BGR2GRAY` applies to BGR, and the white balance is
 * per-channel and order-agnostic, so every constant below carries over unchanged.
 */
object Rectify {

    /**
     * A quad smaller than this fraction of the frame is more likely a panel border than
     * the page itself; below it we decline rather than cropping into the artwork.
     */
    private const val MIN_QUAD_AREA_FRAC = 0.25

    /**
     * A quad covering nearly the whole frame is not a detection -- warping to it is a
     * no-op. On handheld shots of a paperback the bright page, the hand holding it and
     * the facing page all threshold together, which produces exactly that, so it is
     * rejected rather than reported as a successful rectification.
     */
    private const val MAX_QUAD_AREA_FRAC = 0.92

    /** contour area / its min-area rect, i.e. "is this roughly a page". */
    private const val MIN_RECT_FILL = 0.70

    /** Contour search resolution; the warp itself runs at full size. */
    private const val WORKING_WIDTH = 1000.0

    /** Where flattening puts the page, leaving headroom for specular glare. */
    private const val PAPER_WHITE = 238.0

    @Volatile
    private var initialised = false

    /** Must be called once before anything else here. Cheap and idempotent. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (!initialised) initialised = OpenCVLoader.initLocal()
        return initialised
    }

    data class Result(
        /** Rectified RGB image. Caller owns it. */
        val image: Mat,
        /** Corners used, normalized against the *raw* image, or null if no warp ran. */
        val quad: List<PointF>?,
        val rectified: Boolean,
    )

    /**
     * Returns the rectified image, the quad used, and whether a warp was applied.
     *
     * A caller-supplied [quad] (from the confirm screen's corner drag) always wins over
     * auto-detect, which is what makes section 3.1's confirm/retake screen useful.
     * [detectQuad] is false only for an explicit Whole frame choice; shadow flattening
     * still runs on that full image.
     */
    fun rectify(
        image: Mat,
        quad: List<PointF>? = null,
        flatten: Boolean = true,
        detectQuad: Boolean = true,
    ): Result {
        ensureLoaded()
        val found: Array<Point>? = quad?.let { denormalise(it, image.width(), image.height()) }
            ?: if (detectQuad) findPageQuad(image) else null

        if (found == null) {
            // Section 4's fallback, honoured literally: pass the raw image through and
            // mark the page as not rectified rather than failing. A straight-on photo
            // of a flat page is often fine unprocessed.
            return Result(if (flatten) flattenShadows(image) else image, null, false)
        }
        val warped = warpToQuad(image, found)
        return Result(
            if (flatten) flattenShadows(warped) else warped,
            normalise(found, image.width(), image.height()),
            true,
        )
    }

    // ------------------------------------------------------------------ quad search

    /**
     * Best-effort page quad in original pixel coords, or null.
     *
     * Two passes. The first looks for a clean convex 4-gon in the edge map, which is
     * what a flat page on a contrasting surface gives. The second thresholds the page
     * off its background and takes the minimum-area rectangle of the largest bright
     * component, which survives a curved gutter, a thumb over the edge, and an edge
     * that fades out -- none of which produce four clean corners.
     *
     * Returning null is a normal outcome; the fallback above then applies.
     */
    fun findPageQuad(image: Mat): Array<Point>? {
        ensureLoaded()
        val scale = WORKING_WIDTH / image.width()
        val small = Mat()
        Imgproc.resize(
            image, small,
            Size(WORKING_WIDTH, (image.height() * scale).roundToInt().toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA,
        )
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        val frameArea = (small.rows() * small.cols()).toDouble()

        val blurred = Mat()
        Imgproc.bilateralFilter(gray, blurred, 9, 75.0, 75.0)
        val edges = Mat()
        Imgproc.Canny(blurred, edges, 50.0, 150.0)
        // Close gaps where the page edge fades into a dark background or a hand.
        Imgproc.dilate(edges, edges, Mat.ones(5, 5, CvType.CV_8U), Point(-1.0, -1.0), 2)
        Imgproc.erode(edges, edges, Mat.ones(3, 3, CvType.CV_8U), Point(-1.0, -1.0), 1)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )
        contours.sortByDescending { Imgproc.contourArea(it) }

        for (contour in contours.take(8)) {
            if (Imgproc.contourArea(contour) < frameArea * MIN_QUAD_AREA_FRAC) break
            val curve = MatOfPoint2f(*contour.toArray())
            val perimeter = Imgproc.arcLength(curve, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(curve, approx, 0.02 * perimeter, true)
            val points = approx.toArray()
            if (points.size == 4 &&
                Imgproc.isContourConvex(MatOfPoint(*points)) &&
                accept(points, frameArea)
            ) {
                return orderCorners(points.map { Point(it.x / scale, it.y / scale) }
                    .toTypedArray())
            }
        }

        val quad = thresholdQuad(gray, frameArea) ?: return null
        return orderCorners(quad.map { Point(it.x / scale, it.y / scale) }.toTypedArray())
    }

    /** Min-area rect of the largest bright component, if it looks page-shaped. */
    private fun thresholdQuad(gray: Mat, frameArea: Double): Array<Point>? {
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(9.0, 9.0), 0.0)
        val mask = Mat()
        Imgproc.threshold(
            blurred, mask, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU,
        )
        val kernel = Mat.ones(15, 15, CvType.CV_8U)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel)

        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids, 8)
        if (count < 2) return null

        var largest = 1
        var largestArea = -1.0
        for (label in 1 until count) {
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
            if (area > largestArea) {
                largestArea = area
                largest = label
            }
        }

        val component = Mat()
        Core.compare(labels, Scalar(largest.toDouble()), component, Core.CMP_EQ)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            component, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )
        if (contours.isEmpty()) return null
        val contour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return null

        val rect = Imgproc.minAreaRect(MatOfPoint2f(*contour.toArray()))
        val rectArea = rect.size.width * rect.size.height
        if (rectArea <= 0 || Imgproc.contourArea(contour) / rectArea < MIN_RECT_FILL) return null

        val box = Mat()
        Imgproc.boxPoints(rect, box)
        val points = Array(4) { Point(box.get(it, 0)[0], box.get(it, 1)[0]) }
        return if (accept(points, frameArea)) points else null
    }

    private fun accept(quad: Array<Point>, frameArea: Double): Boolean {
        val area = Imgproc.contourArea(MatOfPoint(*quad))
        return area in (frameArea * MIN_QUAD_AREA_FRAC)..(frameArea * MAX_QUAD_AREA_FRAC)
    }

    /** Return corners as top-left, top-right, bottom-right, bottom-left. */
    private fun orderCorners(points: Array<Point>): Array<Point> {
        // Python uses argmin/argmax of (x+y) and of (y-x); np.diff over [x, y] rows is
        // y - x, so the same expression is written out here rather than transcribed
        // from the numpy call.
        val sum = points.map { it.x + it.y }
        val diff = points.map { it.y - it.x }
        return arrayOf(
            points[sum.indexOf(sum.min())],
            points[diff.indexOf(diff.min())],
            points[sum.indexOf(sum.max())],
            points[diff.indexOf(diff.max())],
        )
    }

    /**
     * A normalized starting quad for the confirm screen's corner drag when auto-detect
     * declines. The screen still needs four handles to grab; seeding them just inside
     * the frame beats making the user place corners from nothing.
     */
    fun defaultQuad(inset: Float = 0.04f): List<PointF> {
        val lo = inset
        val hi = 1f - inset
        return listOf(PointF(lo, lo), PointF(hi, lo), PointF(hi, hi), PointF(lo, hi))
    }

    // ---------------------------------------------------------------------- warping

    /** Perspective-correct the given quad to a front-on rectangle. */
    fun warpToQuad(image: Mat, quad: Array<Point>): Mat {
        val (tl, tr, br, bl) = quad
        val width = max(hypot(tr.x - tl.x, tr.y - tl.y), hypot(br.x - bl.x, br.y - bl.y))
            .toInt().coerceAtLeast(32)
        val height = max(hypot(bl.x - tl.x, bl.y - tl.y), hypot(br.x - tr.x, br.y - tr.y))
            .toInt().coerceAtLeast(32)

        val source = MatOfPoint2f(tl, tr, br, bl)
        val destination = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((width - 1).toDouble(), 0.0),
            Point((width - 1).toDouble(), (height - 1).toDouble()),
            Point(0.0, (height - 1).toDouble()),
        )
        val matrix = Imgproc.getPerspectiveTransform(source, destination)
        val out = Mat()
        Imgproc.warpPerspective(
            image, out, matrix, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC,
        )
        return out
    }

    // ------------------------------------------------------------------- flattening

    /**
     * Divide out the low-frequency illumination gradient and set paper to white.
     *
     * The gain map is derived from luminance and shared across all three channels, so
     * page tone lifts without the artwork's blacks shifting colour. Dividing by the
     * blurred background corrects the shadow gradient; targeting PAPER_WHITE also
     * corrects the overall underexposure that indoor handheld shots always have, which
     * matters because these photos are dim enough that flattening alone leaves the page
     * grey.
     *
     * Manga pages are mostly white, which is exactly the case this trick suits.
     */
    fun flattenShadows(image: Mat, strength: Double = 1.0): Mat {
        ensureLoaded()
        if (strength <= 0) return image

        val gray = Mat()
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGB2GRAY)

        // Kernel scaled to the page so it spans shadow gradients but not speech bubbles.
        val k = max(31, (min(image.rows(), image.cols()) / 12) or 1)
        val background = Mat()
        Imgproc.GaussianBlur(gray, background, Size(k.toDouble(), k.toDouble()), 0.0)
        background.convertTo(background, CvType.CV_32F)
        Core.max(background, Scalar(1.0), background)

        val gain = Mat()
        Core.divide(PAPER_WHITE, background, gain)
        Core.min(gain, Scalar(4.0), gain)
        Core.max(gain, Scalar(0.5), gain)
        if (strength != 1.0) {
            // 1 + (gain - 1) * strength
            Core.subtract(gain, Scalar(1.0), gain)
            Core.multiply(gain, Scalar(strength), gain)
            Core.add(gain, Scalar(1.0), gain)
        }

        val channels = ArrayList<Mat>()
        Core.split(image, channels)
        for (channel in channels) {
            channel.convertTo(channel, CvType.CV_32F)
            Core.multiply(channel, gain, channel)
        }
        val scaled = Mat()
        Core.merge(channels, scaled)

        val balanced = whiteBalanceOnPaper(scaled, gray)
        val out = Mat()
        balanced.convertTo(out, CvType.CV_8U)   // convertTo saturates, i.e. clips to 0..255
        return out
    }

    /**
     * Neutralize the illuminant using the page itself as the white reference.
     *
     * Indoor light leaves a heavy warm cast on these photos. Paper is white by
     * definition, so the brightest fifth of the frame is a sound reference; balancing
     * against it removes the cast without discarding the chroma of a colour cover.
     */
    private fun whiteBalanceOnPaper(image: Mat, luminance: Mat): Mat {
        val threshold = percentile(luminance, 80.0)
        val paper = Mat()
        Core.compare(luminance, Scalar(threshold), paper, Core.CMP_GE)
        if (Core.countNonZero(paper) < 64) return image

        val channels = ArrayList<Mat>()
        Core.split(image, channels)
        val means = channels.map { Core.mean(it, paper).`val`[0] }
        if (means.any { it <= 1.0 }) return image

        val average = means.average()
        for (index in channels.indices) {
            Core.multiply(channels[index], Scalar(average / means[index]), channels[index])
        }
        val out = Mat()
        Core.merge(channels, out)
        return out
    }

    /**
     * Percentile of an 8-bit single-channel Mat, via its histogram.
     *
     * numpy's `percentile` interpolates linearly and can return a fractional value;
     * this returns the integer bin where the cumulative count crosses. On 8-bit data
     * the two differ by at most one grey level, which moves the paper mask by a
     * negligible number of pixels and shifts the resulting white balance below
     * visibility. Noted because it is the one place this file is not exact.
     */
    private fun percentile(gray: Mat, percent: Double): Double {
        val histogram = Mat()
        Imgproc.calcHist(
            listOf(gray), MatOfInt(0), Mat(), histogram,
            MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f),
        )
        val total = gray.total().toDouble()
        val target = total * percent / 100.0
        var cumulative = 0.0
        for (bin in 0 until 256) {
            cumulative += histogram.get(bin, 0)[0]
            if (cumulative >= target) return bin.toDouble()
        }
        return 255.0
    }

    // ------------------------------------------------------------------- geometry io

    private fun denormalise(quad: List<PointF>, width: Int, height: Int): Array<Point> =
        Array(4) { Point(quad[it].x.toDouble() * width, quad[it].y.toDouble() * height) }

    private fun normalise(quad: Array<Point>, width: Int, height: Int): List<PointF> =
        quad.map { PointF((it.x / width).toFloat(), (it.y / height).toFloat()) }

    private operator fun <T> Array<T>.component4(): T = this[3]
}
