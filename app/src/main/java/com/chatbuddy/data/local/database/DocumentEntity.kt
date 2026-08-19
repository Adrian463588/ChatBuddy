package com.chatbuddy.data.local.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val mimeType: String,
    val sourceUri: String,
    val chunkCount: Int,
    val indexed: Boolean
)

@Entity(tableName = "document_chunks")
data class DocumentChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: String,
    val ordinal: Int,
    val text: String,
    val startToken: Int,
    val endTokenExclusive: Int
)
