package com.chatbuddy.domain.model

@JvmInline
value class DocumentId(val value: String)

data class DocumentRecord(
    val id: DocumentId,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val sourceUri: String,
    val chunkCount: Int,
    val indexed: Boolean
)

data class FolderIndexFailure(
    val sourceUri: String,
    val displayName: String,
    val reason: String
)

data class DocumentFolderIndexSummary(
    val folderUri: String,
    val discoveredFiles: Int,
    val indexedFiles: Int,
    val skippedFiles: Int,
    val failures: List<FolderIndexFailure>
)

data class DocumentChunk(
    val documentId: DocumentId,
    val ordinal: Int,
    val text: String,
    val startToken: Int,
    val endTokenExclusive: Int,
    val databaseId: Long? = null
)

data class Evidence(
    val documentId: DocumentId,
    val documentName: String,
    val chunkOrdinal: Int,
    val text: String,
    val score: Float
)
