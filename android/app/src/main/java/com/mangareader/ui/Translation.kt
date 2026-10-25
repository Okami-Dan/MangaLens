package com.mangareader.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mangareader.database.MangaDatabase
import com.mangareader.pipeline.LlmStore
import com.mangareader.pipeline.PageTranslator
import com.mangareader.translate.ChatPromptFamily
import com.mangareader.translate.LocalLiquidTranslator
import com.mangareader.translate.LocalLlmTranslator
import com.mangareader.translate.NoOpTranslator
import com.mangareader.translate.RemoteTranslator
import com.mangareader.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import com.mangareader.model.TranslationContext
import com.mangareader.model.PageKind
import com.mangareader.model.Region
import com.mangareader.translate.cacheKey
import com.mangareader.pipeline.translationContext

/**
 * Stage 7's state, hoisted to the app the way [AnalysisController] hoists stages 3 to 6.
 *
 * Same reason, more so. Analysis costs about ten seconds; a translation costs a network
 * round trip on tier 2 and about a minute on tier 1 (`docs/on-device-llm.md` section 4),
 * and the user is free to walk back to the library while it runs. So the job is owned
 * here and the reader is a view of it.
 *
 * This is also the seam where settings meet the translator: [translator] turns the tier,
 * the base URL, the model name and the key into one of section 5's three implementations.
 * `:feature:pipeline` and `:ml:translate` are kept clear of DataStore and of the keystore
 * so that both stay testable off a device.
 */
