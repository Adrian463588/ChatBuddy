package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult

interface EmbeddingRepository {
    suspend fun embed(text: String): AppResult<FloatArray>
}
