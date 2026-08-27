package com.chatbuddy.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY displayName COLLATE NOCASE")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :documentId LIMIT 1")
    suspend fun findDocument(documentId: String): DocumentEntity?

    @Insert
    suspend fun insertDocument(document: DocumentEntity)

    @Insert
    suspend fun insertChunk(chunk: DocumentChunkEntity): Long

    @Query("UPDATE documents SET chunkCount = :chunkCount, indexed = :indexed WHERE id = :documentId")
    suspend fun updateIndexState(documentId: String, chunkCount: Int, indexed: Boolean)

    @Query("SELECT * FROM document_chunks WHERE id IN (:ids)")
    suspend fun findChunks(ids: List<Long>): List<DocumentChunkEntity>

    @Query("UPDATE document_chunks SET embedding = :embedding WHERE id = :id")
    suspend fun updateEmbedding(id: Long, embedding: ByteArray)

    @Query(
        """
        SELECT * FROM document_chunks
        WHERE documentId = :documentId
          AND (embedding IS NULL OR length(embedding) != 1536)
        ORDER BY ordinal ASC
        LIMIT 1
        """
    )
    suspend fun findFirstIncompleteChunk(documentId: String): DocumentChunkEntity?

    @Query(
        """
        SELECT * FROM document_chunks
        WHERE documentId = :documentId
          AND length(embedding) = 1536
        ORDER BY ordinal DESC
        LIMIT 1
        """
    )
    suspend fun findLastCompleteChunk(documentId: String): DocumentChunkEntity?

    @Query(
        """
        SELECT * FROM document_chunks
        WHERE documentId = :documentId
        ORDER BY ordinal DESC
        LIMIT 1
        """
    )
    suspend fun findLastChunk(documentId: String): DocumentChunkEntity?

    @Query(
        """
        SELECT c.*, d.displayName AS documentName
        FROM document_chunks AS c
        INNER JOIN documents AS d ON d.id = c.documentId
        WHERE c.id IN (:ids)
        """
    )
    suspend fun findChunksWithDocuments(ids: List<Long>): List<DocumentChunkWithDocument>

    @Query(
        """
        SELECT id, documentId, ordinal, embedding
        FROM document_chunks
        WHERE embedding IS NOT NULL
          AND length(embedding) = 1536
          AND id > :afterId
        ORDER BY id ASC
        LIMIT :limit
        """
    )
    suspend fun findEmbeddedVectorsAfter(
        afterId: Long,
        limit: Int
    ): List<DocumentVectorRow>

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String)

    @Query("DELETE FROM document_chunks WHERE documentId = :documentId")
    suspend fun deleteChunks(documentId: String): Int

    @Query(
        "DELETE FROM document_chunks WHERE documentId = :documentId AND ordinal >= :fromOrdinal"
    )
    suspend fun deleteChunksFromOrdinal(documentId: String, fromOrdinal: Int): Int
}
