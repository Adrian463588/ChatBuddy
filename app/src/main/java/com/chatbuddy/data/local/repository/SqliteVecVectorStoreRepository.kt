package com.chatbuddy.data.local.repository

import androidx.sqlite.db.SupportSQLiteDatabase
import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.data.local.database.DocumentChunkWithDocument
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentChunk
import com.chatbuddy.domain.model.DocumentId
import com.chatbuddy.domain.model.Evidence
import com.chatbuddy.domain.repository.VectorStoreRepository
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SqliteVecVectorStoreRepository @Inject constructor(
    private val database: AppDatabase
) : VectorStoreRepository {
    @Volatile
    private var backend = Backend.UNINITIALIZED

    override suspend fun insert(chunk: DocumentChunk, embedding: FloatArray): AppResult<Unit> {
        if (embedding.size != EMBEDDING_DIMENSIONS) {
            return AppResult.Error("Embedding must have $EMBEDDING_DIMENSIONS dimensions")
        }
        val databaseId = chunk.databaseId
            ?: return AppResult.Error("Chunk database id is required")
        val db = database.openHelper.writableDatabase
        return when (ensureBackend(db)) {
            Backend.SQLITE_VEC -> try {
                db.execSQL(
                    "INSERT OR REPLACE INTO chatbuddy_vectors(rowid, embedding) VALUES (?, ?)",
                    arrayOf(databaseId, embedding.toBlob())
                )
                AppResult.Success(Unit)
            } catch (error: Exception) {
                backend = Backend.ROOM_EXACT
                persistExact(databaseId, embedding, error)
            }

            Backend.ROOM_EXACT -> persistExact(databaseId, embedding, null)
            Backend.UNINITIALIZED -> AppResult.Error("Local vector backend could not initialize")
        }
    }

    override suspend fun search(embedding: FloatArray, limit: Int): AppResult<List<Evidence>> {
        if (limit <= 0) return AppResult.Error("Retrieval limit must be positive")
        if (embedding.size != EMBEDDING_DIMENSIONS) {
            return AppResult.Error("Embedding must have $EMBEDDING_DIMENSIONS dimensions")
        }
        val db = database.openHelper.readableDatabase
        return when (ensureBackend(db)) {
            Backend.SQLITE_VEC -> try {
                AppResult.Success(searchSqliteVec(db, embedding, limit))
            } catch (error: Exception) {
                backend = Backend.ROOM_EXACT
                searchExact(embedding, limit, error)
            }

            Backend.ROOM_EXACT -> searchExact(embedding, limit, null)
            Backend.UNINITIALIZED -> AppResult.Error("Local vector backend could not initialize")
        }
    }

    private fun ensureBackend(db: SupportSQLiteDatabase): Backend {
        if (backend != Backend.UNINITIALIZED) return backend
        synchronized(this) {
            if (backend != Backend.UNINITIALIZED) return backend
            backend = try {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS chatbuddy_vectors " +
                        "USING vec0(embedding float[$EMBEDDING_DIMENSIONS] distance_metric=cosine)"
                )
                Backend.SQLITE_VEC
            } catch (_: Exception) {
                // Android's system SQLite does not ship sqlite-vec. The exact
                // SQLite BLOB path keeps indexed user data usable until the
                // extension is linked into the native build.
                Backend.ROOM_EXACT
            }
            return backend
        }
    }

    private suspend fun persistExact(
        databaseId: Long,
        embedding: FloatArray,
        previousError: Exception?
    ): AppResult<Unit> = try {
        database.documentDao().updateEmbedding(databaseId, embedding.toBlob())
        AppResult.Success(Unit)
    } catch (error: Exception) {
        AppResult.Error("Local vector storage write failed", previousError ?: error)
    }

    private suspend fun searchSqliteVec(
        db: SupportSQLiteDatabase,
        embedding: FloatArray,
        limit: Int
    ): List<Evidence> {
        val matches = db.query(
            "SELECT rowid, distance FROM chatbuddy_vectors " +
                "WHERE embedding MATCH ? AND k = ? ORDER BY distance",
            arrayOf(embedding.toBlob(), limit.toString())
        ).use { cursor ->
            val rowIdIndex = cursor.getColumnIndex("rowid")
            val distanceIndex = cursor.getColumnIndex("distance")
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.getLong(rowIdIndex) to cursor.getFloat(distanceIndex))
                }
            }
        }
        if (matches.isEmpty()) return emptyList()
        val chunks = database.documentDao()
            .findChunksWithDocuments(matches.map { it.first })
            .associateBy { it.chunk.id }
        return matches.mapNotNull { (id, distance) ->
            chunks[id]?.toEvidence((1f - distance).coerceIn(-1f, 1f))
        }
    }

    private suspend fun searchExact(
        query: FloatArray,
        limit: Int,
        previousError: Exception?
    ): AppResult<List<Evidence>> = try {
        val rows = database.documentDao().findEmbeddedVectors()
        val ranked = withContext(Dispatchers.Default) {
            rows.asSequence()
                .mapNotNull { row ->
                    row.embedding.toFloats()?.let { vector ->
                        row.id to cosine(query, vector)
                    }
                }
                .sortedByDescending { it.second }
                .take(limit)
                .toList()
        }
        if (ranked.isEmpty()) {
            AppResult.Success(emptyList())
        } else {
            val chunks = database.documentDao()
                .findChunksWithDocuments(ranked.map { it.first })
                .associateBy { it.chunk.id }
            AppResult.Success(
                ranked.mapNotNull { (id, score) -> chunks[id]?.toEvidence(score) }
            )
        }
    } catch (error: Exception) {
        AppResult.Error("Local vector retrieval failed", previousError ?: error)
    }

    private fun DocumentChunkWithDocument.toEvidence(score: Float): Evidence = Evidence(
        documentId = DocumentId(chunk.documentId),
        documentName = documentName,
        chunkOrdinal = chunk.ordinal,
        text = chunk.text,
        score = score.coerceIn(-1f, 1f)
    )

    private fun ByteArray.toFloats(): FloatArray? {
        if (size != EMBEDDING_DIMENSIONS * Float.SIZE_BYTES) return null
        val buffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(EMBEDDING_DIMENSIONS) { buffer.float }
    }

    private fun FloatArray.toBlob(): ByteArray = ByteBuffer
        .allocate(size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .also { buffer -> forEach(buffer::putFloat) }
        .array()

    private fun cosine(left: FloatArray, right: FloatArray): Float {
        if (left.size != right.size) return -1f
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        left.indices.forEach { index ->
            val leftValue = left[index].toDouble()
            val rightValue = right[index].toDouble()
            dot += leftValue * rightValue
            leftNorm += leftValue * leftValue
            rightNorm += rightValue * rightValue
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return -1f
        return (dot / (kotlin.math.sqrt(leftNorm) * kotlin.math.sqrt(rightNorm)))
            .toFloat()
            .coerceIn(-1f, 1f)
    }

    private enum class Backend {
        UNINITIALIZED,
        SQLITE_VEC,
        ROOM_EXACT
    }

    companion object {
        private const val EMBEDDING_DIMENSIONS = 384
    }
}
