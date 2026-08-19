package com.chatbuddy.data.local.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.data.local.database.DocumentChunkEntity
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentChunk
import com.chatbuddy.domain.model.Evidence
import com.chatbuddy.domain.repository.VectorStoreRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SqliteVecVectorStoreRepository @Inject constructor(
    private val database: AppDatabase
) : VectorStoreRepository {
    private var initialized = false
    private var initializationError: String? = null

    override suspend fun insert(chunk: DocumentChunk, embedding: FloatArray): AppResult<Unit> {
        val db = database.openHelper.writableDatabase
        val ready = ensureInitialized(db)
        if (ready is AppResult.Error) return ready
        if (embedding.size != EMBEDDING_DIMENSIONS) {
            return AppResult.Error("Embedding must have $EMBEDDING_DIMENSIONS dimensions")
        }
        val databaseId = chunk.databaseId
            ?: return AppResult.Error("Chunk database id is required")
        return try {
            db.execSQL(
                "INSERT OR REPLACE INTO chatbuddy_vectors(rowid, embedding) VALUES (?, ?)",
                arrayOf(databaseId, embedding.toBlob())
            )
            AppResult.Success(Unit)
        } catch (error: Exception) {
            AppResult.Error("sqlite-vec insert failed", error)
        }
    }

    override suspend fun search(embedding: FloatArray, limit: Int): AppResult<List<Evidence>> {
        if (limit <= 0) return AppResult.Error("Retrieval limit must be positive")
        val db = database.openHelper.readableDatabase
        val ready = ensureInitialized(db)
        if (ready is AppResult.Error) return ready
        if (embedding.size != EMBEDDING_DIMENSIONS) {
            return AppResult.Error("Embedding must have $EMBEDDING_DIMENSIONS dimensions")
        }
        return try {
            db.query(
                "SELECT rowid, distance FROM chatbuddy_vectors WHERE embedding MATCH ? AND k = ? ORDER BY distance",
                arrayOf(embedding.toBlob(), limit.toString())
            ).use { cursor ->
                val matches = buildList {
                    val rowIdIndex = cursor.getColumnIndex("rowid")
                    val distanceIndex = cursor.getColumnIndex("distance")
                    while (cursor.moveToNext()) {
                        add(cursor.getLong(rowIdIndex) to cursor.getFloat(distanceIndex))
                    }
                }
                val chunks = database.documentDao().findChunks(matches.map { it.first })
                    .associateBy { it.id }
                AppResult.Success(matches.mapNotNull { (id, distance) ->
                    chunks[id]?.toEvidence(distance)
                })
            }
        } catch (error: Exception) {
            AppResult.Error("sqlite-vec query failed", error)
        }
    }

    private fun ensureInitialized(db: SupportSQLiteDatabase): AppResult<Unit> {
        initializationError?.let { return AppResult.Error(it) }
        if (initialized) return AppResult.Success(Unit)
        return try {
            db.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS chatbuddy_vectors USING vec0(embedding float[$EMBEDDING_DIMENSIONS])"
            )
            initialized = true
            AppResult.Success(Unit)
        } catch (error: Exception) {
            val message = "sqlite-vec extension is unavailable; vector retrieval is disabled"
            initializationError = message
            AppResult.Error(message, error)
        }
    }

    private fun DocumentChunkEntity.toEvidence(distance: Float): Evidence = Evidence(
        documentId = com.chatbuddy.domain.model.DocumentId(documentId),
        documentName = documentId,
        chunkOrdinal = ordinal,
        text = text,
        score = 1f - distance
    )

    private fun FloatArray.toBlob(): ByteArray = ByteBuffer
        .allocate(size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .also { buffer -> forEach(buffer::putFloat) }
        .array()

    companion object {
        private const val EMBEDDING_DIMENSIONS = 384
    }
}
