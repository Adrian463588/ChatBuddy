package com.chatbuddy.data.local.repository

import androidx.room.PooledConnection
import androidx.room.immediateTransaction
import androidx.room.useReaderConnection
import androidx.room.useWriterConnection
import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.data.local.database.DocumentChunkWithDocument
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentChunk
import com.chatbuddy.domain.model.DocumentId
import com.chatbuddy.domain.model.Evidence
import com.chatbuddy.domain.repository.VectorBackendStatus
import com.chatbuddy.domain.repository.VectorStoreRepository
import com.chatbuddy.domain.repository.VectorStoreStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SqliteVecVectorStoreRepository @Inject constructor(
    private val database: AppDatabase
) : VectorStoreRepository {
    @Volatile
    private var backend = Backend.UNINITIALIZED

    @Volatile
    private var backendDetail = "Vector backend has not been probed"

    private val backendMutex = Mutex()

    override suspend fun getBackendStatus(): AppResult<VectorStoreStatus> =
        withContext(Dispatchers.IO) {
            try {
                database.useWriterConnection { connection -> ensureBackend(connection) }
                AppResult.Success(statusSnapshot())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                markUnavailable(error)
                AppResult.Success(statusSnapshot())
            }
        }

    override suspend fun insert(
        chunk: DocumentChunk,
        embedding: FloatArray
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        if (embedding.size != EMBEDDING_DIMENSIONS) {
            return@withContext AppResult.Error(
                "Embedding must have $EMBEDDING_DIMENSIONS dimensions"
            )
        }
        if (embedding.any { !it.isFinite() }) {
            return@withContext AppResult.Error("Embedding contains a non-finite value")
        }
        val databaseId = chunk.databaseId
            ?: return@withContext AppResult.Error("Chunk database id is required")
        val blob = embedding.toBlob()

        try {
            database.useWriterConnection { connection ->
                when (ensureBackend(connection)) {
                    Backend.SQLITE_VEC -> {
                        try {
                            connection.immediateTransaction {
                                writeExact(this, databaseId, blob)
                                writeSqliteVec(this, databaseId, blob)
                            }
                            AppResult.Success(Unit)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            downgradeToRoomExact()
                            // The failed transaction rolled back both writes. Keep the
                            // real embedding usable through the bounded Room backend.
                            persistExact(connection, databaseId, blob)
                        }
                    }

                    Backend.ROOM_EXACT -> persistExact(connection, databaseId, blob)
                    Backend.UNAVAILABLE,
                    Backend.UNINITIALIZED -> AppResult.Error(
                        "Local vector backend is unavailable"
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppResult.Error("Local vector storage write failed", error)
        }
    }

    override suspend fun search(
        embedding: FloatArray,
        limit: Int
    ): AppResult<List<Evidence>> = withContext(Dispatchers.IO) {
        if (limit !in 1..MAX_RETRIEVAL_LIMIT) {
            return@withContext AppResult.Error(
                "Retrieval limit must be between 1 and $MAX_RETRIEVAL_LIMIT"
            )
        }
        if (embedding.size != EMBEDDING_DIMENSIONS) {
            return@withContext AppResult.Error(
                "Embedding must have $EMBEDDING_DIMENSIONS dimensions"
            )
        }
        if (embedding.any { !it.isFinite() }) {
            return@withContext AppResult.Error("Embedding contains a non-finite value")
        }

        val currentBackend = try {
            database.useWriterConnection { connection -> ensureBackend(connection) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            markUnavailable(error)
            return@withContext AppResult.Error("Local vector database is unavailable", error)
        }

        when (currentBackend) {
            Backend.SQLITE_VEC -> try {
                val matches = database.useReaderConnection { connection ->
                    searchSqliteVec(connection, embedding, limit)
                }
                if (matches.isEmpty()) {
                    AppResult.Success(emptyList())
                } else {
                    val chunks = database.documentDao()
                        .findChunksWithDocuments(matches.map { it.first })
                        .associateBy { it.chunk.id }
                    AppResult.Success(
                        matches.mapNotNull { (id, distance) ->
                            chunks[id]?.toEvidence((1f - distance).coerceIn(-1f, 1f))
                        }
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                downgradeToRoomExact()
                searchExact(embedding, limit, error)
            }

            Backend.ROOM_EXACT -> searchExact(embedding, limit, null)
            Backend.UNAVAILABLE,
            Backend.UNINITIALIZED -> AppResult.Error("Local vector backend is unavailable")
        }
    }

    override suspend fun deleteDocumentVectors(documentId: DocumentId): AppResult<Unit> =
        deleteDocumentVectorsFromOrdinal(documentId, fromOrdinal = 0)

    override suspend fun deleteDocumentVectorsFromOrdinal(
        documentId: DocumentId,
        fromOrdinal: Int
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        if (fromOrdinal < 0) {
            return@withContext AppResult.Error("Vector cleanup ordinal must not be negative")
        }
        try {
            database.useWriterConnection { connection ->
                when (ensureBackend(connection)) {
                    Backend.SQLITE_VEC,
                    Backend.ROOM_EXACT -> {
                        deleteLegacyOrSqliteRows(connection, documentId, fromOrdinal)
                        AppResult.Success(Unit)
                    }

                    Backend.UNAVAILABLE,
                    Backend.UNINITIALIZED -> AppResult.Error(
                        "Local vector backend is unavailable"
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppResult.Error(
                "Vector cleanup could not be verified; document data was kept",
                error
            )
        }
    }

    /**
     * Room 2.7 databases configured with a SQLiteDriver do not expose a
     * SupportSQLiteOpenHelper. Raw vector SQL therefore uses Room's public
     * driver connection API, which also adapts the in-memory SupportSQLite
     * driver used by instrumentation tests.
     */
    private suspend fun ensureBackend(connection: PooledConnection): Backend {
        backend.takeIf { it != Backend.UNINITIALIZED }?.let { return it }
        return backendMutex.withLock {
            backend.takeIf { it != Backend.UNINITIALIZED }?.let { return@withLock it }
            backend = try {
                probeSqliteVec(connection)
                backendDetail = "sqlite-vec extension loaded and query probe succeeded"
                Backend.SQLITE_VEC
            } catch (_: Exception) {
                backendDetail =
                    "sqlite-vec extension is unavailable; using bounded Room exact cosine search"
                Backend.ROOM_EXACT
            }
            backend
        }
    }

    private suspend fun probeSqliteVec(connection: PooledConnection) {
        connection.execute(
            "CREATE VIRTUAL TABLE IF NOT EXISTS $VECTOR_TABLE " +
                "USING vec0(embedding float[$EMBEDDING_DIMENSIONS] distance_metric=cosine)"
        )
        val probeEmbedding = FloatArray(EMBEDDING_DIMENSIONS).also { it[0] = 1f }.toBlob()
        connection.usePrepared(
            "INSERT OR REPLACE INTO $VECTOR_TABLE(rowid, embedding) VALUES (?, ?)"
        ) { statement ->
            statement.bindLong(1, PROBE_ROW_ID)
            statement.bindBlob(2, probeEmbedding)
            statement.step()
        }
        try {
            val found = connection.usePrepared(
                "SELECT rowid, distance FROM $VECTOR_TABLE " +
                    "WHERE embedding MATCH ? AND k = ? ORDER BY distance"
            ) { statement ->
                statement.bindBlob(1, probeEmbedding)
                statement.bindLong(2, 1L)
                statement.step()
            }
            check(found) { "sqlite-vec query probe returned no row" }
        } finally {
            connection.usePrepared("DELETE FROM $VECTOR_TABLE WHERE rowid = ?") { statement ->
                statement.bindLong(1, PROBE_ROW_ID)
                statement.step()
            }
        }

        // This is a database-side streaming reconciliation: it never loads
        // the corpus or all vectors into the Kotlin heap.
        connection.execute(
            "DELETE FROM $VECTOR_TABLE WHERE rowid NOT IN " +
                "(SELECT id FROM document_chunks)"
        )
        connection.execute(
            "INSERT OR REPLACE INTO $VECTOR_TABLE(rowid, embedding) " +
                "SELECT id, embedding FROM document_chunks " +
                "WHERE embedding IS NOT NULL AND length(embedding) = $EMBEDDING_BYTES"
        )
    }

    private suspend fun writeExact(
        connection: PooledConnection,
        databaseId: Long,
        embedding: ByteArray
    ) {
        connection.usePrepared("UPDATE document_chunks SET embedding = ? WHERE id = ?") {
            statement ->
            statement.bindBlob(1, embedding)
            statement.bindLong(2, databaseId)
            statement.step()
        }
        val rowExists = connection.usePrepared(
            "SELECT 1 FROM document_chunks WHERE id = ? LIMIT 1"
        ) { statement ->
            statement.bindLong(1, databaseId)
            statement.step()
        }
        check(rowExists) { "Document chunk $databaseId no longer exists" }
    }

    private suspend fun writeSqliteVec(
        connection: PooledConnection,
        databaseId: Long,
        embedding: ByteArray
    ) {
        connection.usePrepared(
            "INSERT OR REPLACE INTO $VECTOR_TABLE(rowid, embedding) VALUES (?, ?)"
        ) { statement ->
            statement.bindLong(1, databaseId)
            statement.bindBlob(2, embedding)
            statement.step()
        }
    }

    private suspend fun persistExact(
        connection: PooledConnection,
        databaseId: Long,
        embedding: ByteArray
    ): AppResult<Unit> = try {
        writeExact(connection, databaseId, embedding)
        AppResult.Success(Unit)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AppResult.Error("Local vector storage write failed", error)
    }

    private suspend fun searchSqliteVec(
        connection: PooledConnection,
        embedding: FloatArray,
        limit: Int
    ): List<Pair<Long, Float>> = connection.usePrepared(
        "SELECT rowid, distance FROM $VECTOR_TABLE " +
            "WHERE embedding MATCH ? AND k = ? ORDER BY distance"
    ) { statement ->
        statement.bindBlob(1, embedding.toBlob())
        statement.bindLong(2, limit.toLong())
        buildList {
            while (statement.step()) {
                add(statement.getLong(0) to statement.getDouble(1).toFloat())
            }
        }
    }

    private suspend fun searchExact(
        query: FloatArray,
        limit: Int,
        previousError: Exception?
    ): AppResult<List<Evidence>> = try {
        val ranked = PriorityQueue<RankedVector>(limit, WORST_FIRST)
        var afterId = 0L
        while (true) {
            val page = database.documentDao().findEmbeddedVectorsAfter(
                afterId = afterId,
                limit = VECTOR_PAGE_SIZE
            )
            if (page.isEmpty()) break
            withContext(Dispatchers.Default) {
                page.forEach { row ->
                    row.embedding.toFloats()?.let { vector ->
                        val candidate = RankedVector(row.id, cosine(query, vector))
                        if (ranked.size < limit) {
                            ranked.add(candidate)
                        } else if (WORST_FIRST.compare(candidate, ranked.peek()) > 0) {
                            ranked.poll()
                            ranked.add(candidate)
                        }
                    }
                }
            }
            afterId = page.last().id
        }

        val ordered = ranked.toList().sortedWith(
            compareByDescending<RankedVector> { it.score }.thenBy { it.id }
        )
        if (ordered.isEmpty()) {
            AppResult.Success(emptyList())
        } else {
            val chunks = database.documentDao()
                .findChunksWithDocuments(ordered.map { it.id })
                .associateBy { it.chunk.id }
            AppResult.Success(
                ordered.mapNotNull { result ->
                    chunks[result.id]?.toEvidence(result.score)
                }
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        AppResult.Error("Local vector retrieval failed", previousError ?: error)
    }

    private suspend fun deleteLegacyOrSqliteRows(
        connection: PooledConnection,
        documentId: DocumentId,
        fromOrdinal: Int
    ) {
        if (!hasVectorTable(connection)) return
        connection.usePrepared(
            "DELETE FROM $VECTOR_TABLE WHERE rowid IN " +
                "(SELECT id FROM document_chunks WHERE documentId = ? AND ordinal >= ?)"
        ) { statement ->
            statement.bindText(1, documentId.value)
            statement.bindLong(2, fromOrdinal.toLong())
            statement.step()
        }
    }

    private suspend fun hasVectorTable(connection: PooledConnection): Boolean =
        connection.usePrepared(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1"
        ) { statement ->
            statement.bindText(1, VECTOR_TABLE)
            statement.step()
        }

    private suspend fun PooledConnection.execute(sql: String) {
        usePrepared(sql) { it.step() }
    }

    private fun downgradeToRoomExact() {
        synchronized(this) {
            if (backend != Backend.UNAVAILABLE) {
                backend = Backend.ROOM_EXACT
                backendDetail =
                    "sqlite-vec query failed; using bounded Room exact cosine search"
            }
        }
    }

    private fun markUnavailable(error: Exception? = null) {
        synchronized(this) {
            backend = Backend.UNAVAILABLE
            val reason = error?.message
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(160)
            backendDetail = if (reason.isNullOrBlank()) {
                "Local vector database could not be opened"
            } else {
                "Local vector database could not be opened: $reason"
            }
        }
    }

    private fun statusSnapshot(resolvedBackend: Backend = backend): VectorStoreStatus =
        VectorStoreStatus(
            backend = when (resolvedBackend) {
                Backend.SQLITE_VEC -> VectorBackendStatus.SQLITE_VEC
                Backend.ROOM_EXACT -> VectorBackendStatus.ROOM_EXACT_DEGRADED
                Backend.UNAVAILABLE,
                Backend.UNINITIALIZED -> VectorBackendStatus.UNAVAILABLE
            },
            detail = backendDetail
        )

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
        if (left.size != right.size || left.any { !it.isFinite() } || right.any { !it.isFinite() }) {
            return -1f
        }
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
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm)))
            .toFloat()
            .coerceIn(-1f, 1f)
    }

    private data class RankedVector(val id: Long, val score: Float)

    private enum class Backend {
        UNINITIALIZED,
        SQLITE_VEC,
        ROOM_EXACT,
        UNAVAILABLE
    }

    companion object {
        private const val EMBEDDING_DIMENSIONS = 384
        private const val EMBEDDING_BYTES = EMBEDDING_DIMENSIONS * Float.SIZE_BYTES
        private const val MAX_RETRIEVAL_LIMIT = 50
        private const val VECTOR_PAGE_SIZE = 256
        private const val VECTOR_TABLE = "chatbuddy_vectors"
        private const val PROBE_ROW_ID = -1L
        private val WORST_FIRST = Comparator<RankedVector> { left, right ->
            val scoreOrder = left.score.compareTo(right.score)
            if (scoreOrder != 0) scoreOrder else right.id.compareTo(left.id)
        }
    }
}
