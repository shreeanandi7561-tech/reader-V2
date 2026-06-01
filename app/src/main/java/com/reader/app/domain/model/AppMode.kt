package com.reader.app.domain.model

/**
 * Three operational modes the user configures independently in Settings.
 *
 * - [Reading]:    TTS-driven continuous document reading + voice-doubt RAG.
 * - [Discussion]: Math / step-breakdown analytical mode.
 * - [Generate]:   Produces structured study material from the transcript —
 *                 MCQ tests and PDF notes. Has its own BYOK row so the
 *                 student can route long-context generation work to a
 *                 different (cheaper / larger-context) model than chat.
 */
enum class AppMode {
    Reading,
    Discussion,
    Generate
}
