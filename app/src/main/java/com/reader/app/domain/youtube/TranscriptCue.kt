package com.reader.app.domain.youtube

/**
 * A single subtitle / caption cue.
 *
 * YouTube's transcript XML emits one of these per `<text start="..." dur="...">`
 * tag. We preserve the timing through the chunking pipeline so the
 * Discussion screen can:
 *  - sync the on-screen highlight to the playing video, and
 *  - hand the AI a timestamp window when the student raises a doubt.
 *
 * `text` is already entity-decoded and inline-tag-stripped — it is the
 * exact words spoken in this window, ready for display or prompt
 * injection.
 *
 * Times are seconds (Double) because YouTube emits floats (e.g.
 * `start="45.32"`); we keep that precision rather than rounding up to
 * milliseconds-as-Long.
 */
data class TranscriptCue(
    val startSec: Double,
    val durSec: Double,
    val text: String
) {
    val endSec: Double get() = startSec + durSec
}
