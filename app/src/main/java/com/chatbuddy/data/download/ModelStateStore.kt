package com.chatbuddy.data.download

import android.content.Context
import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.ModelState
import com.chatbuddy.domain.model.ModelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelStateStore @Inject constructor(
    manifest: com.chatbuddy.data.model.ModelManifestDataSource,
    @ApplicationContext context: Context
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val lock = Any()
    private val artifacts = manifest.read()
    private val _states = MutableStateFlow(
        artifacts.map { artifact -> ModelState(artifact, restoreStatus(artifact)) }
    )
    val states: StateFlow<List<ModelState>> = _states.asStateFlow()

    fun update(id: String, status: ModelStatus) {
        synchronized(lock) {
            if (isPauseRequestedLocked(id) && status.isDownloadMutation()) return
            val current = _states.value
            val updated = current.map { state ->
                if (state.artifact.id == id) state.copy(status = status) else state
            }
            if (updated == current) return
            _states.value = updated
            persistStatus(id, status)
            if (status is ModelStatus.Ready) {
                preferences.edit().remove(pauseKey(id)).apply()
            }
        }
    }

    fun find(id: String): ModelArtifact? = _states.value
        .firstOrNull { it.artifact.id == id }
        ?.artifact

    /** Clears a previous pause request and records that a new work item is desired. */
    fun beginDownload(id: String, totalBytes: Long, downloadedBytes: Long = 0L) {
        synchronized(lock) {
            preferences.edit().remove(pauseKey(id)).apply()
            setStatusLocked(
                id,
                ModelStatus.Queued(
                    totalBytes = totalBytes,
                    downloadedBytes = downloadedBytes.coerceIn(0L, totalBytes)
                )
            )
        }
    }

    /**
     * Marks the current work as paused before WorkManager cancellation. The pause
     * flag prevents a racing worker callback from changing the visible state back
     * to Downloading or Queued.
     */
    fun requestPause(id: String): Boolean {
        synchronized(lock) {
            val state = _states.value.firstOrNull { it.artifact.id == id } ?: return false
            val paused = when (val status = state.status) {
                is ModelStatus.Downloading -> ModelStatus.Paused(status.downloadedBytes, status.totalBytes)
                is ModelStatus.Queued -> ModelStatus.Paused(status.downloadedBytes, status.totalBytes)
                is ModelStatus.Verifying -> ModelStatus.Paused(status.downloadedBytes, status.totalBytes)
                else -> return false
            }
            preferences.edit().putBoolean(pauseKey(id), true).apply()
            setStatusLocked(id, paused)
            return true
        }
    }

    fun markPaused(id: String) {
        requestPause(id)
    }

    fun isPauseRequested(id: String): Boolean = synchronized(lock) {
        isPauseRequestedLocked(id)
    }

    private fun setStatusLocked(id: String, status: ModelStatus) {
        val current = _states.value
        val updated = current.map { state ->
            if (state.artifact.id == id) state.copy(status = status) else state
        }
        if (updated == current) return
        _states.value = updated
        persistStatus(id, status)
    }

    private fun persistStatus(id: String, status: ModelStatus) {
        val artifact = find(id) ?: return
        val editor = preferences.edit()
            .putString(statusKey(id), status.nameForPersistence())
            .putString(fingerprintKey(id), artifactFingerprint(artifact))
        when (status) {
            is ModelStatus.Queued -> editor.putLong(downloadedKey(id), status.downloadedBytes)
                .putLong(totalKey(id), status.totalBytes)
            is ModelStatus.Downloading -> editor.putLong(downloadedKey(id), status.downloadedBytes)
                .putLong(totalKey(id), status.totalBytes)
            is ModelStatus.Paused -> editor.putLong(downloadedKey(id), status.downloadedBytes)
                .putLong(totalKey(id), status.totalBytes)
            is ModelStatus.Verifying -> editor.putLong(downloadedKey(id), status.downloadedBytes)
                .putLong(totalKey(id), status.totalBytes)
            is ModelStatus.Ready -> editor.putString(storageKindKey(id), status.storageKind.name)
            is ModelStatus.Error -> editor.putString(errorKey(id), status.message)
            ModelStatus.NotInstalled, ModelStatus.Unavailable -> Unit
        }
        editor.apply()
    }

    private fun restoreStatus(artifact: ModelArtifact): ModelStatus {
        if (artifact.storageKind == com.chatbuddy.domain.model.ModelStorageKind.UNAVAILABLE) {
            return ModelStatus.Unavailable
        }
        if (preferences.getString(fingerprintKey(artifact.id), null) != artifactFingerprint(artifact)) {
            return ModelStatus.NotInstalled
        }
        val persisted = preferences.getString(statusKey(artifact.id), null) ?: return ModelStatus.NotInstalled
        val total = preferences.getLong(totalKey(artifact.id), artifact.sizeBytes)
        val downloaded = preferences.getLong(downloadedKey(artifact.id), 0L)
        val validProgress = total == artifact.sizeBytes && downloaded in 0L..total
        return when (persisted) {
            STATUS_NOT_INSTALLED -> ModelStatus.NotInstalled
            STATUS_UNAVAILABLE -> ModelStatus.Unavailable
            STATUS_QUEUED -> if (validProgress) ModelStatus.Queued(total, downloaded) else ModelStatus.NotInstalled
            // A process may die while a worker is active. It is not safe to claim
            // that the worker is still running, so expose its durable checkpoint as
            // Resume/Pause state until WorkManager reports progress again.
            STATUS_DOWNLOADING, STATUS_VERIFYING ->
                if (validProgress) ModelStatus.Paused(downloaded, total) else ModelStatus.NotInstalled
            STATUS_PAUSED -> if (validProgress) ModelStatus.Paused(downloaded, total) else ModelStatus.NotInstalled
            STATUS_READY -> ModelStatus.Ready(artifact.storageKind)
            STATUS_ERROR -> preferences.getString(errorKey(artifact.id), null)
                ?.takeIf { it.isNotBlank() }
                ?.let(ModelStatus::Error)
                ?: ModelStatus.NotInstalled
            else -> ModelStatus.NotInstalled
        }
    }

    private fun isPauseRequestedLocked(id: String): Boolean =
        preferences.getBoolean(pauseKey(id), false)

    private fun ModelStatus.isDownloadMutation(): Boolean = when (this) {
        is ModelStatus.Queued,
        is ModelStatus.Downloading,
        is ModelStatus.Verifying -> true
        else -> false
    }

    private fun ModelStatus.nameForPersistence(): String = when (this) {
        ModelStatus.NotInstalled -> STATUS_NOT_INSTALLED
        ModelStatus.Unavailable -> STATUS_UNAVAILABLE
        is ModelStatus.Queued -> STATUS_QUEUED
        is ModelStatus.Downloading -> STATUS_DOWNLOADING
        is ModelStatus.Paused -> STATUS_PAUSED
        is ModelStatus.Verifying -> STATUS_VERIFYING
        is ModelStatus.Ready -> STATUS_READY
        is ModelStatus.Error -> STATUS_ERROR
    }

    private fun statusKey(id: String): String = "status_$id"
    private fun fingerprintKey(id: String): String = "fingerprint_$id"
    private fun downloadedKey(id: String): String = "downloaded_$id"
    private fun totalKey(id: String): String = "total_$id"
    private fun storageKindKey(id: String): String = "storage_kind_$id"
    private fun errorKey(id: String): String = "error_$id"
    private fun pauseKey(id: String): String = "pause_requested_$id"

    private fun artifactFingerprint(artifact: ModelArtifact): String =
        "${artifact.revision}:${artifact.sha256.lowercase(java.util.Locale.US)}:${artifact.sizeBytes}"

    companion object {
        private const val PREFERENCES = "chatbuddy_model_states"
        private const val STATUS_NOT_INSTALLED = "not_installed"
        private const val STATUS_UNAVAILABLE = "unavailable"
        private const val STATUS_QUEUED = "queued"
        private const val STATUS_DOWNLOADING = "downloading"
        private const val STATUS_PAUSED = "paused"
        private const val STATUS_VERIFYING = "verifying"
        private const val STATUS_READY = "ready"
        private const val STATUS_ERROR = "error"
    }
}
