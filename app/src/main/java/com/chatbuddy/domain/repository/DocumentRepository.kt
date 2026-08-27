package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentFolderIndexSummary
import com.chatbuddy.domain.model.DocumentRecord
import kotlinx.coroutines.flow.Flow

interface DocumentRepository {
    fun observeDocuments(): Flow<List<DocumentRecord>>
    suspend fun addDocument(uri: String): AppResult<DocumentRecord>
    suspend fun addFolder(uri: String): AppResult<DocumentFolderIndexSummary>
    suspend fun deleteDocument(id: String): AppResult<Unit>
}
