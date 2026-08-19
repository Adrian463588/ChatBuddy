package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentChunk
import com.chatbuddy.domain.model.Evidence

interface VectorStoreRepository {
    suspend fun insert(chunk: DocumentChunk, embedding: FloatArray): AppResult<Unit>
    suspend fun search(embedding: FloatArray, limit: Int): AppResult<List<Evidence>>
}
