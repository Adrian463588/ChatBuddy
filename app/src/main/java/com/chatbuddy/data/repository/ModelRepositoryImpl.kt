package com.chatbuddy.data.repository

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.workDataOf
import com.chatbuddy.data.download.DownloadWorker
import com.chatbuddy.data.download.DownloadWorkNames
import com.chatbuddy.data.download.ModelStateStore
import com.chatbuddy.data.download.ResumableDownloadManager
import com.chatbuddy.data.download.SafModelStore
import com.chatbuddy.data.model.ModelManifestDataSource
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.ModelCacheStatus
import com.chatbuddy.domain.model.ModelState
import com.chatbuddy.domain.model.ModelStatus
import com.chatbuddy.domain.repository.ModelRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepositoryImpl @Inject constructor(
    private val manifest: ModelManifestDataSource,
    private val stateStore: ModelStateStore,
    private val safStore: SafModelStore,
    private val manager: ResumableDownloadManager,
    private val runtimeCache: com.chatbuddy.data.download.ModelRuntimeCache,
    private val workManager: WorkManager
) : ModelRepository {
    private val workMutex = Mutex()

    override fun hasStorageFolder(): Boolean = safStore.hasValidTree()

    override suspend fun validateStorage(): AppResult<Unit> = withContext(Dispatchers.IO) {
        safStore.validateTree()
    }

    override suspend fun listArtifacts(): AppResult<List<ModelArtifact>> =
        runCatching { manifest.read() }
            .fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Error("Model manifest could not be read", it) }
            )

    override suspend fun inspectCache(): AppResult<List<ModelCacheStatus>> =
        withContext(Dispatchers.IO) {
            runCatching { manifest.read().map(runtimeCache::inspect) }
                .fold(
                    onSuccess = { AppResult.Success(it) },
                    onFailure = { AppResult.Error("Model cache status is unavailable", it) }
                )
        }

    override fun observeStates(): Flow<List<ModelState>> = stateStore.states

    override suspend fun selectStorageFolder(treeUri: String): AppResult<Unit> =
        safStore.selectTree(android.net.Uri.parse(treeUri))

    override suspend fun download(artifactId: String): AppResult<Unit> = workMutex.withLock {
        val artifact = stateStore.find(artifactId)
            ?: return@withLock AppResult.Error("Unknown model artifact")
        if (artifact.storageKind != com.chatbuddy.domain.model.ModelStorageKind.SAF_PERSISTENT) {
            val message = "This model is not available as a SAF download"
            stateStore.update(artifact.id, ModelStatus.Error(message))
            return@withLock AppResult.Error(message)
        }

        when (val storage = safStore.validateTree()) {
            is AppResult.Error -> {
                stateStore.update(artifact.id, ModelStatus.Error(storage.message))
                return@withLock AppResult.Error(storage.message, storage.cause)
            }

            is AppResult.Success -> Unit
            AppResult.Loading -> return@withLock AppResult.Error("SAF storage validation is still running")
        }

        val uniqueName = DownloadWorkNames.forArtifact(artifact.id)
        val existingWork = try {
            withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(uniqueName).get()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            return@withLock AppResult.Error("Unable to inspect model download", error)
        }
        if (existingWork.any { work ->
                work.state == WorkInfo.State.ENQUEUED ||
                    work.state == WorkInfo.State.RUNNING ||
                    work.state == WorkInfo.State.BLOCKED
            }
        ) {
            return@withLock AppResult.Success(Unit)
        }

        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_ARTIFACT_ID to artifact.id))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val resumeOffset = safStore.checkpoint(artifact)
            .coerceIn(0L, artifact.sizeBytes)
        stateStore.beginDownload(
            id = artifact.id,
            totalBytes = artifact.sizeBytes,
            downloadedBytes = resumeOffset
        )
        return@withLock runCatching {
            // KEEP prevents a second tap/process from replacing an active worker.
            // A completed or cancelled unique work item is eligible for a new run.
            workManager.enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.KEEP,
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

    override suspend fun pause(artifactId: String): AppResult<Unit> = workMutex.withLock {
        if (stateStore.find(artifactId) == null) {
            return@withLock AppResult.Error("Unknown model artifact")
        }
        if (!stateStore.requestPause(artifactId)) {
            return@withLock AppResult.Success(Unit)
        }
        return@withLock runCatching {
            workManager.cancelUniqueWork(DownloadWorkNames.forArtifact(artifactId))
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { error ->
                AppResult.Error("Unable to pause model download", error)
            }
        )
    }

    override suspend fun verify(artifactId: String): AppResult<Unit> {
        val artifact = stateStore.find(artifactId)
            ?: return AppResult.Error("Unknown model artifact")
        return manager.verify(artifact)
    }
}
