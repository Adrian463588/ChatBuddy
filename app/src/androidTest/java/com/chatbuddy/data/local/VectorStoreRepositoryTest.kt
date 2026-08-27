package com.chatbuddy.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.InstrumentationRegistry
import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.data.local.database.DocumentChunkEntity
import com.chatbuddy.data.local.database.DocumentEntity
import com.chatbuddy.data.local.repository.SqliteVecVectorStoreRepository
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentChunk
import com.chatbuddy.domain.model.DocumentId
import com.chatbuddy.domain.repository.VectorBackendStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VectorStoreRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var vectorStore: SqliteVecVectorStoreRepository

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        vectorStore = SqliteVecVectorStoreRepository(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reportsRoomExactAsDegradedWhenSqliteVecIsNotLoaded() = runBlocking {
        val result = vectorStore.getBackendStatus()

        assertTrue(result is AppResult.Success)
        assertEquals(
            VectorBackendStatus.ROOM_EXACT_DEGRADED,
            (result as AppResult.Success).data.backend
        )
    }

    @Test
    fun exactFallbackSearchesInRoomAndDeletesDocumentVectors() = runBlocking {
        insertDocument()
        val chunkId = database.documentDao().insertChunk(chunk(ordinal = 0))
        val embedding = basisVector()

        assertTrue(
            vectorStore.insert(
                domainChunk(ordinal = 0, databaseId = chunkId),
                embedding
            ) is AppResult.Success
        )

        val search = vectorStore.search(embedding, limit = 5)
        assertEquals(1, (search as AppResult.Success).data.size)
        assertEquals(0, search.data.single().chunkOrdinal)

        assertTrue(vectorStore.deleteDocumentVectors(DocumentId("doc")) is AppResult.Success)
        database.documentDao().deleteChunks("doc")
        val afterDelete = vectorStore.search(embedding, limit = 5)
        assertTrue((afterDelete as AppResult.Success).data.isEmpty())
    }

    @Test
    fun ordinalCleanupPreservesCompletedPrefix() = runBlocking {
        insertDocument()
        val firstId = database.documentDao().insertChunk(chunk(ordinal = 0))
        val secondId = database.documentDao().insertChunk(chunk(ordinal = 1))
        val first = basisVector()
        val second = FloatArray(384) { if (it == 1) 1f else 0f }
        vectorStore.insert(domainChunk(ordinal = 0, databaseId = firstId), first)
        vectorStore.insert(domainChunk(ordinal = 1, databaseId = secondId), second)

        assertTrue(
            vectorStore.deleteDocumentVectorsFromOrdinal(DocumentId("doc"), fromOrdinal = 1)
                is AppResult.Success
        )
        database.documentDao().deleteChunksFromOrdinal("doc", fromOrdinal = 1)

        val search = vectorStore.search(first, limit = 5)
        assertEquals(listOf(0), (search as AppResult.Success).data.map { it.chunkOrdinal })
    }

    private suspend fun insertDocument() {
        database.documentDao().insertDocument(
            DocumentEntity(
                id = "doc",
                displayName = "notes.txt",
                sizeBytes = 10,
                mimeType = "text/plain",
                sourceUri = "content://notes",
                chunkCount = 0,
                indexed = false
            )
        )
    }

    private fun chunk(ordinal: Int) = DocumentChunkEntity(
        documentId = "doc",
        ordinal = ordinal,
        text = "chunk $ordinal",
        startToken = ordinal,
        endTokenExclusive = ordinal + 1
    )

    private fun domainChunk(ordinal: Int, databaseId: Long) = DocumentChunk(
        documentId = DocumentId("doc"),
        ordinal = ordinal,
        text = "chunk $ordinal",
        startToken = ordinal,
        endTokenExclusive = ordinal + 1,
        databaseId = databaseId
    )

    private fun basisVector() = FloatArray(384).also { it[0] = 1f }
}
