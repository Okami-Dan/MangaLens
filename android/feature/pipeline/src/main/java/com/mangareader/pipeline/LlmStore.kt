package com.mangareader.pipeline

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Section 5 tier 1's weights: which models exist, whether this phone can hold one, and
 * how it gets here.
 *
 * Separate from [ModelStore] even though both download and verify, because the questions
 * are different. The ONNX graphs are not optional -- without them the app does nothing --
 * so `ModelStore` asks only whether they are present. A tier-1 model is entirely optional,
 * costs a gigabyte, and may not fit at all, so this has to answer "should you?" before
 * "have you?". Hence [Model.minRamBytes] and [freeBytes].
 *
 * `docs/on-device-llm.md` section 6 lists what the UI must say before the user commits,
 * and every one of those requirements needs a number from here.
 */
class LlmStore(private val context: Context) {

    /**
     * One entry in the registry.
     *
     * The `mmproj` fields are the multimodal seam. A vision-language model is two files --
     * the language weights and a vision tower -- and carrying the second from the start
     * means adding one later is a row in [MODELS] rather than a change to the download,
     * the storage layout and the UI. `docs/on-device-llm.md` section 8 has the measurement
     * that decides whether a VL entry ever earns its place; nothing ships one yet.
     */
    data class Model(
        val id: String,
        val label: String,
        val detail: String,
        val url: String,
        val bytes: Long,
        val sha256: String,
        val contextLength: Int,
        /** Minimum device RAM before this is offered without a warning. */
        val minRamBytes: Long,
        /** Which row of the picker this is -- see [Preset]. */
        val preset: Preset = Preset.BALANCED,
        /** Which prompt protocol this speaks -- see [ModelFamily]. */
        val family: ModelFamily = ModelFamily.QWEN3,
        val mmprojUrl: String? = null,
        val mmprojBytes: Long = 0,
        val mmprojSha256: String? = null,
    ) {
        val fileName: String get() = "$id.gguf"
        val multimodal: Boolean get() = mmprojUrl != null
        val totalBytes: Long get() = bytes + mmprojBytes
    }

    /**
     * Which prompt protocol a [Model] speaks, so `TranslationController` and
     * `GrammarController` know which `Translator` / `GrammarExplainer` to build for it.
     *
     * [QWEN3] is Qwen3's ChatML wrapped around section 7's structured per-page JSON
     * request, plus the `<think></think>` prefill that selects its direct (non-reasoning)
     * mode -- what every entry spoke before [LFM2_MT] existed.
     *
     * [LFM2_MT] is LiquidAI's dedicated EN<->JA translation protocol: a fixed two-line
     * system prompt, one sentence per call, no JSON and no page context. A model in this
     * family cannot write tier 4's grammar notes at all -- that is arbitrary instruction
     * following, which is exactly what this family of model does not do -- so
     * `GrammarController` falls back to [com.mangareader.translate.NoOpGrammarExplainer]
     * for it rather than pretending the button would work.
     *
     * [GENERIC] is [resolve]'s family: a model that is not known in advance, so nothing
     * here can promise it is Qwen3-shaped. Section 7's per-page JSON protocol is still
     * used -- there is no reason a generic instruction-following chat model would be
     * worse at that than Qwen3 is -- but the literal prompt string is not hand-written.
     * `LlamaBridge.applyTemplate` asks llama.cpp itself to detect and apply the GGUF's
     * own embedded chat template (60+ families recognized natively: Llama 3.x, Gemma,
     * Mistral, Phi, DeepSeek, and more -- see `LocalLlmTranslator`'s doc). Two things this
     * buys nothing for: a hybrid-reasoning model has no `enable_thinking`-equivalent
     * channel here and may exhaust its budget thinking, and a template llama.cpp does not
     * recognize fails with a clear message rather than guessing Qwen3's.
     */
    enum class ModelFamily { QWEN3, LFM2_MT, GENERIC }

    /**
     * ToDo's "preset low / high performance" as three rows rather than two, because the
     * middle one already existed: [BALANCED] is the model this app shipped with before a
     * choice existed at all, and stays the default so nobody's install changes underneath
     * them. [LIGHT] and [HIGH] are the new choices either side of it.
     */
    enum class Preset(val label: String) {
        LIGHT("Light"),
        BALANCED("Small"),
        HIGH("High performance"),

        /** [resolve]'s output. Not a row in [MODELS] -- the user's own pasted link. */
        CUSTOM("Custom"),
    }

    data class Progress(val bytesDone: Long, val bytesTotal: Long) {
        val fraction: Float get() = if (bytesTotal <= 0) 0f else bytesDone.toFloat() / bytesTotal
    }

