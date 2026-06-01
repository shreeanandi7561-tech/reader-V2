package com.reader.app.domain.chunk

/**
 * Decides whether a string the user just imported is a usable text document
 * for the reader. The user spec is explicit: only text-based documents are
 * accepted (notes, questions, summaries, presentations exported as text,
 * etc). Image-based or binary documents must be rejected up-front rather
 * than silently produce gibberish chunks.
 *
 * Two checks:
 *  1. **Non-empty after whitespace flattening.** A file that's all
 *     whitespace / control characters has nothing to read.
 *  2. **Printable-character ratio.** Real text documents have <2% control
 *     characters (and even that is generous — usually it's 0). Binary
 *     payloads (PDF, images, zips, etc.) accidentally read as text show
 *     huge runs of NULs and high-bit bytes. We reject anything where less
 *     than 90% of characters look like real text.
 */
object TextDocumentValidator {

    /** Result of a validation pass. */
    sealed interface Result {
        data class Ok(val cleanedText: String) : Result
        data class Reject(val reason: String) : Result
    }

    /** Minimum useful length after flattening. */
    private const val MIN_USEFUL_CHARS = 20

    /** At least this fraction of characters must be "printable text". */
    private const val MIN_PRINTABLE_RATIO = 0.90

    fun validate(rawText: String): Result {
        if (rawText.isBlank()) {
            return Result.Reject("File is empty.")
        }

        val printable = rawText.count { isTextChar(it) }
        val ratio = printable.toDouble() / rawText.length
        if (ratio < MIN_PRINTABLE_RATIO) {
            return Result.Reject(
                "This file looks like a binary or image-based document " +
                    "(only ${(ratio * 100).toInt()}% readable text). " +
                    "Sirf text-based documents (.txt, .md, notes, summary, " +
                    "presentation as text) hi support karte hain."
            )
        }

        val cleaned = TextChunker.flatten(rawText)
        if (cleaned.length < MIN_USEFUL_CHARS) {
            return Result.Reject(
                "Document mein readable text bahut kam hai (${cleaned.length} characters). " +
                    "Kripya complete document upload karein."
            )
        }
        return Result.Ok(cleaned)
    }

    /**
     * "Looks like real text" — common whitespace, anything that isn't an
     * ISO control character. Letters, digits, punctuation, symbols and
     * Indic / CJK / emoji scripts all pass; binary inputs full of NUL or
     * SOH bytes get rejected.
     */
    private fun isTextChar(ch: Char): Boolean {
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') return true
        if (Character.isISOControl(ch)) return false
        return true
    }
}
