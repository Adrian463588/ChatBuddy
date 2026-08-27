package com.chatbuddy.data.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chatbuddy.domain.model.ModelStatus
import com.chatbuddy.domain.model.AppResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import kotlinx.coroutines.CancellationException

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val manager: ResumableDownloadManager,
    private val stateStore: ModelStateStore,
    private val safModelStore: SafModelStore
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ARTIFACT_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Download request has no model id"))
        val artifact = stateStore.find(id)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Model manifest entry is unavailable"))
        if (stateStore.isPauseRequested(id)) {
            stateStore.markPaused(id)
            return Result.success()
        }
        val foreground = artifact.sizeBytes >= FOREGROUND_THRESHOLD_BYTES
        if (foreground) {
            try {
                setForeground(
                    createForegroundInfo(
                        artifact.displayName,
                        safModelStore.checkpoint(artifact).coerceIn(0L, artifact.sizeBytes),
                        artifact.sizeBytes
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = "Background model download is unavailable: ${error.message ?: "foreground service failed"}"
                stateStore.update(id, ModelStatus.Error(message))
                return Result.failure(workDataOf(KEY_ERROR to message))
            }
        }
        return when (val result = runDownload(id, foreground)) {
            is AppResult.Success -> Result.success()
            is AppResult.Error -> if (result.cause is IOException) {
                stateStore.update(
                    id,
                    ModelStatus.Queued(
                        totalBytes = artifact.sizeBytes,
                        downloadedBytes = safModelStore.checkpoint(artifact)
                            .coerceIn(0L, artifact.sizeBytes)
                    )
                )
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to result.message))
            }
            AppResult.Loading -> Result.retry()
        }
    }

    private suspend fun runDownload(artifactId: String, foreground: Boolean): AppResult<Unit> {
        val artifact = stateStore.find(artifactId)
            ?: return AppResult.Error("Model manifest entry is unavailable")
        var lastNotificationBytes = -FOREGROUND_NOTIFICATION_STEP
        return manager.download(artifact) { downloadedBytes, totalBytes ->
            if (foreground &&
                (downloadedBytes == totalBytes || downloadedBytes - lastNotificationBytes >= FOREGROUND_NOTIFICATION_STEP)
            ) {
                lastNotificationBytes = downloadedBytes
                setProgress(workDataOf(KEY_DOWNLOADED_BYTES to downloadedBytes))
                setForeground(createForegroundInfo(artifact.displayName, downloadedBytes, totalBytes))
            }
        }
    }

    private fun createForegroundInfo(
        displayName: String,
        downloadedBytes: Long,
        totalBytes: Long
    ): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL,
                    "Model downloads",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val progress = if (totalBytes > 0L) {
            ((downloadedBytes.toDouble() / totalBytes.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ChatBuddy model download")
            .setContentText(displayName)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .build()
        val notificationId = id.hashCode()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val KEY_ARTIFACT_ID = "artifact_id"
        const val KEY_ERROR = "error"
        private const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        private const val NOTIFICATION_CHANNEL = "model_downloads"
        private const val FOREGROUND_THRESHOLD_BYTES = 100_000_000L
        private const val FOREGROUND_NOTIFICATION_STEP = 1_000_000L
    }
}
