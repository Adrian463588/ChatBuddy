package com.chatbuddy.data.repository

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.chatbuddy.data.download.DownloadWorker
import com.chatbuddy.data.download.ModelStateStore
import com.chatbuddy.data.download.ResumableDownloadManager
import com.chatbuddy.data.download.SafModelStore
import com.chatbuddy.data.model.ModelManifestDataSource
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.ModelState
import com.chatbuddy.domain.model.ModelStatus
import com.chatbuddy.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val manifest: ModelManifestDataSource,
    private val stateStore: ModelStateStore,
    private val safStore: SafModelStore,
    private val manager: ResumableDownloadManager,
    private val workManager: WorkManager
) : ModelRepository {
    override fun hasStorageFolder(): Boolean = safStore.treeUri() != null

    override suspend fun listArtifacts(): AppResult<List<ModelArtifact>> =
        AppResult.Success(manifest.read())

    override fun observeStates(): Flow<List<ModelState>> = stateStore.states

    override suspend fun selectStorageFolder(treeUri: String): AppResult<Unit> =
        safStore.selectTree(android.net.Uri.parse(treeUri))

    override suspend fun download(artifactId: String): AppResult<Unit> {
        val artifact = stateStore.find(artifactId)
            ?: return AppResult.Error("Unknown model artifact")
        if (safStore.treeUri() == null) {
            val message = "Choose a SAF storage folder before downloading"
            stateStore.update(artifact.id, ModelStatus.Error(message))
            return AppResult.Error(message)
        }
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ARTIFACT_ID to artifact.id))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        stateStore.update(artifact.id, ModelStatus.Queued(artifact.sizeBytes))
        return runCatching {
            workManager.enqueueUniqueWork(
                "chatbuddy-download-${artifact.id}",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { error ->
                val message = "Unable to schedule model download"
                stateStore.update(artifact.id, ModelStatus.Error(message))
                AppResult.Error(message, error)
            }
        )
    }

    override suspend fun pause(artifactId: String): AppResult<Unit> {
        workManager.cancelUniqueWork("chatbuddy-download-$artifactId")
        stateStore.markPaused(artifactId)
        return AppResult.Success(Unit)
    }

    override suspend fun verify(artifactId: String): AppResult<Unit> {
        val artifact = stateStore.find(artifactId)
            ?: return AppResult.Error("Unknown model artifact")
        return manager.verify(artifact)
    }
}
