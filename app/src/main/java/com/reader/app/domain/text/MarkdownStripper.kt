package com.reader.app.domain.text

/**
 * Strips Markdown formatting from a string so the result is safe to feed
 * to a TTS engine (otherwise the engine literally pronounces "asterisk
 * asterisk bold asterisk asterisk").
 *
 * Implementation note — this is now a thin wrapper around
 * [RichTextRenderer]. The renderer produces both:
 *
 *   - a stripped plain-text version (returned here from [strip])
 *   - and an [androidx.compose.ui.text.AnnotatedString] used for the
 *     on-screen formatted display.
 *
 * Routing both through the same parser is what makes per-word TTS
 * highlight work: the engine reports a char range inside the spoken
 * (= stripped) text, and the chat row applies that exact range as a
 * highlight span on top of the rendered AnnotatedString. Two parsers
 * with even a single off-by-one would corrupt the highlight.
 *
 * Kept as a separate object so call-sites that only need the plain
 * string for TTS don't have to know about AnnotatedString or Compose.
 */
object MarkdownStripper {

    /**
     * Returns a plain-text version of [text] with Markdown syntax removed.
     * Blank input returns the empty string.
     *
     * The output is byte-for-byte identical to
     * `RichTextRenderer.render(text).plain`.
     */
    fun strip(text: String): String {
        if (text.isBlank()) return ""
        return RichTextRenderer.render(text).plain
    }
}
