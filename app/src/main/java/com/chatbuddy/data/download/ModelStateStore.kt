package com.chatbuddy.data.download

import com.chatbuddy.domain.model.ModelArtifact
import com.chatbuddy.domain.model.ModelState
import com.chatbuddy.domain.model.ModelStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelStateStore @Inject constructor(
    manifest: com.chatbuddy.data.model.ModelManifestDataSource
) {
    private val _states = MutableStateFlow(
        manifest.read().map { ModelState(it, ModelStatus.NotInstalled) }
    )
    val states: StateFlow<List<ModelState>> = _states.asStateFlow()

    fun update(id: String, status: ModelStatus) {
        _states.update { states ->
            states.map { state -> if (state.artifact.id == id) state.copy(status = status) else state }
        }
    }

    fun find(id: String): ModelArtifact? = _states.value
        .firstOrNull { it.artifact.id == id }
        ?.artifact

    fun markPaused(id: String) {
        _states.update { states ->
            states.map { state ->
                if (state.artifact.id != id) return@map state
                val status = state.status
                if (status is ModelStatus.Downloading) {
                    state.copy(status = ModelStatus.Paused(status.downloadedBytes, status.totalBytes))
                } else state
            }
        }
    }
}
