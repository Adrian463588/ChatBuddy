package com.chatbuddy.data.repository

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.chatbuddy.data.document.StreamingDocumentTokenReader
import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.data.local.database.DocumentChunkEntity
import com.chatbuddy.data.local.database.DocumentEntity
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentFolderIndexSummary
import com.chatbuddy.domain.model.DocumentId
import com.chatbuddy.domain.model.DocumentRecord
import com.chatbuddy.domain.model.FolderIndexFailure
import com.chatbuddy.domain.repository.DocumentRepository
import com.chatbuddy.domain.repository.EmbeddingRepository
import com.chatbuddy.domain.repository.VectorStoreRepository
import com.chatbuddy.domain.usecase.ChunkDocumentUseCase
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class DocumentRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val contentResolver: ContentResolver,
    private val tokenReader: StreamingDocumentTokenReader,
    private val chunkDocument: ChunkDocumentUseCase,
    private val embeddingRepository: EmbeddingRepository,
    private val vectorStore: VectorStoreRepository
) : DocumentRepository {
    private val indexingMutex = Mutex()

    override fun observeDocuments(): Flow<List<DocumentRecord>> =
        database.documentDao().observeDocuments().map { documents ->
            documents.map { it.toRecord() }
        }

    override suspend fun addDocument(uri: String): AppResult<DocumentRecord> =
        withContext(Dispatchers.IO) {
            try {
                indexingMutex.withLock {
                    indexDocument(Uri.parse(uri), persistPermission = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppResult.Error("Unable to index document", error)
            }
        }

    override suspend fun addFolder(uri: String): AppResult<DocumentFolderIndexSummary> =
        withContext(Dispatchers.IO) {
            try {
                indexingMutex.withLock { indexFolder(Uri.parse(uri)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppResult.Error("Unable to scan the selected SAF folder", error)
            }
        }

    override suspend fun deleteDocument(id: String): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                indexingMutex.withLock {
                    val documentId = DocumentId(id)
                    when (val vectorResult = vectorStore.deleteDocumentVectors(documentId)) {
                        is AppResult.Error -> return@withLock vectorResult
                        AppResult.Loading -> return@withLock AppResult.Error(
                            "Vector cleanup is still loading; document was kept"
                        )
                        is AppResult.Success -> Unit
                    }
                    try {
                        val dao = database.documentDao()
                        dao.deleteChunks(id)
                        dao.deleteDocument(id)
                        AppResult.Success(Unit)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        AppResult.Error(
                            "Unable to delete document after vector cleanup",
                            error
                        )
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppResult.Error("Unable to delete document", error)
            }
        }

    private suspend fun indexDocument(
        source: Uri,
        persistPermission: Boolean
    ): AppResult<DocumentRecord> {
        if (persistPermission) {
            try {
                contentResolver.takePersistableUriPermission(
                    source,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (error: SecurityException) {
                return AppResult.Error(
                    "Document permission could not be persisted. Choose it again from a SAF provider.",
                    error
                )
            }
        }
        val uri = source.toString()
        val metadata = readMetadata(source)
            ?: return AppResult.Error("Unable to read document metadata")
        if (metadata.sizeBytes > MAX_DOCUMENT_BYTES) {
            return AppResult.Error("Document exceeds the 200 MB limit")
        }
        if (!isSupportedDocument(metadata.displayName, metadata.mimeType)) {
            return AppResult.Error("Only TXT, PDF, and DOCX documents are supported")
        }

        val id = UUID.nameUUIDFromBytes(uri.toByteArray(Charsets.UTF_8)).toString()
        val documentId = DocumentId(id)
        val dao = database.documentDao()
        var existing = dao.findDocument(id)

        if (existing != null && !sameMetadata(existing, metadata)) {
            when (val cleanup = cleanupTail(documentId, fromOrdinal = 0)) {
                is AppResult.Error -> return cleanup
                AppResult.Loading -> return AppResult.Error(
                    "Existing document cleanup is still loading; document was kept"
                )
                is AppResult.Success -> Unit
            }
            dao.deleteChunks(id)
            dao.deleteDocument(id)
            existing = null
        }

        if (existing == null) {
            dao.insertDocument(
                DocumentEntity(
                    id = id,
                    displayName = metadata.displayName,
                    sizeBytes = metadata.sizeBytes,
                    mimeType = metadata.mimeType,
                    sourceUri = uri,
                    chunkCount = 0,
                    indexed = false,
                    lastModifiedEpochMs = metadata.lastModifiedEpochMs
                )
            )
        } else if (existing.indexed && isHealthy(existing)) {
            return AppResult.Success(existing.toRecord())
        }

        return indexExistingDocument(
            source = source,
            documentId = documentId,
            metadata = metadata,
            dao = dao
        )
    }

    private suspend fun indexFolder(folderUri: Uri): AppResult<DocumentFolderIndexSummary> {
        val root = DocumentFile.fromTreeUri(context, folderUri)
            ?: return AppResult.Error("The selected SAF folder is no longer available")
        if (!root.exists() || !root.isDirectory || !root.canRead()) {
            return AppResult.Error("The selected SAF folder cannot be read")
        }
        try {
            contentResolver.takePersistableUriPermission(
                folderUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (error: SecurityException) {
            return AppResult.Error(
                "SAF folder permission could not be persisted. Reconnect the folder and try again.",
                error
            )
        }

        val files = enumerateSupportedFiles(root)
        val checkpoint = context.getSharedPreferences(
            FOLDER_CHECKPOINT_PREFS,
            Context.MODE_PRIVATE
        )
        val checkpointKey = checkpointKey(folderUri)
        val completed = checkpoint.getStringSet(checkpointKey, emptySet()).orEmpty().toMutableSet()
        var indexed = 0
        var skipped = 0
        val failures = ArrayList<FolderIndexFailure>()

        for (file in files) {
            val source = file.uri.toString()
            val checkpointToken = folderFileCheckpointToken(file)
            if (checkpointToken in completed) {
                skipped++
                continue
            }
            when (val result = indexDocument(file.uri, persistPermission = false)) {
                is AppResult.Success -> {
                    indexed++
                    completed += checkpointToken
                    checkpoint.edit().putStringSet(checkpointKey, completed).apply()
                }
                is AppResult.Error -> failures += FolderIndexFailure(
                    sourceUri = source,
                    displayName = file.name ?: source,
                    reason = result.message
                )
                AppResult.Loading -> failures += FolderIndexFailure(
                    sourceUri = source,
                    displayName = file.name ?: source,
                    reason = "Document indexing is still loading"
                )
            }
        }
        if (files.all { folderFileCheckpointToken(it) in completed }) {
            checkpoint.edit().remove(checkpointKey).apply()
        }
        return AppResult.Success(
            DocumentFolderIndexSummary(
                folderUri = folderUri.toString(),
                discoveredFiles = files.size,
                indexedFiles = indexed,
                skippedFiles = skipped,
                failures = failures
            )
        )
    }

    private fun enumerateSupportedFiles(root: DocumentFile): List<DocumentFile> {
        val result = ArrayList<DocumentFile>()
        val pending = ArrayDeque<DocumentFile>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (current.isDirectory) {
                current.listFiles().forEach { child ->
                    if (child.isDirectory) pending.add(child)
                    else if (child.isFile && isSupportedDocument(child.name.orEmpty(), child.type.orEmpty())) {
                        result += child
                    }
                }
            }
        }
        return result.sortedBy { it.uri.toString() }
    }

    private fun folderFileCheckpointToken(file: DocumentFile): String = buildString {
        append(file.uri)
        append('|')
        append(file.length().coerceAtLeast(0L))
        append('|')
        append(file.lastModified().coerceAtLeast(0L))
    }

    private fun checkpointKey(folderUri: Uri): String =
        "folder_${folderUri.toString().hashCode().toUInt().toString(16)}"

    private suspend fun indexExistingDocument(
        source: Uri,
        documentId: DocumentId,
        metadata: Metadata,
        dao: com.chatbuddy.data.local.database.DocumentDao
    ): AppResult<DocumentRecord> {
        val checkpoint = try {
            prepareCheckpoint(documentId, dao)
        } catch (error: IndexingException) {
            return AppResult.Error(error.message ?: "Unable to prepare document index", error.cause)
        }
        var nextOrdinal = checkpoint.nextOrdinal
        var completedChunkCount = checkpoint.completedChunkCount

        return try {
            dao.updateIndexState(documentId.value, completedChunkCount, indexed = false)
            val tokenIterator = tokenReader.tokens(
                source,
                metadata.displayName,
                metadata.mimeType
            ).iterator()
            val resumedSource = createResumedSource(tokenIterator, checkpoint)
            if (resumedSource.isComplete) {
                dao.updateIndexState(documentId.value, completedChunkCount, indexed = true)
                return AppResult.Success(
                    metadata.toRecord(
                        documentId,
                        source.toString(),
                        completedChunkCount,
                        indexed = true
                    )
                )
            }

            for (rawChunk in chunkDocument(
                documentId = documentId,
                tokens = resumedSource.tokens,
                chunkSize = CHUNK_SIZE,
                overlap = CHUNK_OVERLAP
            )) {
                val chunk = rawChunk.copy(
                    ordinal = checkpoint.nextOrdinal + rawChunk.ordinal,
                    startToken = checkpoint.resumeTokenStart + rawChunk.startToken,
                    endTokenExclusive = checkpoint.resumeTokenStart + rawChunk.endTokenExclusive
                )
                val embedding = when (val result = embeddingRepository.embed(chunk.text)) {
                    is AppResult.Success -> result.data
                    is AppResult.Error -> throw IndexingException(result.message, result.cause)
                    AppResult.Loading -> throw IndexingException(
                        "Embedding runtime is still loading"
                    )
                }
                val chunkId = dao.insertChunk(
                    DocumentChunkEntity(
                        documentId = documentId.value,
                        ordinal = chunk.ordinal,
                        text = chunk.text,
                        startToken = chunk.startToken,
                        endTokenExclusive = chunk.endTokenExclusive
                    )
                )
                when (val result = vectorStore.insert(chunk.copy(databaseId = chunkId), embedding)) {
                    is AppResult.Success -> Unit
                    is AppResult.Error -> throw IndexingException(result.message, result.cause)
                    AppResult.Loading -> throw IndexingException(
                        "Vector store is still loading"
                    )
                }
                completedChunkCount++
                nextOrdinal = chunk.ordinal + 1
                // This is the durable checkpoint. A process kill resumes from
                // the last chunk whose embedding was persisted successfully.
                dao.updateIndexState(
                    documentId.value,
                    completedChunkCount,
                    indexed = false
                )
            }

            dao.updateIndexState(documentId.value, completedChunkCount, indexed = true)
            AppResult.Success(
                metadata.toRecord(
                    documentId,
                    source.toString(),
                    completedChunkCount,
                    indexed = true
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: IndexingException) {
            recoverAfterFailure(documentId, nextOrdinal, completedChunkCount, error)
        } catch (error: Exception) {
            recoverAfterFailure(
                documentId,
                nextOrdinal,
                completedChunkCount,
                IndexingException("Document indexing failed", error)
            )
        }
    }

    private suspend fun prepareCheckpoint(
        documentId: DocumentId,
        dao: com.chatbuddy.data.local.database.DocumentDao
    ): ResumeCheckpoint {
        val firstIncomplete = dao.findFirstIncompleteChunk(documentId.value)
        if (firstIncomplete != null) {
            when (val cleanup = cleanupTail(documentId, firstIncomplete.ordinal)) {
                is AppResult.Error -> throw IndexingException(cleanup.message, cleanup.cause)
                AppResult.Loading -> throw IndexingException("Vector cleanup is still loading")
                is AppResult.Success -> Unit
            }
        }
        val lastComplete = dao.findLastCompleteChunk(documentId.value)
        val completedChunkCount = lastComplete?.ordinal?.plus(1) ?: 0
        return ResumeCheckpoint(
            nextOrdinal = completedChunkCount,
            completedChunkCount = completedChunkCount,
            resumeTokenStart = lastComplete
                ?.endTokenExclusive
                ?.minus(CHUNK_OVERLAP)
                ?.coerceAtLeast(0)
                ?: 0
        )
    }

    private fun createResumedSource(
        iterator: Iterator<String>,
        checkpoint: ResumeCheckpoint
    ): ResumedSource {
        repeat(checkpoint.resumeTokenStart) {
            if (!iterator.hasNext()) {
                throw IndexingException("Document changed before the saved index checkpoint")
            }
            iterator.next()
        }

        if (checkpoint.nextOrdinal == 0) {
            return ResumedSource(
                tokens = sequence {
                    while (iterator.hasNext()) yield(iterator.next())
                },
                isComplete = false
            )
        }

        val overlapTokens = ArrayList<String>(CHUNK_OVERLAP + 1)
        while (overlapTokens.size <= CHUNK_OVERLAP && iterator.hasNext()) {
            overlapTokens += iterator.next()
        }
        if (overlapTokens.size < CHUNK_OVERLAP) {
            throw IndexingException("Document changed before the saved index checkpoint")
        }
        if (overlapTokens.size == CHUNK_OVERLAP) {
            return ResumedSource(emptySequence(), isComplete = true)
        }

        return ResumedSource(
            tokens = sequence {
                yieldAll(overlapTokens)
                while (iterator.hasNext()) yield(iterator.next())
            },
            isComplete = false
        )
    }

    private suspend fun recoverAfterFailure(
        documentId: DocumentId,
        nextOrdinal: Int,
        completedChunkCount: Int,
        error: IndexingException
    ): AppResult<DocumentRecord> {
        val cleanup = try {
            cleanupTail(documentId, nextOrdinal)
        } catch (cleanupError: CancellationException) {
            throw cleanupError
        } catch (cleanupError: Exception) {
            return AppResult.Error(
                "${error.message}; partial index kept because vector cleanup could not be verified",
                error.cause ?: cleanupError
            )
        }
        if (cleanup is AppResult.Error) {
            return AppResult.Error(
                "${error.message}; partial index kept because vector cleanup could not be verified",
                error.cause ?: cleanup.cause
            )
        }
        val savedCount = try {
            database.documentDao().findLastCompleteChunk(documentId.value)
                ?.ordinal
                ?.plus(1)
                ?: 0
        } catch (readError: CancellationException) {
            throw readError
        } catch (_: Exception) {
            completedChunkCount
        }
        try {
            database.documentDao().updateIndexState(
                documentId.value,
                savedCount,
                indexed = false
            )
        } catch (updateError: CancellationException) {
            throw updateError
        } catch (_: Exception) {
            // The original indexing failure remains the actionable result.
        }
        return AppResult.Error(error.message ?: "Document indexing failed", error.cause)
    }

    private suspend fun cleanupTail(
        documentId: DocumentId,
        fromOrdinal: Int
    ): AppResult<Unit> {
        when (val result = vectorStore.deleteDocumentVectorsFromOrdinal(documentId, fromOrdinal)) {
            is AppResult.Error -> return AppResult.Error(
                "Vector cleanup failed; document data was kept",
                result.cause
            )
            AppResult.Loading -> return AppResult.Error(
                "Vector cleanup is still loading; document data was kept"
            )
            is AppResult.Success -> Unit
        }
        return try {
            database.documentDao().deleteChunksFromOrdinal(documentId.value, fromOrdinal)
            AppResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppResult.Error("Document chunk cleanup failed", error)
        }
    }

    private suspend fun isHealthy(document: DocumentEntity): Boolean {
        val dao = database.documentDao()
        if (dao.findFirstIncompleteChunk(document.id) != null) return false
        val expectedChunkCount = dao.findLastCompleteChunk(document.id)
            ?.ordinal
            ?.plus(1)
            ?: 0
        if (document.chunkCount != expectedChunkCount) {
            dao.updateIndexState(document.id, expectedChunkCount, indexed = true)
        }
        return true
    }

    private fun readMetadata(uri: Uri): Metadata? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )
            if (cursor?.moveToFirst() != true) return null
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            Metadata(
                displayName = if (nameIndex >= 0) {
                    cursor.getString(nameIndex) ?: "document.txt"
                } else {
                    "document.txt"
                },
                sizeBytes = if (sizeIndex >= 0) {
                    cursor.getLong(sizeIndex).coerceAtLeast(0)
                } else {
                    0
                },
                mimeType = contentResolver.getType(uri).orEmpty(),
                lastModifiedEpochMs = runCatching {
                    DocumentFile.fromSingleUri(context, uri)
                        ?.lastModified()
                        ?.coerceAtLeast(0L)
                        ?: 0L
                }.getOrDefault(0L)
            )
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun sameMetadata(document: DocumentEntity, metadata: Metadata): Boolean =
        document.displayName == metadata.displayName &&
            document.sizeBytes == metadata.sizeBytes &&
            document.mimeType == metadata.mimeType &&
            document.lastModifiedEpochMs == metadata.lastModifiedEpochMs

    private fun isSupportedDocument(name: String, mime: String): Boolean =
        mime == TEXT_MIME || mime == PDF_MIME || mime == DOCX_MIME ||
            name.endsWith(".txt", ignoreCase = true) ||
            name.endsWith(".pdf", ignoreCase = true) ||
            name.endsWith(".docx", ignoreCase = true)

    private fun DocumentEntity.toRecord(): DocumentRecord = DocumentRecord(
        id = DocumentId(id),
        displayName = displayName,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        sourceUri = sourceUri,
        chunkCount = chunkCount,
        indexed = indexed
    )

    private fun Metadata.toRecord(
        id: DocumentId,
        sourceUri: String,
        chunkCount: Int,
        indexed: Boolean
    ): DocumentRecord = DocumentRecord(
        id = id,
        displayName = displayName,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        sourceUri = sourceUri,
        chunkCount = chunkCount,
        indexed = indexed
    )

    private data class Metadata(
        val displayName: String,
        val sizeBytes: Long,
        val mimeType: String,
        val lastModifiedEpochMs: Long
    )

    private data class ResumeCheckpoint(
        val nextOrdinal: Int,
        val completedChunkCount: Int,
        val resumeTokenStart: Int
    )

    private data class ResumedSource(
        val tokens: Sequence<String>,
        val isComplete: Boolean
    )

    private class IndexingException(message: String, cause: Throwable? = null) : Exception(message, cause)

    companion object {
        private const val TEXT_MIME = "text/plain"
        private const val PDF_MIME = "application/pdf"
        private const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val MAX_DOCUMENT_BYTES = 200L * 1024L * 1024L
        private const val CHUNK_SIZE = 512
        private const val CHUNK_OVERLAP = 50
        private const val FOLDER_CHECKPOINT_PREFS = "document_folder_checkpoints"
    }
}
