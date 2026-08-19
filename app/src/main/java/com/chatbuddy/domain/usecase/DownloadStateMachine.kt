package com.chatbuddy.domain.usecase

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data class Paused(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data class Verifying(val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data object Complete : DownloadState
    data class Failed(val message: String) : DownloadState
}

sealed interface DownloadAction {
    data class Start(val offset: Long) : DownloadAction
    data object Pause : DownloadAction
    data class Progress(val downloadedBytes: Long) : DownloadAction
    data object Verify : DownloadAction
    data object Verified : DownloadAction
    data class Error(val message: String) : DownloadAction
}

class DownloadStateMachine(private val totalBytes: Long) {
    init {
        require(totalBytes > 0) { "totalBytes must be positive" }
    }

    fun reduce(state: DownloadState, action: DownloadAction): DownloadState = when (action) {
        is DownloadAction.Start -> {
            require(action.offset in 0..totalBytes) { "offset is outside artifact size" }
            DownloadState.Downloading(action.offset, totalBytes)
        }
        DownloadAction.Pause -> when (state) {
            is DownloadState.Downloading -> DownloadState.Paused(state.downloadedBytes, totalBytes)
            else -> state
        }
        is DownloadAction.Progress -> when (state) {
            is DownloadState.Downloading -> {
                require(action.downloadedBytes in state.downloadedBytes..totalBytes)
                DownloadState.Downloading(action.downloadedBytes, totalBytes)
            }
            else -> state
        }
        DownloadAction.Verify -> when (state) {
            is DownloadState.Downloading -> DownloadState.Verifying(state.downloadedBytes, totalBytes)
            is DownloadState.Paused -> DownloadState.Verifying(state.downloadedBytes, totalBytes)
            else -> state
        }
        DownloadAction.Verified -> when (state) {
            is DownloadState.Verifying -> {
                require(state.downloadedBytes == totalBytes) { "cannot complete before all bytes arrive" }
                DownloadState.Complete
            }
            else -> state
        }
        is DownloadAction.Error -> DownloadState.Failed(action.message)
    }
}
