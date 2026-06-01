package com.reader.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One subtitle / caption cue belonging to a YouTube-imported document.
 *
 * Unlike [DocumentChunkEntity] (which slices the *text* into sentence-
 * sized pieces for Reading mode), TranscriptCueEntity preserves YouTube's
 * own caption boundaries with their timestamps so Discussion mode can
 * sync the AI to whatever moment the student is actually watching.
 *
 * Schema notes:
 *  - `documentId` is FK'd to `document.id` with cascading delete so cues
 *    disappear with their parent doc.
 *  - The compound index `(documentId, startSec)` makes the "cues in
 *    [t-60, t]" timestamp-window query (used by the Discussion VM on
 *    every doubt) a single B-tree range scan.
 */
@Entity(
    tableName = "transcript_cue",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["documentId", "startSec"])
    ]
)
data class TranscriptCueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val documentId: Long,
    /** Seconds from the start of the video. Float-precision because YouTube emits e.g. `45.32`. */
    val startSec: Double,
    /** Duration in seconds. May be 0 for some single-frame caption tracks. */
    val durSec: Double,
    /** Cue text — already entity-decoded, inline tags stripped. */
    val text: String
)
