package com.chatbuddy.ai.embedding

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.repository.EmbeddingRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnavailableEmbeddingRepository @Inject constructor() : EmbeddingRepository {
    override suspend fun embed(text: String): AppResult<FloatArray> =
        AppResult.Error("Embedding runtime is unavailable until tokenizer and SAF model are installed")
}
