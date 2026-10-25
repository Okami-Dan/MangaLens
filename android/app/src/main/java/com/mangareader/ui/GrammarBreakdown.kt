package com.mangareader.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mangareader.database.GrammarNoteEntity
import com.mangareader.database.MangaDatabase
import com.mangareader.model.GrammarAnswer
import com.mangareader.model.Phrase
import com.mangareader.model.LexicalUnit
import com.mangareader.model.TranslationContext
import com.mangareader.translate.grammarRequest
import com.mangareader.translate.validated
import com.mangareader.pipeline.translationContext
import com.mangareader.pipeline.LlmStore
import com.mangareader.translate.ChatPromptFamily
import com.mangareader.translate.GrammarExplainer
import com.mangareader.translate.LocalGrammarExplainer
import com.mangareader.translate.NoOpGrammarExplainer
import com.mangareader.translate.RemoteGrammarExplainer
import com.mangareader.translate.grammarCacheKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Section 3.3 tier 4's optional half, hoisted the way [TranslationController] hoists
 * stage 7.
 *
 * Less is owned here than in that controller, and the difference is the point. A
 * translation is a page-long job worth surviving navigation, so it goes to a worker; an
 * explanation is one short call about the bubble in front of the user, and if they close
 * the sheet the answer has nowhere to go. So this is a plain coroutine in the app scope,
 * and cancelling it costs nothing anyone was waiting for.
 *
 * **The offline breakdown is not here at all.** `BubbleSheet` derives it directly from the
 * tokens through `AnalysisController.grammar`. This controller exists only for what a model
 * wrote, which is the part worth a table and a cache.
 */
