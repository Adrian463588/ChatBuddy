package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentChunk
import com.chatbuddy.domain.model.DocumentId
import com.chatbuddy.domain.model.Evidence

enum class VectorBackendStatus {
    SQLITE_VEC,
    ROOM_EXACT_DEGRADED,
    UNAVAILABLE
}

data class VectorStoreStatus(
    val backend: VectorBackendStatus,
    val detail: String
)

interface VectorStoreRepository {
    suspend fun insert(chunk: DocumentChunk, embedding: FloatArray): AppResult<Unit>
    suspend fun search(embedding: FloatArray, limit: Int): AppResult<List<Evidence>>

    /**
     * Exposes the backend actually serving queries. Implementations that do
     * not expose a vector backend fail closed instead of implying support.
     */
    suspend fun getBackendStatus(): AppResult<VectorStoreStatus> =
        AppResult.Success(
            VectorStoreStatus(
                backend = VectorBackendStatus.UNAVAILABLE,
                detail = "Vector backend status is unavailable"
            )
        )

    /**
     * Removes vector rows before their owning document chunks are deleted.
     * The default is fail-closed so an implementation cannot silently orphan
     * an external vector index.
     */
    suspend fun deleteDocumentVectors(documentId: DocumentId): AppResult<Unit> =
        AppResult.Error("Vector backend does not support document deletion")

    /**
     * Removes a resumable-index tail while preserving completed chunks.
     */
    suspend fun deleteDocumentVectorsFromOrdinal(
        documentId: DocumentId,
        fromOrdinal: Int
    ): AppResult<Unit> = AppResult.Error("Vector backend does not support resumable cleanup")
}
