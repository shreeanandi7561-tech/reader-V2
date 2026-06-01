package com.reader.app.data.repository

import com.reader.app.data.local.dao.DocumentChunkDao
import com.reader.app.data.local.dao.DocumentDao
import com.reader.app.data.local.dao.TranscriptCueDao
import com.reader.app.data.local.entity.DocumentChunkEntity
import com.reader.app.data.local.entity.DocumentEntity
import com.reader.app.data.local.entity.TranscriptCueEntity
import com.reader.app.domain.chunk.TextChunker
import com.reader.app.domain.chunk.TextDocumentValidator
import com.reader.app.domain.youtube.TranscriptCue
import kotlinx.coroutines.flow.Flow

/**
 * Owns Document + DocumentChunk + TranscriptCue persistence and the
 * chunking pipeline.
 *
 * `import` runs the raw text through:
 *   1. [TextDocumentValidator]  — reject binary / image-based / empty input
 *   2. [TextChunker.flatten]    — collapse all whitespace into one stream
 *   3. [TextChunker.chunk]      — split into sentence-level chunks
 * before persisting. This is what gives the reader its "one continuous
 * story, but highlighted sentence-by-sentence" UX.
 *
 * `importYoutube` adds two more steps on top:
 *   4. Persist `youtubeVideoId` on the document so Discussion mode knows
 *      to render the IFrame player above the chat.
 *   5. Persist all caption cues with their timestamps in `transcript_cue`
 *      so the same screen can hand the AI a "last 60 seconds" window
 *      whenever the student raises a doubt.
 *
 * Old plain-text docs (paste / .pdf / .docx / .txt) still use `import`
 * unchanged — they get `youtubeVideoId = null` and the Discussion screen
 * falls back to the existing chat-only layout.
 */
