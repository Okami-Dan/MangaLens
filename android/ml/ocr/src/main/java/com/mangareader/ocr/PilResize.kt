package com.mangareader.ocr

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pillow's `Image.resize(..., Image.BILINEAR)`, reimplemented.
 *
 * **This is not gratuitous.** `harness/pipeline/ocr.py` resizes every crop to 224x224
 * with PIL, and PIL antialiases on downscale -- it widens the filter support by the
 * scale factor -- while OpenCV's `INTER_LINEAR` samples four neighbours regardless.
 * Measured on the fixture set, swapping in `Imgproc.resize` changes the recognized text
 * on **6 of 39 regions** (`INTER_AREA`: 8 of 39). Some of those changes even read
 * better, but "different from the reference" is the problem: the harness is what the
 * detection thresholds and the CER baseline in `docs/onnx-export.md` were tuned
 * against, so stage 5 has to reproduce it, not improve on it.
 *
 * The algorithm is Pillow's `precompute_coeffs` + two separable passes
 * (`src/libImaging/Resample.c`). Pillow's 8-bit path accumulates in fixed point; this
 * accumulates in double and rounds once, which agrees to within a grey level -- see
 * `PilResizeParityTest`.
 */
object PilResize {

    /**
     * The two filters `harness/pipeline/ocr.py` uses: BILINEAR for the 224x224 model
     * input, and LANCZOS for `prepare_crop`'s upscale of crops under 32 px on their
     * short side. Both are Pillow's definitions verbatim.
     */
    enum class Filter(val support: Double) {
        BILINEAR(1.0) {
            override fun weight(x: Double): Double {
                val a = if (x < 0) -x else x
                return if (a < 1.0) 1.0 - a else 0.0
            }
        },
        LANCZOS(3.0) {
            override fun weight(x: Double): Double =
                if (x > -3.0 && x < 3.0) sinc(x) * sinc(x / 3.0) else 0.0
        },
        ;

        abstract fun weight(x: Double): Double

        protected fun sinc(x: Double): Double {
            if (x == 0.0) return 1.0
            val scaled = x * PI
            return sin(scaled) / scaled
        }
    }

    private class Coefficients(
        val bounds: IntArray,     // [min, count] per output position
        val kernel: DoubleArray,  // ksize weights per output position
        val ksize: Int,
    )

    private fun precompute(inSize: Int, outSize: Int, filter: Filter): Coefficients {
        val scale = inSize.toDouble() / outSize
        // Widening the filter is what produces the antialiasing on downscale; on
        // upscale filterscale stays 1 and this reduces to ordinary bilinear.
        val filterScale = if (scale < 1.0) 1.0 else scale
        val support = filter.support * filterScale
        val ksize = ceil(support).toInt() * 2 + 1

        val bounds = IntArray(outSize * 2)
        val kernel = DoubleArray(outSize * ksize)

        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            val ss = 1.0 / filterScale

            var xmin = (center - support + 0.5).toInt()
            if (xmin < 0) xmin = 0
            var xmax = (center + support + 0.5).toInt()
            if (xmax > inSize) xmax = inSize
            xmax -= xmin

            var weightSum = 0.0
            for (x in 0 until xmax) {
                val w = filter.weight((x + xmin - center + 0.5) * ss)
                kernel[xx * ksize + x] = w
                weightSum += w
            }
            if (weightSum != 0.0) {
                for (x in 0 until xmax) kernel[xx * ksize + x] /= weightSum
            }
            bounds[xx * 2] = xmin
            bounds[xx * 2 + 1] = xmax
        }
        return Coefficients(bounds, kernel, ksize)
    }

    private fun clip8(value: Double): Int = when {
        value <= 0.0 -> 0
        value >= 255.0 -> 255
        else -> value.roundToInt().coerceIn(0, 255)
    }

    /**
     * Resize interleaved 8-bit data with [channels] channels, row-major.
     *
     * Two separable passes, horizontal then vertical, exactly as Pillow does -- a
     * single combined pass would not give the same rounding.
     */
    fun resize(
        source: ByteArray,
        inWidth: Int,
        inHeight: Int,
        outWidth: Int,
        outHeight: Int,
        channels: Int = 3,
        filter: Filter = Filter.BILINEAR,
    ): ByteArray {
        require(source.size >= inWidth * inHeight * channels) { "source too small" }

        // ---- horizontal: (inWidth x inHeight) -> (outWidth x inHeight)
        //
        // The intermediate is 8-bit, not float. Pillow's 8bpc path writes the
        // horizontal pass into a UINT8 image, so values are rounded and clipped to
        // 0..255 *before* the vertical pass reads them. That matters for any filter
        // with negative lobes: LANCZOS overshoots on high-contrast edges, and keeping
        // the overshoot in double here diverges from Pillow by up to 33 grey levels.
        // BILINEAR is unaffected -- its weights are all non-negative -- which is
        // exactly why this was invisible until LANCZOS was added.
        val horizontal = ByteArray(outWidth * inHeight * channels)
        val hc = precompute(inWidth, outWidth, filter)
        for (y in 0 until inHeight) {
            for (xx in 0 until outWidth) {
                val xmin = hc.bounds[xx * 2]
                val count = hc.bounds[xx * 2 + 1]
                for (c in 0 until channels) {
                    var sum = 0.0
                    for (x in 0 until count) {
                        val pixel = source[((y * inWidth) + xmin + x) * channels + c]
                        sum += (pixel.toInt() and 0xFF) * hc.kernel[xx * hc.ksize + x]
                    }
                    horizontal[((y * outWidth) + xx) * channels + c] =
                        clip8(sum).toByte()
                }
            }
        }

        // ---- vertical: (outWidth x inHeight) -> (outWidth x outHeight)
        val out = ByteArray(outWidth * outHeight * channels)
        val vc = precompute(inHeight, outHeight, filter)
        for (yy in 0 until outHeight) {
            val ymin = vc.bounds[yy * 2]
            val count = vc.bounds[yy * 2 + 1]
            for (x in 0 until outWidth) {
                for (c in 0 until channels) {
                    var sum = 0.0
                    for (y in 0 until count) {
                        val pixel = horizontal[(((ymin + y) * outWidth) + x) * channels + c]
                        sum += (pixel.toInt() and 0xFF) * vc.kernel[yy * vc.ksize + y]
                    }
                    out[((yy * outWidth) + x) * channels + c] = clip8(sum).toByte()
                }
            }
        }
        return out
    }
}