class TranslationController(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: ReaderSettings,
) {
    private val appContext = context.applicationContext
    private val database = MangaDatabase.get(appContext)
    private val pages = PageTranslator(appContext)

    val secrets = TranslatorSecrets(appContext)

    /** Tier 1's registry and weights. Also read by the settings screen's model card. */
    val llm = LlmStore(appContext)

    init {
        // One-time: resolve which RemoteProvider a pre-existing base URL corresponds to
        // and persist it, then move the single legacy key into that provider's slot.
        // Both settings.remoteProvider's read-time fallback and this write agree on the
        // same resolution, so there is no window where a translator() call sees a
        // different provider than the key it is about to look up.
        scope.launch { secrets.adoptLegacyKey(settings.ensureRemoteProvider()) }
    }

    /** Non-null while tier 1's model is downloading. */
    var modelInstall by mutableStateOf<LlmStore.Progress?>(null)
        private set

    /**
     * Which model [modelInstall] belongs to, so a screen showing more than one model's
     * card at a time -- or, after this change, a screen showing one card that can be
     * swapped out from under an in-flight download -- can tell which card the bytes are
     * actually for rather than lighting up every card that happens to be visible.
     */
    var installingId by mutableStateOf<String?>(null)
        private set

    data class Running(
        val pageId: String,
        val done: Int,
        val total: Int,
        val detail: String,
    ) {
        val fraction: Float get() = if (total <= 0) -1f else done.toFloat() / total
    }

    var running by mutableStateOf<Running?>(null)
        private set

    /** Last failure, until something else is attempted. Shown in place, not toasted. */
    var error by mutableStateOf<String?>(null)
        private set

    /** The last page's outcome, so the sheet can say how much of it came back. */
    var last by mutableStateOf<PageTranslator.Result?>(null)
        private set

    private var job: Job? = null

    /**
     * Section 5's tier, as configured right now.
     *
     * Built fresh on every call rather than cached: the alternative is an HTTP client
     * still pointing at the previous base URL after the user edits it, which presents as
     * a broken endpoint and is very hard to see. Construction is a few string operations;
     * the client inside it is lazy and only materializes on a real call.
     */
    fun translator(): Translator = when (settings.translatorTier.value) {
        TranslatorTier.NONE -> NoOpTranslator

        TranslatorTier.REMOTE -> settings.remoteProvider.value.let { provider ->
            RemoteTranslator(
                baseUrl = settings.remoteBaseUrl.value,
                apiKey = secrets.keyFor(provider),
                model = settings.remoteModel.value,
                protocol = provider.protocol,
            )
        }

        // Tier 1 needs a model on disk. Until one is downloaded this reads as
        // unconfigured, which surfaces in Settings and in the sheet rather than failing at
        // the moment the user taps translate on a page. Which Translator gets built
        // depends on the model's family -- see LlmStore.ModelFamily.
        TranslatorTier.LOCAL -> localModel()?.let { model ->
            when (model.family) {
                LlmStore.ModelFamily.QWEN3 -> LocalLlmTranslator(llm.fileFor(model), model.id)
                LlmStore.ModelFamily.LFM2_MT -> LocalLiquidTranslator(llm.fileFor(model), model.id)
                LlmStore.ModelFamily.GENERIC -> LocalLlmTranslator(
                    llm.fileFor(model), model.id, promptFamily = ChatPromptFamily.GENERIC,
                )
            }
        } ?: NoOpTranslator
    }

    /** The registry entry the user chose, if its weights are actually installed. */
    fun localModel(): LlmStore.Model? {
        val chosen = settings.localModel.value
        return if (chosen.isEmpty()) {
            LlmStore.MODELS.firstOrNull { llm.isInstalled(it) }
        } else {
            llm.installed(chosen)
        }
    }

    val configured: Boolean get() = translator().configured

    val contextNote: String get() = settings.pageContext.value

    fun observeContext(pageId: String) = database.pages().observeContext(pageId).map { row ->
        if (row == null || row.seriesId == MangaDatabase.UNSORTED_ID) TranslationContext()
        else TranslationContext(row.seriesTitle, row.japaneseTitle)
    }

    fun matchesRequest(regions: List<Region>, modelId: String, cover: Boolean,
        context: TranslationContext, requestKey: String?): Boolean {
        val liquid = LlmStore.MODELS.firstOrNull { it.id == modelId }?.family == LlmStore.ModelFamily.LFM2_MT
        return requestKey == cacheKey(regions, modelId, cover, if (liquid) "" else context.promptNote())
    }

    /** Old paid cache rows remain available; displayed results must match current metadata. */
    suspend fun validateStoredContext(pageId: String) {
        val page = database.pages().byId(pageId) ?: return
        val rows = database.regions().byPage(pageId)
        val context = database.translationContext(pageId, contextNote).promptNote()
        val regions = rows.map { it.toRegion() }
        val mismatch = rows.filter { it.translatorId != null }.any { row ->
            val id = row.translatorId!!
            val liquid = LlmStore.MODELS.firstOrNull { it.id == id }?.family == LlmStore.ModelFamily.LFM2_MT
            row.translationKey != cacheKey(regions, id, page.kind == PageKind.COVER, if (liquid) "" else context)
        }
        if (mismatch) database.regions().clearTranslations(pageId)
    }

    /** For tier 3's footnote: which model produced the line the user is looking at. */
    fun labelFor(translatorId: String?): String = when {
        translatorId.isNullOrEmpty() -> ""
        translatorId == NoOpTranslator.id -> NoOpTranslator.label
        translatorId.startsWith("remote:") -> translatorId.substringAfterLast(':')
        else -> LlmStore.MODELS.firstOrNull { it.id == translatorId }?.label ?: translatorId
    }

    /**
     * Section 6 of docs/on-device-llm.md: the download is explicit, stated in advance, and
     * undoable. This is the "explicit tap" half; the card states the size before it.
     */
    fun installModel(model: LlmStore.Model) {
        if (modelInstall != null) return
        error = null
        installingId = model.id
        scope.launch {
            runCatching { llm.install(model) { modelInstall = it } }
                .onFailure { error = it.message ?: it.toString() }
            modelInstall = null
            installingId = null
            settings.setLocalModel(model.id)
        }
    }

    /**
     * Tier 1's weights from a file rather than the network -- a GGUF already downloaded
     * once, or carried over on a stick. [LlmStore.install] decides which registry entry
     * it is by hashing it, so this cannot install something unpinned.
     *
     * @param expectedId the row the picker was launched from -- only for [installingId],
     *   since the hash match that decides the real id does not run until the copy is done
     */
    fun importModel(uri: Uri, expectedId: String) {
        if (modelInstall != null) return
        error = null
        installingId = expectedId
        scope.launch {
            runCatching { llm.install(uri) { modelInstall = it } }
                .onSuccess { settings.setLocalModel(it.id) }
                .onFailure { error = it.message ?: it.toString() }
            modelInstall = null
            installingId = null
        }
    }

    /** Section 6: "offer delete model in settings, and actually free the file." */
    fun deleteModel(model: LlmStore.Model): Long = llm.uninstall(model)

    /**
     * ToDo's "HF link to proper format": resolves a pasted download URL through
     * [LlmStore.resolve], remembers the result so the settings card survives a restart,
     * then downloads it exactly the way [installModel] downloads a registry preset.
     */
    fun resolveCustomModel(url: String, label: String) {
        if (modelInstall != null) return
        error = null
        scope.launch {
            val model = runCatching { llm.resolve(url, label) }
                .onFailure { error = it.message ?: it.toString() }
                .getOrNull() ?: return@launch

            settings.setCustomModel(model)
            installingId = model.id
            runCatching { llm.install(model) { modelInstall = it } }
                .onFailure { error = it.message ?: it.toString() }
            modelInstall = null
            installingId = null
            settings.setLocalModel(model.id)
        }
    }

    /** Forgets the custom model's metadata and, if it is on disk, deletes its file too. */
    fun forgetCustomModel(model: LlmStore.Model): Long {
        val freed = llm.uninstall(model)
        settings.setCustomModel(null)
        if (settings.localModel.value == model.id) settings.setLocalModel("")
        return freed
    }

    /**
     * @param force re-run even on a cache hit -- the sheet's "translate again"
     */
    fun translate(pageId: String, force: Boolean = false) {
        if (running != null) return

        // Tier 1 goes to a foreground worker rather than this scope. A page is 45-60
        // seconds of continuous CPU (docs/on-device-llm.md section 4), and a coroutine in
        // the application scope survives navigation but not the process -- pocket the
        // phone mid-page and Android is entitled to kill an app that is only holding a
        // coroutine. Tier 2 stays here: a network round trip is seconds and a
        // notification for it would be noise.
        if (settings.translatorTier.value == TranslatorTier.LOCAL) {
            val model = localModel()
            if (model == null) {
                error = "The on-device model is not downloaded yet. See Settings."
                return
            }
            error = null
            running = Running(pageId, 0, 0, "Queued")
            TranslationWorker.enqueue(
                context = appContext,
                pageId = pageId,
                modelId = model.id,
                pageContext = settings.pageContext.value,
                force = force,
            )
            observeWork(pageId)
            return
        }

        val active = translator()
        if (!active.configured) {
            error = "No translator configured. Choose one in Settings."
            return
        }
        error = null
        job?.cancel()
        running = Running(pageId, 0, 0, "Translating")
        job = scope.launch {
            try {
                active.use { translator ->
                    last = pages.translate(
                        pageId = pageId,
                        translator = translator,
                        pageContext = settings.pageContext.value,
                        force = force,
                        currentPageContext = { settings.currentPageContext() },
                    ) { progress ->
                        running = Running(
                            pageId = pageId,
                            done = progress.done,
                            total = progress.total,
                            detail = progress.detail,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                error = failure.message ?: failure.toString()
            } finally {
                running = null
            }
        }
    }

    /**
     * Mirror the worker's state onto [running] and [error], so the reader and the sheet
     * render a tier-1 job exactly as they render a tier-2 one.
     */
    private fun observeWork(pageId: String) {
        job?.cancel()
        job = scope.launch {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWorkFlow(TranslationWorker.WORK_NAME)
                .collect { infos ->
                    val info = infos.lastOrNull { pageId in it.tags } ?: return@collect
                    when (info.state) {
                        WorkInfo.State.ENQUEUED ->
                            running = Running(pageId, 0, 0, "Queued")

                        WorkInfo.State.RUNNING ->
                            running = Running(pageId, 0, 0, "Translating on this phone")

                        WorkInfo.State.FAILED -> {
                            error = info.outputData.getString(TranslationWorker.KEY_ERROR)
                                ?: "The on-device translator failed."
                            running = null
                        }

                        WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> running = null

                        else -> Unit
                    }
                }
        }
    }

    /** Forget this page's English and its cache entry, so the next run really re-runs. */
    fun clear(pageId: String) {
        error = null
        scope.launch {
            runCatching { pages.clear(pageId) }
                .onFailure { error = it.message ?: it.toString() }
            last = null
        }
    }

    /** Section 7's cache, emptied from Settings. Reports how many pages it dropped. */
    fun forgetCache(onDone: (Int) -> Unit = {}) {
        scope.launch {
            val count = runCatching { database.translations().count() }.getOrDefault(0)
            runCatching { database.translations().clearAll() }
                .onFailure { error = it.message ?: it.toString() }
            onDone(count)
        }
    }
}