    companion object {
        private const val TAG = "LlmStore"

        private const val GB = 1_000_000_000L

        /** [resolve]'s id prefix -- what [installed] uses to recognise a custom model. */
        private const val CUSTOM_PREFIX = "custom-"

        /**
         * The registry.
         *
         * Four entries now, three of them Qwen3's ChatML family and one -- [Preset.LIGHT]
         * -- a genuinely different kind of model. The original comment here argued for
         * exactly one entry, "a menu of models the phone cannot run is worse than no
         * menu", and that argument is still half right: it is why every row still carries
         * its own [Model.minRamBytes] warning and why [Preset.BALANCED] stays the default
         * rather than [Preset.HIGH]. What changed is that "the phone" is no longer one
         * device -- ToDo's "preset low / high performance (based on flagship smartphone
         * specs)" asks for a menu that spans a 6 GB midranger and a 12+ GB flagship, and
         * one entry cannot serve both without either being too heavy for the first or
         * leaving the second's extra RAM unused.
         *
         * The Balanced and High rows are `ggml-org`'s builds rather than Qwen's own: Qwen
         * publishes only Q8_0 at 1.7B (~1.8 GB, over budget), and `ggml-org` is the
         * llama.cpp project's own account, so its GGUFs are the ones most likely to keep
         * loading as the pinned runtime moves. Both share Qwen3's ChatML and the
         * `<think></think>` non-reasoning prefill [LocalLlmTranslator] writes -- see
         * [ModelFamily.QWEN3].
         *
         * The Light row is [ModelFamily.LFM2_MT] -- LiquidAI's LFM2-350M-ENJP-MT, added
         * by request alongside the others rather than in place of them, after a bake-off
         * in `tools/bench_translate.py` found it translated every one of the fixture
         * pages' 39 bubbles at least as fluently as models six times its size. It is not
         * a smaller Qwen3: it is a dedicated EN<->JA translation model with a fixed
         * two-line system prompt and no notion of a page, so it runs through
         * [com.mangareader.translate.LocalLiquidTranslator] instead of
         * [LocalLlmTranslator] -- see that class's doc for what that costs (no cross-bubble
         * context) and `docs/on-device-llm.md` section 2 for the measured numbers and the
         * licence caveat (LFM Open License v1.0, not Apache-2.0).
         *
         * [sha256] is each file's real SHA-256, which is also what Hugging Face returns as
         * `x-linked-etag` on a HEAD of the download URL -- verified by downloading once and
         * hashing. So pinning a future entry does not require a gigabyte of bandwidth:
         *
         *     curl -sIL <url> | grep -i x-linked-etag
         */
        val MODELS = listOf(
            Model(
                id = "lfm2-350m-enjp-mt-q4_k_m",
                label = "LFM2 350M (EN/JA)",
                detail = "Light-weight and fast English to Japanese model. Does not support " +
                    "detailed grammar explanations, series-specific context and whole-page " +
                    "translation. Despite loss of context, this model is preferred for smaller, " +
                    "less powerful devices.",
                url = "https://huggingface.co/LiquidAI/LFM2-350M-ENJP-MT-GGUF/resolve/" +
                    "main/LFM2-350M-ENJP-MT-Q4_K_M.gguf",
                bytes = 229_310_240L,
                sha256 = "574ef7980dd20d69b494bce82565db5f25124d404be02052bbca62277d33077b",
                // The model's own window is 128K; LlamaBridge.CONTEXT (2048) is what
                // actually runs, same as every other entry -- a single translated line
                // needs a fraction of either.
                contextLength = 128_000,
                // No measurement on this device -- the desktop bake-off used a fifth of
                // Qwen3-1.7B's file size, so this is scaled down from that model's 8 GB
                // minimum by roughly the same ratio, not measured RSS.
                minRamBytes = 3 * GB,
                preset = Preset.LIGHT,
                family = ModelFamily.LFM2_MT,
            ),
            Model(
                id = "qwen3-1.7b-q4_k_m",
                label = "Qwen3 1.7B",
                detail = "INT-4 weights, one-time download and no network needed to run. " +
                    "Longer translation time, but enables full app capabilities.",
                url = "https://huggingface.co/ggml-org/Qwen3-1.7B-GGUF/resolve/main/" +
                    "Qwen3-1.7B-Q4_K_M.gguf",
                bytes = 1_282_439_264L,
                sha256 = "d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5",
                contextLength = 32_768,
                // Measured, not budgeted: peak PSS on a real page is 2.54 GB, not the
                // 1.6 GB section 1 planned for (docs/on-device-llm.md section 8). It
                // survived on an 8 GB device with 1.7 GB free; a 6 GB one would very
                // likely be killed mid-page, which is a worse outcome than declining to
                // offer the tier. Lower this only with a measurement from a 6 GB unit.
                minRamBytes = 8 * GB,
                preset = Preset.BALANCED,
            ),
            Model(
                id = "qwen3-4b-q4_k_m",
                label = "Qwen3 4B",
                detail = "INT-4 weights, one-time download and no network needed to run. " +
                    "Capable of cohesive translation at the higher computational cost.",
                url = "https://huggingface.co/ggml-org/Qwen3-4B-GGUF/resolve/main/" +
                    "Qwen3-4B-Q4_K_M.gguf",
                bytes = 2_497_280_640L,
                sha256 = "ab27b9bfa375a178d6cba48f3ad892b94b7739659dcc7aae8058ce0ffed6b328",
                contextLength = 32_768,
                // No measurement on this device -- scaled up from the 1.7B figure by
                // parameter count. Gated behind a RAM warning like the others rather than
                // hidden, since a flagship owner is exactly who this row is for.
                minRamBytes = 12 * GB,
                preset = Preset.HIGH,
            ),
        )
    }

