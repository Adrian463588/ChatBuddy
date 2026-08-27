package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.TranslationHistoryEntry
import kotlinx.coroutines.flow.Flow

interface TranslationHistoryRepository {
    fun observeRecent(limit: Int = 20): Flow<List<TranslationHistoryEntry>>
    suspend fun add(entry: TranslationHistoryEntry): AppResult<Unit>
    suspend fun clear(): AppResult<Unit>
}
