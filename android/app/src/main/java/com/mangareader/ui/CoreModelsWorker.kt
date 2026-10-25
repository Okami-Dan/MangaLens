package com.mangareader.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.mangareader.MainActivity
import com.mangareader.R
import com.mangareader.pipeline.ModelStore
import kotlinx.coroutines.CancellationException

/** One durable installation, shared by first-run setup and the Options repair action. */
class CoreModelsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = ModelStore(applicationContext)
        if (store.status() is ModelStore.Status.Ready) return Result.success()
        return try {
            setForeground(foreground("Preparing text recognition", 0f))
            val status = store.install { progress ->
                setProgressAsync(workDataOf(
                    KEY_LABEL to progress.label,
                    KEY_DONE to progress.bytesDone,
                    KEY_TOTAL to progress.bytesTotal,
                    KEY_INDEX to progress.index,
                    KEY_COUNT to progress.total,
                ))
                applicationContext.getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(progress.label, progress.fraction))
            }
            if (status is ModelStore.Status.Ready) Result.success()
            else Result.failure(workDataOf(KEY_ERROR to "Some text recognition files are still missing."))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (failure.message ?: "Setup could not finish.")))
        }
    }

    private fun notification(label: String, fraction: Float): android.app.Notification {
        applicationContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Text recognition setup",
                NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("Preparing Manga Lens")
            .setContentText(label)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, (fraction * 100).toInt().coerceIn(0, 100), false)
            .build()
    }

    private fun foreground(label: String, fraction: Float): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification(label, fraction), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, notification(label, fraction))

    companion object {
        const val WORK_NAME = "install-core-models"
        const val KEY_LABEL = "label"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_INDEX = "index"
        const val KEY_COUNT = "count"
        const val KEY_ERROR = "error"
        private const val CHANNEL = "core-model-setup"
        private const val NOTIFICATION_ID = 4203

        fun enqueue(context: Context): androidx.work.Operation {
            val pending = (ModelStore(context).status() as? ModelStore.Status.Incomplete)?.pending.orEmpty()
            val request = OneTimeWorkRequestBuilder<CoreModelsWorker>()
            if (pending.any { it.source == ModelStore.Source.REMOTE }) {
                request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            }
            return WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request.build())
        }

        fun progress(info: androidx.work.WorkInfo): ModelStore.Progress = ModelStore.Progress(
            label = if (info.state == androidx.work.WorkInfo.State.ENQUEUED)
                "Waiting to start. Connect to the internet if you are offline."
                else info.progress.getString(KEY_LABEL) ?: "Preparing text recognition",
            bytesDone = info.progress.getLong(KEY_DONE, 0),
            bytesTotal = info.progress.getLong(KEY_TOTAL, 0),
            index = info.progress.getInt(KEY_INDEX, 0),
            total = info.progress.getInt(KEY_COUNT, 0),
        )
    }
}
