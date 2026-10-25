package com.mangareader.detection

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.mangareader.model.Box
import com.mangareader.model.PageKind
import com.mangareader.model.Region
import com.mangareader.model.RegionKind
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Stage 3 -- detection. A port of `harness/pipeline/detect.py`.
 *
 * Model: ogkalu/comic-text-and-bubble-detector (RT-DETR-v2 r50vd, 640px input), whose
 * config.json declares exactly the class ids the plan's section 4 table assumes:
 *
 *     0 bubble       container geometry, used for tap targets
 *     1 text_bubble  dialogue  -> OCR -> analyse -> translate
 *     2 text_free    SFX       -> OCR -> analyse -> SFX dictionary, never translated
 *
 * The published ONNX export bakes in RT-DETR's post-processing: it takes the original
 * frame size as a second input and returns labels/boxes/scores with boxes already in
 * source pixels (xyxy). `orig_target_sizes` is (width, height) in this export --
 * passing (height, width) yields boxes outside the frame.
 *
 * Preprocessing follows preprocessor_config.json: resize to 640x640 and rescale by
 * 1/255 with `do_normalize: false`. **Do not apply the ImageNet mean/std** that also
 * appear in that file; this export does not expect them. Note this differs from the OCR
 * model, which does normalize -- crossing the two silently degrades both.
 *
 * Every threshold below is carried over from the Python unchanged. They were measured
 * against the photographs in `test_input/`, and `docs/onnx-export.md` records that the
 * INT8 detector shifts boxes enough to cost real OCR accuracy, which is why the fp32
 * graph is the one that ships.
 */
