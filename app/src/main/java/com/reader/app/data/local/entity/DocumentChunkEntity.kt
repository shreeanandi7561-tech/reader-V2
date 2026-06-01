package com.reader.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Indexed sentence (or small chunk) of a [DocumentEntity]. The [orderIndex]
 * is what the TTS controller iterates and what we save as `currentIndex` on pause.
 */
@Entity(
    tableName = "document_chunk",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId"), Index(value = ["documentId", "orderIndex"], unique = true)]
)
data class DocumentChunkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val documentId: Long,
    val orderIndex: Int,
    val text: String
)
