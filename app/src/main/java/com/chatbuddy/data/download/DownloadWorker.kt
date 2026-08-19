package com.chatbuddy.data.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chatbuddy.domain.model.AppResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val manager: ResumableDownloadManager,
    private val stateStore: ModelStateStore
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ARTIFACT_ID) ?: return Result.failure()
        val artifact = stateStore.find(id) ?: return Result.failure()
        return when (val result = manager.download(artifact)) {
            is AppResult.Success -> Result.success()
            is AppResult.Error -> if (result.cause is IOException) Result.retry() else Result.failure()
            AppResult.Loading -> Result.retry()
        }
    }

    companion object {
        const val KEY_ARTIFACT_ID = "artifact_id"
    }
}