class GrammarController(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: ReaderSettings,
    private val translation: TranslationController,
) {
    private val appContext = context.applicationContext
    private val database = MangaDatabase.get(appContext)

    /**
     * Answers by region, for the sheet to fold into the offline breakdown. Keyed by region
     * id rather than by cache key because that is what the sheet has in hand; the cache
     * key does the work of noticing that the text changed underneath it.
     */
    private val answers = mutableStateMapOf<String, GrammarAnswer>()
    private val requests = mutableStateMapOf<String, String>()

    /** The region currently being explained, if any. One at a time, deliberately. */
    var running by mutableStateOf<Long?>(null)
        private set

    /** Last failure, until something else is attempted. Shown in place, not toasted. */
    var error by mutableStateOf<String?>(null)
        private set

    private var job: Job? = null

    /**
     * The configured tier, built fresh on every call for the reason
     * [TranslationController.translator] gives: a client still pointing at the previous
     * base URL after the user edits it presents as a broken endpoint and is very hard to
     * see. Construction is a few string operations.
     */
    fun explainer(): GrammarExplainer = when (settings.translatorTier.value) {
        TranslatorTier.NONE -> NoOpGrammarExplainer

        TranslatorTier.REMOTE -> settings.remoteProvider.value.let { provider ->
            RemoteGrammarExplainer(
                baseUrl = settings.remoteBaseUrl.value,
                apiKey = translation.secrets.keyFor(provider),
                model = settings.remoteModel.value,
                protocol = provider.protocol,
            )
        }

        // A model in ModelFamily.LFM2_MT cannot write a grammar note at all -- that is
        // arbitrary instruction following, which a dedicated translation model was never
        // trained to do -- so it reads as unconfigured here rather than being asked.
        // GENERIC gets the same treatment QWEN3 does: nothing about this prompt is
        // Qwen3-specific beyond the ChatML wrapper, which LocalGrammarExplainer's
        // promptStrategy already abstracts for the translator's sake.
        TranslatorTier.LOCAL -> translation.localModel()?.let { model ->
            when (model.family) {
                LlmStore.ModelFamily.QWEN3 ->
                    LocalGrammarExplainer(translation.llm.fileFor(model), model.id)
                LlmStore.ModelFamily.GENERIC -> LocalGrammarExplainer(
                    translation.llm.fileFor(model), model.id,
                    promptFamily = ChatPromptFamily.GENERIC,
                )
                LlmStore.ModelFamily.LFM2_MT -> null
            }
        } ?: NoOpGrammarExplainer
    }

    val configured: Boolean get() = explainer().configured

    /** For the button's footnote: which model would write, or wrote, these notes. */
    val label: String get() = explainer().label
    val identity: String get() = explainer().id

    private fun slot(pageId: String, regionId: Long) = "$pageId/$regionId"
    fun answerFor(pageId: String, regionId: Long): GrammarAnswer =
        answers[slot(pageId, regionId)] ?: GrammarAnswer.NONE

    fun answerFor(pageId: String, regionId: Long, text: String, phrases: List<Phrase>,
        words: List<LexicalUnit>, context: TranslationContext): GrammarAnswer {
        val key = grammarCacheKey(text, identity, grammarRequest(text, phrases, words, context))
        return if (requests[slot(pageId, regionId)] == key) answerFor(pageId, regionId) else GrammarAnswer.NONE
    }

    /**
     * Serve this bubble's notes from the cache if they are there.
     *
     * Called when the sheet opens a region. A miss is silent and leaves the button
     * offering to run: the section is already complete without it.
     */
    fun load(pageId: String, regionId: Long, text: String, phrases: List<Phrase>,
        words: List<LexicalUnit>, context: TranslationContext) {
        val slot = slot(pageId, regionId)
        answers.remove(slot)
        requests.remove(slot)
        if (text.isBlank()) return
        val explainer = explainer()
        if (!explainer.configured) return
        val key = grammarCacheKey(text, explainer.id, grammarRequest(text, phrases, words, context))
        requests[slot] = key
        scope.launch {
            val cached = runCatching { database.grammarNotes().byKey(key) }.getOrNull()
            if (cached != null && requests[slot] == key) answers[slot] = cached.answer().validated(phrases, words)
        }
    }

    /**
     * Ask the model about one bubble.
     *
     * @param phrases the offline breakdown, numbered, passed through to the prompt so the
     *   model is asked to improve on it token by token rather than to write a second copy
     */
    fun explain(
        pageId: String,
        regionId: Long,
        text: String,
        phrases: List<Phrase>,
        words: List<LexicalUnit> = emptyList(),
    ) {
        if (running != null || text.isBlank()) return
        val explainer = explainer()
        if (!explainer.configured) {
            error = "No translator configured. Choose one in Settings."
            return
        }
        error = null
        running = regionId
        val requestSlot = slot(pageId, regionId)
        val expectedRequest = requests[requestSlot]
        job?.cancel()
        job = scope.launch {
            try {
                explainer.use { active ->
                    val context = database.translationContext(pageId, settings.pageContext.value)
                    val slot = slot(pageId, regionId)
                    val key = grammarCacheKey(text, active.id, grammarRequest(text, phrases, words, context))
                    // A text/reading/context/model change while resolving metadata cancels
                    // this snapshot before it can overwrite the new request identity.
                    if (requests[requestSlot] != expectedRequest) return@use
                    requests[slot] = key

                    // Checked again here rather than trusting `load`: the user can tap
                    // the button before the read that `load` started has come back, and
                    // paying a hosted endpoint twice for one answer is exactly what the
                    // table exists to prevent.
                    val cached = runCatching { database.grammarNotes().byKey(key) }.getOrNull()
                    val written = cached?.answer()?.validated(phrases, words) ?:
                        active.explain(text, phrases, words, context).also { fresh ->
                        if (!fresh.isEmpty) {
                            runCatching {
                                database.grammarNotes().put(
                                    GrammarNoteEntity.of(
                                        key = key,
                                        pageId = pageId,
                                        regionId = regionId,
                                        explainerId = active.id,
                                        answer = fresh,
                                    ),
                                )
                            }
                        }
                    }
                    if (requests[slot] == key) answers[slot] = written
                    if (written.isEmpty) {
                        // A model that answered with nothing usable is not an error, but
                        // it is not silence either -- without this the button would go
                        // back to its resting state and look like it was never pressed.
                        error = "The model did not return anything usable for this bubble."
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

    /** Section 7's cache, emptied from Settings alongside the translations. */
    fun forgetCache(onDone: (Int) -> Unit = {}) {
        scope.launch {
            val count = runCatching { database.grammarNotes().count() }.getOrDefault(0)
            runCatching { database.grammarNotes().clearAll() }
                .onFailure { error = it.message ?: it.toString() }
            answers.clear()
            requests.clear()
            onDone(count)
        }
    }
}
