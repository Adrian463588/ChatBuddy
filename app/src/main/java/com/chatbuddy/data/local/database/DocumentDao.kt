package com.chatbuddy.data.local.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY displayName COLLATE NOCASE")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Insert
    suspend fun insertDocument(document: DocumentEntity)

    @Insert
    suspend fun insertChunk(chunk: DocumentChunkEntity): Long

    @Query("UPDATE documents SET chunkCount = :chunkCount, indexed = :indexed WHERE id = :documentId")
    suspend fun updateIndexState(documentId: String, chunkCount: Int, indexed: Boolean)

    @Query("SELECT * FROM document_chunks WHERE id IN (:ids)")
    suspend fun findChunks(ids: List<Long>): List<DocumentChunkEntity>

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)

    @Query("DELETE FROM document_chunks WHERE documentId = :documentId")
    suspend fun deleteChunks(documentId: String)
}
