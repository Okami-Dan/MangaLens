package com.mangareader.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mangareader.R
import com.mangareader.database.MangaDatabase
import com.mangareader.pipeline.LlmStore
import com.mangareader.pipeline.PageTranslator
import com.mangareader.translate.ChatPromptFamily
import com.mangareader.translate.LocalLiquidTranslator
import com.mangareader.translate.LocalLlmTranslator
import com.mangareader.translate.Translator

/**
 * Tier 1's page job, as `docs/on-device-llm.md` section 4 requires: "run it in a
 * WorkManager job, not on the UI thread or a viewmodel scope".
 *
 * The reason is the measured latency. A page is 45–60 seconds of continuous CPU on the
 * target device, and a coroutine in the application scope survives navigation but not the
 * process: put the phone in a pocket for a minute and Android is entitled to kill an
 * app that is only holding a coroutine. A foreground worker with a notification is the
 * one thing that survives, and it is also the honest thing -- a minute of four-core
 * inference is something the user should be able to see and cancel.
 *
 * Tier 2 does **not** use this. A network round trip is seconds, it holds no CPU, and a
 * notification for it would be noise.
 *
 * The queue is serialized by name ([WORK_NAME], `APPEND_OR_REPLACE`) because section 4
 * warns about thermal throttling across a run of several pages: two of these at once
 * would be slower than either alone and would double peak memory on a device chosen for
 * having little.
 */
class TranslationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pageId = inputData.getString(KEY_PAGE_ID) ?: return Result.failure()
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val pageContext = inputData.getString(KEY_PAGE_CONTEXT).orEmpty()
        val force = inputData.getBoolean(KEY_FORCE, false)

        val store = LlmStore(applicationContext)
        val model = store.installed(modelId) ?: return Result.failure(
            workDataOf(KEY_ERROR to "the on-device model is not installed"),
        )

        setForeground(foregroundInfo(0, 0))

        val translator: Translator = when (model.family) {
            LlmStore.ModelFamily.QWEN3 -> LocalLlmTranslator(store.fileFor(model), model.id)
            LlmStore.ModelFamily.LFM2_MT -> LocalLiquidTranslator(store.fileFor(model), model.id)
            LlmStore.ModelFamily.GENERIC -> LocalLlmTranslator(
                store.fileFor(model), model.id, promptFamily = ChatPromptFamily.GENERIC,
            )
        }

        return try {
            val result = translator.use { active ->
                PageTranslator(applicationContext).translate(
                    pageId = pageId,
                    translator = active,
                    pageContext = pageContext,
                    force = force,
                    currentPageContext = { applicationContext.currentTranslationNote() },
                ) { progress ->
                    // Best effort: the notification is the only progress a backgrounded
                    // user can see, and failing to update it must not fail the page.
                    runCatching { setForegroundAsync(foregroundInfo(progress.done, progress.total)) }
                }
            }
            Result.success(
                workDataOf(
                    KEY_TRANSLATED to result.translated,
                    KEY_BUBBLES to result.bubbles,
                ),
            )
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            Result.failure(workDataOf(KEY_ERROR to (failure.message ?: failure.toString())))
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    "Translation",
                    // Low: this is a progress report the user asked for, not news.
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Translating page")
            .setContentText(
                if (total > 0) "$done of $total bubbles" else "Loading the model…",
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val WORK_NAME = "translate-page"
        const val KEY_PAGE_ID = "pageId"
        const val KEY_MODEL_ID = "modelId"
        const val KEY_PAGE_CONTEXT = "pageContext"
        const val KEY_FORCE = "force"
        const val KEY_ERROR = "error"
        const val KEY_TRANSLATED = "translated"
        const val KEY_BUBBLES = "bubbles"

        private const val CHANNEL = "translation"
        private const val NOTIFICATION_ID = 4201

        /** Queues one page. Serialized: never two models resident at once. */
        fun enqueue(
            context: Context,
            pageId: String,
            modelId: String,
            pageContext: String,
            force: Boolean,
        ) {
            val request = OneTimeWorkRequestBuilder<TranslationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_PAGE_ID, pageId)
                        .putString(KEY_MODEL_ID, modelId)
                        .putString(KEY_PAGE_CONTEXT, pageContext)
                        .putBoolean(KEY_FORCE, force)
                        .build(),
                )
                .addTag(pageId)
                .build()

            WorkManager.getInstance(context)
                .beginUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
                .enqueue()
        }
    }
}