class DocumentRepository(
    private val documentDao: DocumentDao,
    private val chunkDao: DocumentChunkDao,
    private val cueDao: TranscriptCueDao
) {

    fun observeAll(): Flow<List<DocumentEntity>> = documentDao.observeAll()

    suspend fun get(id: Long): DocumentEntity? = documentDao.get(id)

    /**
     * Validate, chunk and persist a new document.
     *
     * @return the new document id.
     * @throws IllegalArgumentException if the input isn't a valid text
     *   document (binary, image-based, empty, …) — the message is safe
     *   to show directly to the user.
     */
    suspend fun import(title: String, rawText: String): Long =
        importInternal(title = title, rawText = rawText, youtubeVideoId = null, cues = emptyList())

    /**
     * YouTube-flavoured import. Same chunking pipeline as [import], plus:
     *  - The new document row carries [videoId] so Discussion mode can
     *    render the actual video.
     *  - Each [TranscriptCue] is persisted as a row in `transcript_cue`
     *    with its `startSec` / `durSec`, ready for the timestamp-window
     *    queries [loadCuesAround] makes.
     *  - The optional [storyboardSpec] (YouTube's public preview-grid
     *    descriptor) is persisted alongside so the multimodal frame
     *    pipeline can fall back to storyboard cells when WebView frame
     *    capture returns black bitmaps.
     *
     * If [cues] is empty (e.g. caption-less video, title-only fallback),
     * the doc is still persisted with the [videoId] — the Discussion
     * screen will show the player but the AI prompt builder will
     * gracefully fall back to flat-document context. Same for
     * [storyboardSpec] = null: the WebView capture path runs as
     * before, no fallback engaged.
     */
    suspend fun importYoutube(
        title: String,
        rawText: String,
        videoId: String,
        cues: List<TranscriptCue>,
        storyboardSpec: String? = null
    ): Long = importInternal(
        title          = title,
        rawText        = rawText,
        youtubeVideoId = videoId,
        cues           = cues,
        storyboardSpec = storyboardSpec
    )

    private suspend fun importInternal(
        title: String,
        rawText: String,
        youtubeVideoId: String?,
        cues: List<TranscriptCue>,
        storyboardSpec: String? = null
    ): Long {
        val cleanedText = when (val v = TextDocumentValidator.validate(rawText)) {
            is TextDocumentValidator.Result.Ok     -> v.cleanedText
            is TextDocumentValidator.Result.Reject -> throw IllegalArgumentException(v.reason)
        }
        val chunks = TextChunker.chunk(cleanedText)
        if (chunks.isEmpty()) {
            throw IllegalArgumentException(
                "Could not extract any readable sentences from this document."
            )
        }

        val docId = documentDao.insert(
            DocumentEntity(
                title          = title,
                youtubeVideoId = youtubeVideoId,
                storyboardSpec = storyboardSpec
            )
        )
        val chunkEntities = chunks.mapIndexed { i, text ->
            DocumentChunkEntity(documentId = docId, orderIndex = i, text = text)
        }
        chunkDao.insertAll(chunkEntities)
        documentDao.updateTotalChunks(docId, chunkEntities.size)

        if (cues.isNotEmpty()) {
            cueDao.insertAll(
                cues.map { c ->
                    TranscriptCueEntity(
                        documentId = docId,
                        startSec   = c.startSec,
                        durSec     = c.durSec,
                        text       = c.text
                    )
                }
            )
        }
        return docId
    }

    suspend fun loadChunks(documentId: Long): List<String> =
        chunkDao.getAllForDocument(documentId).map { it.text }

    suspend fun loadFullText(documentId: Long): String =
        chunkDao.getAllForDocument(documentId).joinToString(separator = " ") { it.text }

    /** Last [n] chunks at or before [endIndexInclusive], in reading order. */
    suspend fun loadRecent(documentId: Long, endIndexInclusive: Int, n: Int): List<String> =
        chunkDao.getRecent(documentId, endIndexInclusive, n)
            .sortedBy { it.orderIndex }
            .map { it.text }

    /**
     * All caption cues for a YouTube doc in playback order. Empty when
     * the doc has no associated video / no cues were captured at import.
     */
    suspend fun loadCues(documentId: Long): List<TranscriptCue> =
        cueDao.getAllForDocument(documentId).map { it.toDomain() }

    /**
     * Cues whose start lies in the half-open window
     * `[positionSec - lookbackSec, positionSec]`, in playback order.
     *
     * This is the "what was the teacher just saying" snippet that
     * Discussion mode injects into the AI prompt when the student
     * pauses on a doubt — typically with a 60 s lookback. The current
     * cue (the one the player is sitting on at exactly `positionSec`)
     * is included via the upper bound being `positionSec + ε`.
     */
    suspend fun loadCuesAround(
        documentId: Long,
        positionSec: Double,
        lookbackSec: Double = DEFAULT_DOUBT_LOOKBACK_SEC
    ): List<TranscriptCue> {
        val from = (positionSec - lookbackSec).coerceAtLeast(0.0)
        // +0.5 so the cue currently spanning `positionSec` is included even
        // when its `startSec` is slightly past the exact pause moment.
        val to   = positionSec + 0.5
        return cueDao.getInWindow(documentId, from, to).map { it.toDomain() }
    }

    suspend fun saveProgress(documentId: Long, index: Int) {
        documentDao.updateLastIndex(documentId, index)
    }

    /** Cache the AI-derived teaching-style profile for this video. */
    suspend fun saveToneProfile(documentId: Long, profile: String?) {
        documentDao.updateToneProfile(documentId, profile?.takeIf { it.isNotBlank() })
    }

    /**
     * Cache YouTube's storyboard-spec descriptor for a video doc.
     *
     * Only meaningful when the doc has a [DocumentEntity.youtubeVideoId];
     * for plain-text docs the spec stays null and the multimodal frame
     * fallback is bypassed. Stored exactly as YouTube returned it
     * (`|`-separated levels, `$L`/`$M`/`$N` URL placeholders) — the
     * StoryboardSpec parser handles all decoding lazily at doubt time.
     */
    suspend fun saveStoryboardSpec(documentId: Long, spec: String?) {
        documentDao.updateStoryboardSpec(documentId, spec?.takeIf { it.isNotBlank() })
    }

    private fun TranscriptCueEntity.toDomain(): TranscriptCue =
        TranscriptCue(startSec = startSec, durSec = durSec, text = text)

    companion object {
        /** Default doubt context window. */
        const val DEFAULT_DOUBT_LOOKBACK_SEC: Double = 60.0
    }
}
