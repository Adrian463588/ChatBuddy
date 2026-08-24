package com.chatbuddy.data.local.database

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.Embedded
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
    val endTokenExclusive: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray? = null
)

data class DocumentChunkWithDocument(
    @Embedded val chunk: DocumentChunkEntity,
    @ColumnInfo(name = "documentName") val documentName: String
)

data class DocumentVectorRow(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "documentId") val documentId: String,
    @ColumnInfo(name = "ordinal") val ordinal: Int,
    @ColumnInfo(name = "embedding") val embedding: ByteArray
)