    /** Installed weights. App-private, so uninstalling the app reclaims the gigabyte. */
    val installDir: File get() = File(context.filesDir, "llm")

    fun fileFor(model: Model): File = File(installDir, model.fileName)

    fun isInstalled(model: Model): Boolean = fileFor(model).let {
        it.isFile && (model.bytes == 0L || it.length() == model.bytes)
    }

    /** The model the user picked, if it is on disk. */
    fun installed(id: String): Model? =
        MODELS.firstOrNull { it.id == id && isInstalled(it) } ?: installedCustom(id)

    /**
     * A [resolve]d model is not in [MODELS], so it cannot be found there by id -- its
     * file's own presence is what "installed" means for one of these instead. The bytes,
     * label and URL that [ReaderSettings][com.mangareader.ui.ReaderSettings] remembers for
     * the settings card are not reconstructed here and are not needed: everything a
     * translator actually runs on is the id, [ModelFamily.GENERIC] (llama.cpp detects the
     * chat template from the file itself at load time, not from anything recorded here),
     * and the file [fileFor] derives from the id alone.
     */
    private fun installedCustom(id: String): Model? {
        if (!id.startsWith(CUSTOM_PREFIX)) return null
        val file = File(installDir, "$id.gguf")
        if (!file.isFile || file.length() <= 0L) return null
        return Model(
            id = id,
            label = "Custom model",
            detail = "Imported from a pasted link.",
            url = "",
            bytes = file.length(),
            sha256 = "",
            contextLength = 32_768,
            minRamBytes = 0L,
            preset = Preset.CUSTOM,
            family = ModelFamily.GENERIC,
        )
    }

    /**
     * False while the registry entry still carries placeholder size and hash.
     *
     * The download machinery verifies SHA-256 before a file is allowed to become a model,
     * which is worth nothing if the expected hash is an empty string. Rather than quietly
     * skipping the check, this reports the entry as unusable: an unverified gigabyte from
     * the network is exactly the thing the rest of this file exists to prevent.
     *
     * To pin an entry: download the file, record its byte count and SHA-256 here.
     */
    fun verified(model: Model): Boolean = model.bytes > 0L && model.sha256.isNotEmpty()

    /** Total device RAM, for section 6's "warn on a 6 GB device". */
    val deviceRamBytes: Long
        get() = ActivityManager.MemoryInfo().also { info ->
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                .getMemoryInfo(info)
        }.totalMem

    /** Free space where the model would land, for section 6's storage warning. */
    val freeBytes: Long
        get() = runCatching {
            StatFs(context.filesDir.absolutePath).availableBytes
        }.getOrDefault(0L)

    fun fits(model: Model): Boolean = deviceRamBytes >= model.minRamBytes

    /** Section 6: warn under roughly 2 GB free, over and above the model itself. */
    fun hasRoom(model: Model): Boolean = freeBytes > model.totalBytes + 2 * GB

