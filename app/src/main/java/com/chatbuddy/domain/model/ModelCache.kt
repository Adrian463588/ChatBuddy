package com.chatbuddy.domain.model

enum class ModelCacheState {
    HIT,
    MISS,
    UNAVAILABLE
}

data class ModelCacheStatus(
    val artifactId: String,
    val displayName: String,
    val state: ModelCacheState,
    val detail: String
)
