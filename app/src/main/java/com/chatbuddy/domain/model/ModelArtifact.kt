package com.chatbuddy.domain.model

enum class ModelStorageKind {
    SAF_PERSISTENT,
    APK_BUNDLED,
    PLAY_SERVICES_MANAGED,
    UNAVAILABLE
}

data class ModelArtifact(
    val id: String,
    val displayName: String,
    val url: String,
    val revision: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val abi: Set<String>,
    val storageKind: ModelStorageKind
)

sealed interface ModelStatus {
    data object Unavailable : ModelStatus
    data object NotInstalled : ModelStatus
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelStatus
    data class Paused(val downloadedBytes: Long, val totalBytes: Long) : ModelStatus
    data class Verifying(val downloadedBytes: Long, val totalBytes: Long) : ModelStatus
    data class Ready(val storageKind: ModelStorageKind) : ModelStatus
    data class Error(val message: String) : ModelStatus
}

data class ModelState(
    val artifact: ModelArtifact,
    val status: ModelStatus
)
