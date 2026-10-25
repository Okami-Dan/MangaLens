package com.mangareader.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mangareader.database.MangaDatabase
import com.mangareader.database.SavedWordEntity
import com.mangareader.analysis.Grammar
import com.mangareader.analysis.GrammarLexicon
import com.mangareader.model.Token
import com.mangareader.pipeline.DictionaryStore
import com.mangareader.pipeline.ModelStore
import com.mangareader.pipeline.PageAnalyzer
import com.mangareader.pipeline.Vault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/**
 * The state behind "this page is being read right now", hoisted out of the reader.
 *
 * A page costs about ten seconds on the target device, most of it OCR, and the user is
 * free to walk back to the library while it runs. So the work is owned by a controller
 * remembered at the app level and the reader is a view of it -- leaving the screen does
 * not cancel the job, and coming back rejoins the progress already in flight.
 */
class AnalysisController(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val store = ModelStore(appContext)
    private val dictionaries = DictionaryStore(appContext)
    private val database = MangaDatabase.get(appContext)
    private val analyzer = PageAnalyzer(appContext)
    private val vault = Vault.get(appContext)
    private val workManager = WorkManager.getInstance(appContext)
    private var modelWork by mutableStateOf<WorkInfo?>(null)
    private var schedulingModels by mutableStateOf(false)
    private var importProgress by mutableStateOf<ModelStore.Progress?>(null)
    private var modelImportError by mutableStateOf<String?>(null)

    data class Running(
        val pageId: String,
        val stage: PageAnalyzer.Stage,
        val detail: String,
        val fraction: Float,
    )

    var models by mutableStateOf(store.status())
        private set

    /** Non-null while [installModels] is copying or downloading. */
    val install: ModelStore.Progress?
        get() = importProgress ?: when {
            schedulingModels -> ModelStore.Progress("Starting text recognition setup", 0, 0, 0, 0)
            else -> modelWork?.takeUnless { it.state.isFinished }?.let(CoreModelsWorker::progress)
        }

    val modelError: String?
        get() = modelImportError ?: if (schedulingModels || modelsReady) null else when (modelWork?.state) {
            WorkInfo.State.FAILED -> modelWork?.outputData?.getString(CoreModelsWorker.KEY_ERROR)
                ?: "Setup could not finish. Check your connection and available storage, then retry."
            WorkInfo.State.CANCELLED -> "Setup was interrupted. Retry to finish preparing text recognition."
            else -> null
        }

    /**
     * The per-stage seconds [PageAnalyzer.analyse] just measured for the most recent
     * page: `decode`/`load_detector`/`load_ocr`/`detect`/`order`/`ocr`/`analyse`.
     *
     * [PageAnalyzer.analyse]'s own log line already puts this in Logcat; the result was
     * otherwise discarded once [running] went back to null, which is what this fixes --
     * a future debug view, or a test, can now read it back without a device attached to
     * `adb logcat`. Cleared to null on failure -- a partial map from a cancelled or
     * errored run would misread as a page that got faster.
     */
    var lastTimings by mutableStateOf<Map<String, Double>?>(null)
        private set

    var running by mutableStateOf<Running?>(null)
        private set

    /** Non-null while the 53 MB JMdict asset is being unpacked out of the APK. */
    var dictionaryInstall by mutableStateOf<DictionaryStore.Progress?>(null)
        private set

    var dictionaryReady by mutableStateOf(dictionaries.isInstalled)
        private set

    /**
     * Section 3.3 tier 4's analyser, built once.
     *
     * Lazy because it parses the rule table, and a user who never opens a bubble sheet
     * should not pay for it at startup. Not tied to [dictionaryReady]: the rules ship in
     * the APK and are read straight out of assets, so unlike the glosses they are there
     * on a fresh install with nothing unpacked -- which is the whole point of tier 4
     * being offline.
     */
    val grammar: Grammar by lazy { Grammar(dictionaries.grammar()) }
    var grammarLexicon by mutableStateOf(GrammarLexicon.EMPTY)
        private set

    init {
        scope.launch {
            grammarLexicon = withContext(Dispatchers.IO) { dictionaries.grammarLexicon }
        }
        scope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(CoreModelsWorker.WORK_NAME).collect { infos ->
                modelWork = infos.firstOrNull { !it.state.isFinished } ?: infos.firstOrNull()
                if (modelWork?.state?.isFinished == true) refreshModels()
            }
        }
    }

    val dictionaryPackaged: Boolean get() = dictionaries.isPackaged

    val dictionaryBytes: Long get() = dictionaries.packagedBytes

    /** Last failure, until something else is attempted. Shown in place, not toasted. */
    var error by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    val modelsReady: Boolean get() = models is ModelStore.Status.Ready

    val stagedBytes: Long
        get() = store.stagingDirs.sumOf { dir ->
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }

    fun refreshModels() {
        models = store.status()
        dictionaryReady = dictionaries.isInstalled
    }

    /**
     * Unpack JMdict if it has not been unpacked. Called once at startup rather than
     * waited on: stage 6 asks for the same thing again before it runs, so a page
     * analysed in the first few seconds still gets its glosses -- this only moves the
     * cost off the first capture.
     */
    fun ensureDictionary() {
        if (dictionaryReady || dictionaryInstall != null || !dictionaries.isPackaged) return
        scope.launch {
            runCatching { dictionaries.install { dictionaryInstall = it } }
                .onFailure { error = it.message ?: it.toString() }
            dictionaryInstall = null
            dictionaryReady = dictionaries.isInstalled
        }
    }

    fun freeDictionary(): Long = dictionaries.uninstall().also { dictionaryReady = false }

    fun installModels() {
        if (modelsReady || install != null) return
        modelImportError = null
        schedulingModels = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { CoreModelsWorker.enqueue(appContext).result.get() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                modelImportError = failure.message ?: "Could not start text recognition setup."
            } finally {
                schedulingModels = false
            }
        }
    }

    /**
     * Section 4 stage 5 without a USB cable: install the ONNX graphs from files the user
     * picked. Shares [install] with the download path, so the same progress bar renders
     * it and the two can never run at once.
     */
    fun importModels(uris: List<Uri>) {
        if (importProgress != null || schedulingModels || uris.isEmpty()) return
        modelImportError = null
        importProgress = ModelStore.Progress("Checking selected files", 0, uris.size, 0, 0)
        scope.launch {
            var imported = false
            try {
                withContext(Dispatchers.IO) {
                    workManager.cancelUniqueWork(CoreModelsWorker.WORK_NAME).result.get()
                }
                modelWork = null
                store.install(uris) { importProgress = it }
                imported = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                modelImportError = failure.message ?: "Could not import text recognition files."
            } finally {
                importProgress = null
                refreshModels()
                if (imported && !modelsReady) installModels()
            }
        }
    }

    fun clearStaging(): Long = store.clearStaging()

    /** No-op if this page is already the one running. */
    fun analyse(pageId: String) {
        if (running?.pageId == pageId) return
        if (!modelsReady) {
            refreshModels()
            if (!modelsReady) return
        }
        error = null
        job?.cancel()
        running = Running(pageId, PageAnalyzer.Stage.LOAD, "", -1f)
        job = scope.launch {
            runCatching {
                analyzer.analyse(pageId) { progress ->
                    running = Running(
                        pageId = pageId,
                        stage = progress.stage,
                        detail = progress.detail,
                        fraction = progress.fraction,
                    )
                }
            }.onSuccess {
                lastTimings = it.timings
            }.onFailure {
                lastTimings = null
                error = it.message ?: it.toString()
            }
            running = null
            dictionaryReady = dictionaries.isInstalled
            // Ten seconds of OCR and dictionary work the user would rather not repeat if
            // the app is reinstalled before it next reaches `onStop`.
            vault.snapshotSoon()
        }
    }

    /**
     * Section 3.3's Tier 1 edit: the correction is stored and tier 2 is re-derived for
     * that region alone. [PageAnalyzer.editRegionText] does the work; this exists so the
     * sheet does not have to own a coroutine scope or know about the database.
     */
    fun editRegionText(pageId: String, regionId: Long, text: String?) {
        error = null
        scope.launch {
            runCatching { analyzer.editRegionText(pageId, regionId, text) }
                .onFailure { error = it.message ?: it.toString() }
        }
    }

    suspend fun refreshAnalysis(pageId: String) {
        runCatching { analyzer.refreshAnalysis(pageId) }
            .onFailure { error = it.message ?: it.toString() }
    }

    fun editTokenReading(
        pageId: String,
        regionId: Long,
        position: Int,
        surface: String,
        reading: String?,
    ) {
        error = null
        scope.launch {
            runCatching { analyzer.editTokenReading(pageId, regionId, position, surface, reading) }
                .onSuccess { vault.snapshotSoon() }
                .onFailure { error = it.message ?: it.toString() }
        }
    }

    /** Section 3.3 tier 2: "tapping a token pins it / adds it to a saved list". */
    fun pin(token: Token, pageId: String?, regionId: Long?, context: String) {
        scope.launch {
            database.savedWords().insert(
                SavedWordEntity(
                    baseForm = token.baseForm,
                    reading = token.baseReading.ifEmpty { token.reading },
                    gloss = token.glosses.joinToString("; ") { it.text },
                    posLabel = token.posLabel,
                    jmdictEntryId = token.jmdictEntryId,
                    sourcePageId = pageId,
                    sourceRegionId = regionId,
                    context = context,
                    savedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun unpin(baseForm: String, reading: String) {
        scope.launch { database.savedWords().delete(baseForm, reading) }
    }
}
