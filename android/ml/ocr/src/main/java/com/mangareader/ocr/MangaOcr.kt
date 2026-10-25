package com.mangareader.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mangareader.model.Box
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Stage 5 -- OCR. A port of `harness/pipeline/ocr.py::OnnxMangaOcrEngine`.
 *
 * kha-white/manga-ocr-base as two ONNX graphs, INT8-quantized: a ViT encoder and a
 * two-layer BERT decoder run greedily with no KV cache. `docs/onnx-export.md` records
 * why there is no cache and what the quantization cost (nothing measurable, on the
 * fixture set).
 *
 * Section 4 is explicit that cropping happens per *region*, not per line: the model
 * reads a whole multi-line bubble in one forward pass.
 *
 * Two things here are easy to get wrong and both silently degrade output:
 *  - **Preprocessing differs from the detector's.** OCR normalizes with mean and std
 *    0.5; the detector rescales but does not normalize.
 *  - **The 224x224 resize must be Pillow's**, not OpenCV's -- see [PilResize].
 */
class MangaOcr(
    modelDir: File,
    private val maxLength: Int = 300,
    private val useNnapi: Boolean = false,
    /** See `Detector`'s constructor doc -- same parameter, same reasoning. */
    private val cacheDir: File? = null,
) : Closeable {

    companion object {
        /**
         * Section 4: crops below ~32 px in their short dimension get upscaled rather
         * than passed through -- print captures produce small crops, especially at
         * panel scale.
         */
        const val MIN_SHORT_SIDE = 32

        const val LOW_CONFIDENCE = 0.55f

        /** config.json: decoder_start_token_id 2 ([CLS]), eos_token_id 3 ([SEP]). */
        const val START_TOKEN = 2
        const val EOS_TOKEN = 3

        /** vocab.txt ids 0-4 are [PAD] [UNK] [CLS] [SEP] [MASK]. */
        const val FIRST_REAL_TOKEN = 5

        const val IMAGE_SIZE = 224

        /**
         * Section 10's "if a region's OCR output looks truncated or repetitive" case,
         * met in practice: on a low-scoring crop, greedy decode fell into emitting the
         * same kana until max_length -- 300 characters of one glyph, which took CER on
         * the fixture set from ~2% to 91%. Greedy decode has no way out of a repeat
         * loop once it enters one, so the loop needs a bound rather than a better graph.
         *
         * 6 is above anything the fixtures write legitimately; the correct reading of
         * the region that triggered it runs to three. On tripping, the trailing run is
         * trimmed back to [KEEP_REPEAT] and the region is forced below [LOW_CONFIDENCE]
         * so section 4's "mark the bubble as low-confidence rather than silently showing
         * garbage" applies.
         */
        const val MAX_REPEAT = 6
        const val KEEP_REPEAT = 3

        /** Section 4: pad each text region by ~4% before cropping. */
        const val CROP_PAD = 0.04f

        /** See `Detector.DEFAULT_INTRA_OP_THREADS` -- same reasoning, same number. */
        const val DEFAULT_INTRA_OP_THREADS = 4

        /** Suffix for the optimized graph [openSession] caches for each source file. */
        private const val OPTIMIZED_SUFFIX = ".ort_opt.onnx"
    }

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val encoder: OrtSession
    private val decoder: OrtSession
    private val vocabulary: List<String>

    init {
        encoder = openSession(File(modelDir, "encoder.onnx"))
        decoder = openSession(File(modelDir, "decoder.onnx"))
        vocabulary = File(modelDir, "vocab.txt").readLines()
    }

    /**
     * Open [modelPath], from its cached optimized graph when there is a valid one.
     *
     * Two graphs are built here every page under the same per-page reconstruction
     * `PageAnalyzer`'s stage-6 note explains for the detector: graph optimization is
     * deterministic over the model bytes, so paying it twice a page -- once for the ViT
     * encoder, once for the two-layer decoder -- is waste after page 1. See
     * `Detector.optimizedFile` for why this is keyed on mtime rather than existence,
     * and for why [cacheDir] -- when the caller gives one -- is where the result lives
     * rather than next to [modelPath].
     */
    private fun openSession(modelPath: File): OrtSession {
        val optimized = File(cacheDir ?: modelPath.parentFile, modelPath.name + OPTIMIZED_SUFFIX)
        val valid = optimized.isFile && optimized.lastModified() >= modelPath.lastModified()
        val cached = if (valid) runCatching { openPath(optimized) }.getOrNull() else null
        return cached ?: environment.createSession(modelPath.absolutePath, freshOptions(optimized))
    }

    private fun openPath(path: File): OrtSession =
        environment.createSession(path.absolutePath, baseOptions())

    private fun baseOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        // ORT's own guidance: reload a saved-optimized graph at the same level it was
        // saved at. Re-running ALL_OPT on an already-optimized graph costs nothing --
        // there is nothing left to fuse -- and is what makes reloading safe, not just
        // fast. Dynamic S (see the class note) does not change this: fusion and
        // constant folding operate on graph topology, not on a dynamic axis's runtime
        // value, which is why ORT's own tooling ships pre-optimized dynamic-shape
        // graphs the same way.
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(DEFAULT_INTRA_OP_THREADS)
        // Encoder and decoder are each one straight-line graph, not independent
        // branches -- nothing here benefits from a second, inter-op thread pool.
        setInterOpNumThreads(1)
        if (useNnapi) runCatching { addNnapi() }
    }

    private fun freshOptions(optimized: File): OrtSession.SessionOptions =
        baseOptions().apply {
            // Best-effort: a write failure (missing directory included) costs the next
            // page the caching, never this one -- the session still comes from the
            // source model either way.
            runCatching {
                optimized.parentFile?.mkdirs()
                setOptimizedModelFilePath(optimized.absolutePath)
            }
        }

    data class Reading(val text: String, val confidence: Float?)

    /** @param crop RGB region crop, as produced by [cropRegion] */
    fun read(crop: Mat): Reading {
        val prepared = prepareCrop(crop)
        val hidden = encode(prepared)

        val ids = mutableListOf(START_TOKEN)
        val chosen = mutableListOf<Float>()
        var repeats = 0
        var ranAway = false

        try {
            // A `for` loop with `break`, not `repeat { return@repeat }`: inside `repeat`
            // a labelled return only *continues*, so EOS and the runaway guard would
            // both fail to stop the loop and it would run the full max_length.
            for (step in 0 until maxLength - 1) {
                val logits = decodeStep(ids, hidden)

                // Softmax over the last position only; greedy, so no sampling state.
                val maximum = logits.max()
                var total = 0.0
                val probabilities = DoubleArray(logits.size) {
                    exp((logits[it] - maximum).toDouble()).also { v -> total += v }
                }
                var best = 0
                for (i in probabilities.indices) if (probabilities[i] > probabilities[best]) best = i

                // Same confidence proxy as the reference: mean probability of the tokens
                // actually chosen, EOS included.
                chosen.add((probabilities[best] / total).toFloat())
                if (best == EOS_TOKEN) break

                repeats = if (ids.last() == best) repeats + 1 else 0
                ids.add(best)
                if (repeats >= MAX_REPEAT) {
                    ranAway = true
                    // Trim the trailing run back to KEEP_REPEAT. Bounded by size so a
                    // pathological run can never empty the list.
                    val drop = minOf(repeats - KEEP_REPEAT + 1, ids.size - 1)
                    repeat(drop) { ids.removeAt(ids.size - 1) }
                    break
                }
            }
        } finally {
            hidden.close()
        }

        val text = OcrText.postProcess(
            ids.drop(1).filter { it >= FIRST_REAL_TOKEN }
                .joinToString("") { vocabulary.getOrElse(it) { "" } },
        )
        var confidence = if (chosen.isEmpty()) null else chosen.average().toFloat()
        if (ranAway && confidence != null) {
            // The model is confidently wrong in a repeat loop, so the mean chosen
            // probability is high and would not flag it on its own.
            confidence = min(confidence, LOW_CONFIDENCE - 0.01f)
        }
        return Reading(text, confidence)
    }

    // ------------------------------------------------------------------ inference

    private fun encode(image: Mat): OnnxTensor {
        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(pixelValues(image)),
            longArrayOf(1, 3, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong()),
        )
        tensor.use {
            encoder.run(mapOf("pixel_values" to it)).use { result ->
                // Copy out of the result before it is closed; the decoder is called many
                // times against the same encoder states.
                @Suppress("UNCHECKED_CAST")
                val states = result.get(0).value as Array<Array<FloatArray>>
                val flat = FloatArray(states[0].size * states[0][0].size)
                var at = 0
                for (row in states[0]) {
                    row.copyInto(flat, at)
                    at += row.size
                }
                return OnnxTensor.createTensor(
                    environment,
                    FloatBuffer.wrap(flat),
                    longArrayOf(1, states[0].size.toLong(), states[0][0].size.toLong()),
                )
            }
        }
    }

    private fun decodeStep(ids: List<Int>, hidden: OnnxTensor): FloatArray {
        val inputIds = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(LongArray(ids.size) { ids[it].toLong() }),
            longArrayOf(1, ids.size.toLong()),
        )
        inputIds.use {
            decoder.run(
                mapOf("input_ids" to it, "encoder_hidden_states" to hidden),
            ).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = result.get(0).value as Array<Array<FloatArray>>
                return logits[0].last()
            }
        }
    }

    // ---------------------------------------------------------------- preprocessing

    /** Grayscale, and upscale crops too small for the encoder to resolve strokes. */
    internal fun prepareCrop(image: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGB2GRAY)
        val rgb = Mat()
        Imgproc.cvtColor(gray, rgb, Imgproc.COLOR_GRAY2RGB)

        val short = min(rgb.width(), rgb.height())
        if (short >= MIN_SHORT_SIDE || short <= 0) return rgb

        val factor = MIN_SHORT_SIDE.toDouble() / short
        val width = max(1, (rgb.width() * factor).roundToInt())
        val height = max(1, (rgb.height() * factor).roundToInt())
        // The reference upscales with LANCZOS here, not bilinear.
        val bytes = ByteArray(rgb.total().toInt() * 3)
        rgb.get(0, 0, bytes)
        val scaled = PilResize.resize(
            bytes, rgb.width(), rgb.height(), width, height,
            channels = 3, filter = PilResize.Filter.LANCZOS,
        )
        val out = Mat(height, width, rgb.type())
        out.put(0, 0, scaled)
        return out
    }

    private fun pixelValues(image: Mat): FloatArray {
        val bytes = ByteArray(image.total().toInt() * 3)
        image.get(0, 0, bytes)
        val resized = PilResize.resize(
            bytes, image.width(), image.height(), IMAGE_SIZE, IMAGE_SIZE,
            channels = 3, filter = PilResize.Filter.BILINEAR,
        )

        // rescale by 1/255, then normalize with mean and std 0.5, then HWC -> CHW.
        val plane = IMAGE_SIZE * IMAGE_SIZE
        val out = FloatArray(plane * 3)
        for (i in 0 until plane) {
            for (c in 0 until 3) {
                val value = (resized[i * 3 + c].toInt() and 0xFF) / 255f
                out[c * plane + i] = (value - 0.5f) / 0.5f
            }
        }
        return out
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }
}

/** Crop a normalized box out of an RGB page, with section 4's 4% padding. */
fun cropRegion(image: Mat, box: Box, pad: Float = MangaOcr.CROP_PAD): Mat {
    val width = image.width()
    val height = image.height()
    val (l, t, r, b) = box.padded(pad).toPixels(width, height)
    val left = max(0, l)
    val top = max(0, t)
    val right = min(width, max(r, left + 1))
    val bottom = min(height, max(b, top + 1))
    return Mat(image, Rect(left, top, right - left, bottom - top))
}

private operator fun IntArray.component4(): Int = this[3]
