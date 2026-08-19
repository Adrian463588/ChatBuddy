package com.chatbuddy.data.repository

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.chatbuddy.data.document.StreamingDocumentTokenReader
import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.data.local.database.DocumentChunkEntity
import com.chatbuddy.data.local.database.DocumentEntity
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentId
import com.chatbuddy.domain.model.DocumentRecord
import com.chatbuddy.domain.repository.DocumentRepository
import com.chatbuddy.domain.repository.EmbeddingRepository
import com.chatbuddy.domain.repository.VectorStoreRepository
import com.chatbuddy.domain.usecase.ChunkDocumentUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    private val database: AppDatabase,
    private val contentResolver: ContentResolver,
    private val tokenReader: StreamingDocumentTokenReader,
    private val chunkDocument: ChunkDocumentUseCase,
    private val embeddingRepository: EmbeddingRepository,
    private val vectorStore: VectorStoreRepository
) : DocumentRepository {
    override fun observeDocuments(): Flow<List<DocumentRecord>> =
        database.documentDao().observeDocuments().map { documents ->
            documents.map {
                DocumentRecord(
                    id = DocumentId(it.id),
                    displayName = it.displayName,
                    sizeBytes = it.sizeBytes,
                    mimeType = it.mimeType,
                    sourceUri = it.sourceUri,
                    chunkCount = it.chunkCount,
                    indexed = it.indexed
                )
            }
        }

    override suspend fun addDocument(uri: String): AppResult<DocumentRecord> = withContext(Dispatchers.IO) {
        val source = Uri.parse(uri)
        val metadata = readMetadata(source)
            ?: return@withContext AppResult.Error("Unable to read document metadata")
        if (metadata.sizeBytes > MAX_DOCUMENT_BYTES) {
            return@withContext AppResult.Error("Document exceeds the 200 MB limit")
        }
        if (!isSupportedDocument(metadata.displayName, metadata.mimeType)) {
            return@withContext AppResult.Error("Only TXT, PDF, and DOCX documents are supported")
        }

        val id = UUID.nameUUIDFromBytes(uri.toByteArray()).toString()
        val record = DocumentRecord(
            id = DocumentId(id),
            displayName = metadata.displayName,
            sizeBytes = metadata.sizeBytes,
            mimeType = metadata.mimeType,
            sourceUri = uri,
            chunkCount = 0,
            indexed = false
        )
        val dao = database.documentDao()
        dao.insertDocument(
            DocumentEntity(
                id = id,
                displayName = record.displayName,
                sizeBytes = record.sizeBytes,
                mimeType = record.mimeType,
                sourceUri = record.sourceUri,
                chunkCount = 0,
                indexed = false
            )
        )
        var chunkCount = 0
        try {
            for (chunk in chunkDocument(DocumentId(id), tokenReader.tokens(source, metadata.displayName, metadata.mimeType))) {
                val embedding = when (val result = embeddingRepository.embed(chunk.text)) {
                    is AppResult.Success -> result.data
                    is AppResult.Error -> throw IndexingException(result.message, result.cause)
                    AppResult.Loading -> throw IndexingException("Embedding runtime is still loading")
                }
                val chunkId = dao.insertChunk(
                    DocumentChunkEntity(
                        documentId = id,
                        ordinal = chunk.ordinal,
                        text = chunk.text,
                        startToken = chunk.startToken,
                        endTokenExclusive = chunk.endTokenExclusive
                    )
                )
                when (val result = vectorStore.insert(chunk.copy(databaseId = chunkId), embedding)) {
                    is AppResult.Success -> chunkCount++
                    is AppResult.Error -> throw IndexingException(result.message, result.cause)
                    AppResult.Loading -> throw IndexingException("Vector store is still loading")
                }
            }
            dao.updateIndexState(id, chunkCount, indexed = true)
            AppResult.Success(record.copy(chunkCount = chunkCount, indexed = true))
        } catch (error: IndexingException) {
            dao.deleteChunks(id)
            dao.deleteDocument(id)
            AppResult.Error(error.message ?: "Document indexing failed", error.cause)
        } catch (error: Exception) {
            dao.deleteChunks(id)
            dao.deleteDocument(id)
            AppResult.Error("Document indexing failed", error)
        }
    }

    override suspend fun deleteDocument(id: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            database.documentDao().deleteChunks(id)
            database.documentDao().deleteDocument(id)
        }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Error("Unable to delete document", it) }
        )
    }

    private fun readMetadata(uri: Uri): Metadata? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            if (cursor?.moveToFirst() != true) return null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            Metadata(
                displayName = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "document.txt" else "document.txt",
                sizeBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex).coerceAtLeast(0) else 0,
                mimeType = contentResolver.getType(uri).orEmpty()
            )
        } finally {
            cursor?.close()
        }
    }

    private fun isSupportedDocument(name: String, mime: String): Boolean =
        mime == "text/plain" || mime == "application/pdf" ||
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
            name.endsWith(".txt", ignoreCase = true) ||
            name.endsWith(".pdf", ignoreCase = true) ||
            name.endsWith(".docx", ignoreCase = true)

    private data class Metadata(val displayName: String, val sizeBytes: Long, val mimeType: String)

    private class IndexingException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val MAX_DOCUMENT_BYTES = 200L * 1024L * 1024L
    }
}
