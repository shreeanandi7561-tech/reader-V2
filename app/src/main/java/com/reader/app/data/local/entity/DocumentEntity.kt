package com.reader.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-imported document (paste, .txt / .pdf / .docx upload, or YouTube URL).
 *
 * `lastIndex` is the last TTS cursor saved so the user can resume reading
 * exactly where they left off.
 *
 * **YouTube docs** also carry [youtubeVideoId] and [toneProfile]:
 *  - [youtubeVideoId] is non-null only for documents imported from a
 *    YouTube URL. When set, Discussion mode renders the actual video
 *    above the chat (via the IFrame player) and feeds the AI the exact
 *    timestamp window the student paused on. For text-only docs this
 *    stays null and the Discussion screen falls back to the chat-only
 *    layout — the existing behaviour is preserved 1:1.
 *  - [toneProfile] is a 1–2 paragraph "teaching style" snapshot of the
 *    video's narrator that we extract via a one-time LLM call on import
 *    (see `ToneProfileExtractor`). The Discussion prompt builder feeds
 *    it back to the LLM at every Q&A so the AI's answer mimics the
 *    actual video tutor's tone, vocabulary and rhythm — works for any
 *    video because the profile is derived from that video's transcript,
 *    not hard-coded. Null when extraction failed or the doc isn't from
 *    YouTube; the prompt builder gracefully degrades to a generic
 *    "match the source's tone" instruction.
 *  - [storyboardSpec] is YouTube's raw `playerStoryboardSpecRenderer.spec`
 *    string captured at import time. It is a `|`-separated descriptor
 *    of the public preview-thumbnail grids YouTube already serves for
 *    every video (the tiles you see hovering over the seek-bar).
 *
 *    The Discussion-mode multimodal frame pipeline already grabs HD
 *    frames out of the IFrame WebView's bitmap when that succeeds —
 *    but on Android API levels / device GPU stacks where the video
 *    surface is hardware-only and not blitted into the WebView's
 *    `Canvas`, that path returns mostly-black frames the AI can't
 *    use. When this column is populated, the Composite frame source
 *    falls back per-frame to `i.ytimg.com/sb/...` storyboard cells
 *    (low-res ~160×90 px but reliably aligned to any timestamp), so
 *    the AI still gets visual context instead of nothing.
 *
 *    Null on docs imported before this column existed (v7 and older)
 *    and on videos for which YouTube didn't expose a storyboard
 *    (extremely rare). Re-importing the same YouTube URL re-captures
 *    the spec automatically.
 */
@Entity(tableName = "document")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val totalChunks: Int = 0,
    val lastIndex: Int = 0,
    val youtubeVideoId: String? = null,
    val toneProfile: String? = null,
    val storyboardSpec: String? = null
)