    /**
     * Download [model] into [installDir], verifying before it is allowed to be a model.
     *
     * @throws IllegalStateException if the entry is unpinned, or the bytes do not match
     */
    suspend fun install(model: Model, onProgress: (Progress) -> Unit = {}): File =
        withContext(Dispatchers.IO) {
            check(verified(model)) {
                "${model.label} has no pinned checksum in LlmStore.MODELS, so it cannot " +
                    "be verified and will not be downloaded"
            }
            val target = fileFor(model)
            if (isInstalled(model)) return@withContext target

            installDir.mkdirs()
            val part = File(installDir, model.fileName + ".part")

            val digest = Downloads.download(model.url, part) { copied ->
                onProgress(Progress(copied, model.bytes))
            }
            if (!digest.equals(model.sha256, ignoreCase = true)) {
                part.delete()
                error("${model.label}: checksum mismatch (got $digest)")
            }
            if (!part.renameTo(target)) {
                part.delete()
                error("${model.label}: could not move into place")
            }
            Log.i(TAG, "installed ${model.id} (${model.bytes} bytes)")
            target
        }

    /**
     * Installs a model from a file the user picked, identified by its checksum.
     *
     * The gigabyte is the whole problem this solves. On a metered connection, or a phone
     * that has already downloaded it once, or a desk with the GGUF sitting on a USB
     * stick, a second download is the wrong answer -- but so is trusting whatever file
     * gets handed over. Hashing on the way in settles both: the digest is what says which
     * registry entry this is, so an unpinned or corrupted file cannot be installed even
     * by accident.
     *
     * @return the registry entry the file turned out to be
     * @throws IllegalStateException if the file matches no entry in [MODELS]
     */
    suspend fun install(uri: Uri, onProgress: (Progress) -> Unit = {}): Model =
        withContext(Dispatchers.IO) {
            val document = Imports.describe(context, uri)
            installDir.mkdirs()
            val part = File(installDir, "import.part")

            val digest = Imports.copy(context, uri, part) { copied ->
                onProgress(Progress(copied, document.bytes))
            }
            val model = MODELS.firstOrNull { it.sha256.equals(digest, ignoreCase = true) }
            if (model == null) {
                part.delete()
                error(
                    document.name + " is not one of the models this build expects. Its " +
                        "checksum has to match exactly; a partial download will not.",
                )
            }

            val target = fileFor(model)
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                error(model.label + ": could not move into place")
            }
            Log.i(TAG, "imported " + model.id + " from " + document.name)
            model
        }

    /**
     * ToDo's "HF link to proper format": turns a pasted download URL into a [Model] by
     * reading Hugging Face's `x-linked-etag` / `x-linked-size` headers -- the same trick
     * [MODELS]'s own doc comment above documents for pinning a registry entry by hand,
     * run here at request time instead. [install] then proceeds completely unchanged:
     * this function only produces the [Model] and never touches the file's bytes, so the
     * verification code downstream is fed, not modified.
     *
     * The id is derived from the hash rather than the URL, so the same file reached
     * through two different links -- a mirror, a redirected revision -- lands in one
     * installed slot instead of two.
     *
     * This verifies that the bytes match what the host serves. It does **not** vouch for
     * the file's contents beyond that: [family] is [ModelFamily.GENERIC], so
     * [LocalLlmTranslator] asks llama.cpp to detect the GGUF's own chat template at load
     * time rather than assuming Qwen3's -- see [ModelFamily.GENERIC]'s doc for what that
     * does and does not cover. [Model.preset] is set to [Preset.CUSTOM] so the picker can
     * carry that caption through to the model card.
     *
     * @throws IllegalStateException if the host answers with no linked checksum -- an
     *   unverifiable download is refused rather than silently accepted
     */
    suspend fun resolve(url: String, label: String): Model = withContext(Dispatchers.IO) {
        val headers = Downloads.head(url)
        val sha256 = headers["x-linked-etag"]?.trim('"').orEmpty()
        val bytes = headers["x-linked-size"]?.toLongOrNull() ?: 0L
        check(sha256.isNotEmpty() && bytes > 0L) {
            "This link did not report a linked checksum, so it cannot be verified and " +
                "will not be downloaded. Paste a Hugging Face \"resolve\" link to a " +
                ".gguf file."
        }
        Model(
            id = CUSTOM_PREFIX + sha256.take(16),
            label = label.ifBlank { "Custom model" },
            detail = "Imported from a pasted link. Chat format is detected from the " +
                "model's own file at first use -- most instruction-tuned models work, " +
                "but a template llama.cpp does not recognize will fail rather than " +
                "translate nonsense, and a hybrid-reasoning model may spend its whole " +
                "budget thinking and return nothing.",
            url = url.trim(),
            bytes = bytes,
            sha256 = sha256,
            contextLength = 32_768,
            minRamBytes = 8 * GB,
            preset = Preset.CUSTOM,
            family = ModelFamily.GENERIC,
        )
    }

    /** Section 6: "offer delete model in settings, and actually free the file." */
    fun uninstall(model: Model): Long {
        val file = fileFor(model)
        val size = file.length()
        return if (file.delete()) size else 0L
    }
}