class Detector(
    modelPath: File,
    private val useNnapi: Boolean = false,
    /**
     * Where [optimizedFile] writes the cached graph. Null -- the default, and what
     * every androidTest fixture still gets -- caches next to [modelPath] itself, in the
     * same `filesDir/models` `ModelStore` already owns. The app passes its
     * `context.cacheDir` instead: see [optimizedFile] for why that is the better home
     * for bytes that exist only to be regenerable.
     */
    private val cacheDir: File? = null,
) : Closeable {

    companion object {
        const val INPUT_SIZE = 640L
        const val CLASS_BUBBLE = 0
        const val CLASS_TEXT_BUBBLE = 1
        const val CLASS_TEXT_FREE = 2

        const val DEFAULT_THRESHOLD = 0.35f
        const val DEFAULT_BUBBLE_THRESHOLD = 0.45f

        /**
         * Free-floating SFX scores far lower than dialogue on photographs -- measured on
         * the fixture set, dialogue lands at 0.86-0.96 while genuine SFX lands at
         * 0.09-0.20, close to the noise floor. One global threshold therefore either
         * loses every SFX or floods the page with junk, so text_free gets its own, lower
         * bar and is then filtered by geometry (see [plausibleSfx]) instead of by score.
         *
         * 0.25 is where the fixture sweep settles: it costs no false positives on any of
         * the four photos while recovering one region that 0.35 drops. It is not enough
         * to catch everything -- the パカ / ピ lettering on photo_3 scores 0.196 and
         * 0.093 -- but the 0.15 needed for those turns photo_1 into 17 false positives,
         * so they stay missed and the limitation is reported rather than tuned away.
         */
        const val DEFAULT_SFX_THRESHOLD = 0.25f

        /** Below this fraction of the page, a text_free box is noise, not lettering. */
        const val MIN_SFX_AREA = 0.0004f

        /**
         * A text_free box hugging the frame border is the facing page or the page edge,
         * which every handheld shot of a paperback includes.
         */
        const val FRAME_MARGIN = 0.012f

        const val NMS_IOU = 0.6f

        // Measured across the fixture set. Interior pages: 9-11 bubbles, 9-11 dialogue
        // regions, 15-29% saturated pixels. The cover: 0 bubbles, 0 dialogue, 15 free-text
        // regions, 41% saturated. The geometric half of that gap is decisive; the colour
        // half separates a cover from a silent all-SFX interior page, which is otherwise
        // identical in shape. Neither is infallible, so the result is a *proposal* the
        // reader can flip -- see Page.kindAuto.
        const val COVER_COLOUR_FRACTION = 0.35f
        const val COVER_SATURATION = 60.0

        /**
         * ORT auto-detects a thread count from `hardware_concurrency()`, which counts
         * the little cores on a big.LITTLE SoC along with the big ones -- every barrier
         * inside a run then waits on whichever thread landed on an A55. Pinned instead,
         * to a number chosen to cover the big cluster on the target device class without
         * guessing at a specific core map; worth revisiting once `PageAnalyzer`'s
         * per-stage timings exist on a real device to sweep against.
         */
        const val DEFAULT_INTRA_OP_THREADS = 4

        /** Suffix for the optimized graph [optimizedFile] writes next to the source. */
        private const val OPTIMIZED_SUFFIX = ".ort_opt.onnx"
    }

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        // RT-DETR is one graph run once per page, not a pipeline of independent
        // branches -- inter-op parallelism has nothing to schedule onto a second pool,
        // so it is pinned to 1 rather than left for ORT to size on its own.
        session = openOptimized(modelPath) ?: run {
            val optimized = optimizedFile(modelPath)
            environment.createSession(modelPath.absolutePath, freshOptions(optimized))
        }
    }

    /**
     * The graph [modelPath] optimizes to.
     *
     * Graph optimization -- constant folding, node fusion -- is deterministic over the
     * model bytes, so re-running it on every page against the same 168 MB detector is
     * pure waste once page 1 has already paid for it. `PageAnalyzer` builds and closes a
     * fresh [Detector] per page (see its stage-6 note on why), so this is what keeps
     * that reconstruction cheap rather than keeping the session itself resident.
     *
     * Lives in [cacheDir] when one is given, not next to the source model: the file is
     * regenerable from [modelPath] at any time -- that is the whole of what
     * [openOptimized] does on a miss -- so it belongs where the OS can reclaim it under
     * storage pressure rather than permanently doubling `ModelStore`'s ~285 MB. ORT does
     * not promise the optimized graph is smaller than the source, so budget for the
     * worst case, not the best.
     *
     * Keyed on mtime, not existence alone: `ModelStore.install` rewrites `detector.onnx`
     * in place if the pinned checksum ever changes across an app update, and a cache
     * built from the old bytes has to lose that race rather than silently keep serving
     * a stale graph.
     */
    private fun optimizedFile(modelPath: File): File =
        File(cacheDir ?: modelPath.parentFile, modelPath.name + OPTIMIZED_SUFFIX)

    /** Load the cached optimized graph, or null if there is none valid to load. */
    private fun openOptimized(modelPath: File): OrtSession? {
        val optimized = optimizedFile(modelPath)
        if (!optimized.isFile || optimized.lastModified() < modelPath.lastModified()) return null
        // A half-written or otherwise unreadable cache falls back to the source model
        // rather than failing the whole detector -- see [freshOptions].
        return runCatching { environment.createSession(optimized.absolutePath, baseOptions()) }
            .getOrNull()
    }

    private fun baseOptions(): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        // ORT's own guidance: reload a saved-optimized graph at the same optimization
        // level it was saved at. Re-running ALL_OPT here costs nothing on an already-
        // optimized graph -- there is nothing left for it to fuse -- and is what makes
        // reloading safe rather than merely fast.
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        setIntraOpNumThreads(DEFAULT_INTRA_OP_THREADS)
        setInterOpNumThreads(1)
        if (useNnapi) {
            // Section 7 asks for the NNAPI EP with CPU fallback. It is off by
            // default: docs/on-device-llm.md establishes there is no usable NPU path
            // on this SoC (no sm7325 profile), so NNAPI has nothing to delegate to
            // and only adds a partitioning pass. Left switchable so it can be
            // measured rather than assumed either way.
            runCatching { addNnapi() }
        }
    }

    private fun freshOptions(optimized: File): OrtSession.SessionOptions =
        baseOptions().apply {
            // Best-effort: a write failure (missing directory, out of space, a
            // read-only volume) costs the next page the caching, never this one -- the
            // session below still comes from the source model either way.
            runCatching {
                optimized.parentFile?.mkdirs()
                setOptimizedModelFilePath(optimized.absolutePath)
            }
        }

    data class Detections(
        val regions: List<Region>,
        val kind: PageKind,
        val colourFraction: Float,
    )

    /**
     * @param image RGB, full resolution, as produced by stage 2
     */
    fun detect(
        image: Mat,
        threshold: Float = DEFAULT_THRESHOLD,
        bubbleThreshold: Float = DEFAULT_BUBBLE_THRESHOLD,
        sfxThreshold: Float = DEFAULT_SFX_THRESHOLD,
    ): Detections {
        val width = image.width()
        val height = image.height()
        val (labels, boxes, scores) = rawDetections(image)

        val limits = mapOf(
            CLASS_BUBBLE to bubbleThreshold,
            CLASS_TEXT_BUBBLE to threshold,
            CLASS_TEXT_FREE to sfxThreshold,
        )

        val textRegions = mutableListOf<Region>()
        val bubbles = mutableListOf<Box>()
        var nextId = 1L

        for (index in labels.indices) {
            val cls = labels[index].toInt()
            if (scores[index] < (limits[cls] ?: threshold)) continue

            val box = Box(
                left = boxes[index][0] / width,
                top = boxes[index][1] / height,
                right = boxes[index][2] / width,
                bottom = boxes[index][3] / height,
            )
            if (box.area <= 0f) continue

            if (cls == CLASS_BUBBLE) {
                bubbles.add(box)
                continue
            }
            val kind = when (cls) {
                CLASS_TEXT_BUBBLE -> RegionKind.TEXT_BUBBLE
                CLASS_TEXT_FREE -> RegionKind.TEXT_FREE
                else -> continue
            }
            if (kind == RegionKind.TEXT_FREE && !plausibleSfx(box)) continue

            textRegions.add(
                Region(id = nextId++, kind = kind, box = box, score = scores[index]),
            )
        }

        val kept = dropOverlaps(textRegions)
            .map { attachBubble(it, bubbles) }
            .map(::reclassifyContained)
            // Ids are assigned after NMS, so they run 1..n over the score-sorted list --
            // matching the order the harness serializes, which the fixtures depend on.
            .mapIndexed { index, region -> region.copy(id = (index + 1).toLong()) }

        val (kind, colour) = classifyPage(image, kept)
        return Detections(kept, kind, colour)
    }

    /** (labels, boxes in source pixels xyxy, scores). */
    private fun rawDetections(image: Mat): Triple<LongArray, Array<FloatArray>, FloatArray> {
        val resized = Mat()
        Imgproc.resize(
            image, resized, Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()),
            0.0, 0.0, Imgproc.INTER_LINEAR,
        )

        // HWC uint8 -> CHW float32 / 255. No mean/std: this export does not expect them.
        val pixels = ByteArray(resized.total().toInt() * resized.channels())
        resized.get(0, 0, pixels)
        val plane = (INPUT_SIZE * INPUT_SIZE).toInt()
        val chw = FloatArray(plane * 3)
        for (i in 0 until plane) {
            chw[i] = (pixels[i * 3].toInt() and 0xFF) / 255f
            chw[plane + i] = (pixels[i * 3 + 1].toInt() and 0xFF) / 255f
            chw[2 * plane + i] = (pixels[i * 3 + 2].toInt() and 0xFF) / 255f
        }

        val images = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(chw), longArrayOf(1, 3, INPUT_SIZE, INPUT_SIZE),
        )
        // (width, height), verified empirically -- the other order puts boxes outside
        // the frame.
        val sizes = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(longArrayOf(image.width().toLong(), image.height().toLong())),
            longArrayOf(1, 2),
        )

        images.use { imagesTensor ->
            sizes.use { sizesTensor ->
                session.run(
                    mapOf("images" to imagesTensor, "orig_target_sizes" to sizesTensor),
                ).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val labels = (result.get(0).value as Array<LongArray>)[0]

                    @Suppress("UNCHECKED_CAST")
                    val boxes = (result.get(1).value as Array<Array<FloatArray>>)[0]

                    @Suppress("UNCHECKED_CAST")
                    val scores = (result.get(2).value as Array<FloatArray>)[0]
                    return Triple(labels, boxes, scores)
                }
            }
        }
    }

    /**
     * Reject the two things the low text_free threshold otherwise lets through.
     *
     * Both are artifacts of photographing a physical book rather than scanning it:
     * slivers of the facing page along the frame border, and sub-glyph specks of page
     * texture.
     */
    private fun plausibleSfx(box: Box): Boolean {
        if (box.area < MIN_SFX_AREA) return false
        return box.left > FRAME_MARGIN && box.top > FRAME_MARGIN &&
            box.right < 1 - FRAME_MARGIN && box.bottom < 1 - FRAME_MARGIN
    }

    /**
     * Class-agnostic NMS over text regions.
     *
     * The model occasionally emits the same text as both text_bubble and text_free; that
     * ambiguity decides whether a region is translated or looked up as SFX, so it has to
     * be resolved here rather than left for the UI.
     */
    private fun dropOverlaps(regions: List<Region>): List<Region> {
        val kept = mutableListOf<Region>()
        for (region in regions.sortedByDescending { it.score }) {
            if (kept.all { iou(region.box, it.box) < NMS_IOU }) kept.add(region)
        }
        return kept
    }

    private fun iou(a: Box, b: Box): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val overlap = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.area + b.area - overlap
        return if (union > 0f) overlap / union else 0f
    }

    /**
     * Give each text region its containing bubble, smallest container wins.
     *
     * Section 4 keeps bubble geometry for tap targets and for M5's inpainting. It also
     * provides the heuristic cross-check the plan mentions: a text region with no
     * containing bubble is a strong SFX signal.
     */
    private fun attachBubble(region: Region, bubbles: List<Box>): Region {
        val container = bubbles
            .filter { it.contains(region.box, tolerance = 0.02f) }
            .minByOrNull { it.area }
        return if (container == null) region else region.copy(bubbleBox = container)
    }

    /**
     * Text sitting inside a detected bubble is dialogue, whatever the class head said.
     *
     * The model confuses text_bubble and text_free on photographs often enough to
     * matter, and the confusion decides whether a region gets translated or looked up as
     * drawn on the art. Section 4 already names the geometric rule for the two-class
     * fallback model -- "text region with no containing bubble = SFX" -- and it is better
     * evidence here than a class score, because the `bubble` class detects far more
     * reliably than `text_free` does. The converse is not applied: a missing bubble
     * detection is common, so dialogue without a container stays dialogue.
     */
    private fun reclassifyContained(region: Region): Region =
        if (region.kind == RegionKind.TEXT_FREE && region.bubbleBox != null) {
            region.copy(kind = RegionKind.TEXT_BUBBLE)
        } else {
            region
        }

    private fun classifyPage(image: Mat, regions: List<Region>): Pair<PageKind, Float> {
        val hsv = Mat()
        Imgproc.cvtColor(image, hsv, Imgproc.COLOR_RGB2HSV)
        val channels = ArrayList<Mat>()
        Core.split(hsv, channels)
        val saturated = Mat()
        Imgproc.threshold(channels[1], saturated, COVER_SATURATION, 255.0, Imgproc.THRESH_BINARY)
        val colourFraction = Core.countNonZero(saturated).toFloat() / saturated.total()

        val dialogue = regions.count { it.kind == RegionKind.TEXT_BUBBLE }
        val free = regions.count { it.kind == RegionKind.TEXT_FREE }
        val isCover = dialogue == 0 && free >= 1 && colourFraction >= COVER_COLOUR_FRACTION
        return (if (isCover) PageKind.COVER else PageKind.INTERIOR) to colourFraction
    }

    override fun close() {
        session.close()
    }
}
