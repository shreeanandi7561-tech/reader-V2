package com.reader.app.domain.notes

import com.reader.app.data.repository.LlmRepository
import com.reader.app.domain.model.ApiConfig
import com.reader.app.domain.text.LanguageDetect

/**
 * Converts a raw transcript into a self-contained, print-ready,
 * student-friendly HTML *study notes* document.
 *
 * **Design intent (this is NOT just transcript-with-tags):**
 *
 * The previous version of this prompt asked the LLM to "preserve the
 * teacher's tone, flow and depth" — and the LLM took that to mean
 * "paste the transcript verbatim with `<p>` tags around each
 * paragraph". The result was a wall of prose with no headings, no
 * sub-headings, nothing the student could scan. The student already
 * has the video for that.
 *
 * What we actually want is the *content* of the transcript re-
 * organised into proper revision notes:
 *   - same language register (Hindi / English / Hinglish — match the
 *     transcript), same technical terms, same examples;
 *   - simpler sentences, bullet points, numbered steps, callouts for
 *     the key idea / formula / worked example / common mistake of each
 *     section;
 *   - a navigable structure (Table of Contents at the top, multi-
 *     level headings, every section ending in a 3-5-bullet summary).
 *
 * **MathJax / LaTeX support (added):** The notes WebView now loads
 * MathJax 3 (CDN) so the LLM can emit math in standard LaTeX
 * delimiters (single-dollar inline, double-dollar display, plus
 * `\(...\)` and `\[...\]`). Plain-text math is still allowed as a
 * fallback for trivial expressions that don't benefit from
 * typesetting; anything with fractions, subscripts, integrals,
 * summations, square roots, etc. MUST go through LaTeX. PDF "Save
 * as PDF" snapshots whatever the WebView currently shows, so the
 * user opens the preview, MathJax typesets, then the print
 * captures the typeset DOM.
 *
 * **Language preservation:** [LanguageDetect] picks one of
 * {Hindi-Devanagari, Hinglish, English} from the transcript itself
 * and the directive is stamped in BOLD CAPS at the top of the user
 * prompt — burying the rule in the system prompt was historically
 * not enough; the LLM regularly defaulted to English for Hindi
 * videos. Now the very first thing the model sees is "OUTPUT
 * LANGUAGE: Hindi (Devanagari)…".
 */
object NotesGenerator {

    /**
     * `$` placeholder used inside our prompt / HTML head literal so
     * we can keep the strings as raw triple-quoted blobs without
     * Kotlin interpreting `$identifier` as a template substitution
     * everywhere LaTeX delimiters appear. Replaced with a literal
     * `$` once at object-init time.
     */
    private const val DOL = "@DOL@"

    /**
     * Standalone HTML head fragment that wires up MathJax 3 with
     * the four common LaTeX delimiter pairs (`$…$`, `$$…$$`,
     * `\(…\)`, `\[…\]`).
     *
     * We inject this into both the "wrap a partial response" path
     * (defensive shell in [wrapIfNeeded]) and post-process the
     * full HTML to GUARANTEE math rendering even when the LLM
     * forgets to include the script tag itself.
     *
     * NB: The CDN load needs the WebView to be on a real https
     * origin — see [com.reader.app.ui.screens.notes.NotesScreen]
     * which uses `loadDataWithBaseURL("https://reader.local/", …)`
     * for that reason.
     */
    internal val MATHJAX_HEAD: String = """<script>
window.MathJax = {
  tex: {
    inlineMath:  [['@DOL@','@DOL@'], ['\\(','\\)']],
    displayMath: [['@DOL@@DOL@','@DOL@@DOL@'], ['\\[','\\]']],
    processEscapes: true,
    processEnvironments: true
  },
  options: { skipHtmlTags: ['script','noscript','style','textarea','pre','code'] },
  svg: { fontCache: 'global' },
  startup: {
    ready: function() { MathJax.startup.defaultReady(); }
  }
};
</script>
<script async src="https://cdn.jsdelivr.net/npm/mathjax@3/es5/tex-mml-chtml.js"></script>""".replace(DOL, "\$")

    private val SYSTEM_DIRECTIVE = """
        You are an expert study-notes editor for exam-prep students.
        The text below is the transcript of an educational video. Your
        job is to convert it into a CLEAN, HIGHLY-STRUCTURED, VISUALLY
        ORGANISED HTML revision document a student can scan in seconds
        before an exam.

        ╔════════════════════════════════════════════════════════════╗
        ║  RULE 0 — LANGUAGE (NON-NEGOTIABLE, READ FIRST)            ║
        ╚════════════════════════════════════════════════════════════╝
        The user prompt starts with a line "OUTPUT LANGUAGE: <lang>".
        That is the ONLY language you may use for body content
        (headings, paragraphs, bullets, callouts, table cells,
        examples). DO NOT translate the transcript. DO NOT switch to
        English for the notes when the transcript is Hindi /
        Hinglish. If the transcript is in Devanagari Hindi, the
        notes are in Devanagari Hindi. If the transcript is
        Hinglish (Hindi in Roman script), notes are Hinglish — keep
        the same code-switching ratio you see in the transcript.
        Technical terms ("integration", "torque", "percentage") may
        stay in English; everything else MUST match the source
        language.

        ╔════════════════════════════════════════════════════════════╗
        ║  CRITICAL — WHAT YOU MUST NOT DO                           ║
        ╚════════════════════════════════════════════════════════════╝
        - DO NOT just copy the transcript with `<p>` tags around each
          paragraph. The student already has the video. Pasting the
          transcript with HTML wrapping is USELESS and the WRONG
          OUTPUT.
        - DO NOT produce a single wall of prose. Long unbroken
          paragraphs are the failure mode this prompt exists to
          prevent.
        - DO NOT keep verbal filler ("ab dekho", "achchha", "uh", "so
          basically", "main keh raha hoon ki"), repeats, or off-topic
          asides.
        - DO NOT keep the transcript's exact sentence structure. The
          student has heard it once; what they need now is the
          information re-arranged for revision.

        ╔════════════════════════════════════════════════════════════╗
        ║  CRITICAL — WHAT YOU MUST DO                               ║
        ╚════════════════════════════════════════════════════════════╝
        Treat this as a re-write, not a wrap. For every concept the
        teacher covers, decide its proper place in the notes
        (definition / theory / formula / worked example / common
        mistake / summary) and put it under the matching heading with
        the matching structural element. Aim for ~50-70% of the
        transcript's length but 100% of its teaching content.

        ╔════════════════════════════════════════════════════════════╗
        ║  REQUIRED STRUCTURE — USE EVERY APPLICABLE ELEMENT          ║
        ╚════════════════════════════════════════════════════════════╝
        1.  `<h1>` — exactly ONE, the topic title, at the very top.
        2.  `<nav class="toc">` containing `<ol>` of `<a href="#id">`
            anchor links — ONE entry per `<h2>` in document order. The
            student MUST be able to jump to any section in 1 tap. This
            is non-negotiable.
        3.  `<h2 id="…">` — major sections. Use 4-10 of them. Never
            one giant section. Suggested groupings (pick what fits
            the content):
              • Quick overview / Kya seekhna hai
              • Definitions / Concepts
              • Theory / Approach
              • Worked examples
              • Step-by-step solutions
              • Common mistakes / Galtiyan
              • Summary / Revision checklist
        4.  `<h3>` — subsections inside an `<h2>`.
        5.  `<h4>` — only when actually nested (e.g. Case 1 / Case 2
            inside an example).
        6.  `<ul>` and `<ol>` — USE AGGRESSIVELY. If you have 3+
            related points in prose, convert them to a list. List
            items should each be ONE clear short line, not a
            paragraph.
        7.  `<p>` — ONLY for genuinely linear narrative bridges
            between sections. Default to lists.
        8.  `<div class="callout important">` — the key idea of each
            section. Open with `<strong>Key Point:</strong>`.
        9.  `<div class="callout note">` — for "yaad rakhna" tips,
            asides, mnemonics. Open with `<strong>Note:</strong>`.
        10. `<div class="callout example">` — every worked example in
            its own block. Number them: `<strong>Example 1:</strong>`,
            etc.
        11. `<div class="callout formula">` — every formula in its own
            box.
        12. `<div class="callout warning">` — common mistake / "yeh
            galti mat karna" point.
        13. `<div class="callout summary"><strong>Summary:</strong>
            <ul>…</ul></div>` — at the end of EVERY `<h2>` section, a
            3-5 bullet recap. Non-negotiable.
        14. `<table>` with `<thead>` / `<tbody>` — when comparing 2+
            things (definitions vs examples, before vs after, etc.).
            Only when it actually helps.
        15. `<ol class="solution"><li>Step 1: …</li>…</ol>` — for any
            worked problem with steps. Each step a separate `<li>`.

        ╔════════════════════════════════════════════════════════════╗
        ║  MATH RENDERING — USE LaTeX, NOT PLAIN TEXT                ║
        ╚════════════════════════════════════════════════════════════╝
        The page LOADS MathJax 3. You MUST emit math expressions in
        LaTeX so they typeset properly:

        - INLINE math: wrap in single dollar signs (or `\(...\)`).
          Example: `velocity @DOL@v = u + at@DOL@ ke formula se nikalta hai.`
        - DISPLAY math (own line, centered): wrap in double dollars
          (or `\[...\]`).
          Example: `@DOL@@DOL@ E = mc^{2} @DOL@@DOL@`
        - Inside `<div class="callout formula">`, put the formula on
          its own line in display math. Example:
          `<div class="callout formula">@DOL@@DOL@ \frac{d}{dx}(\sin x) = \cos x @DOL@@DOL@</div>`
        - Use proper LaTeX commands for fractions (`\frac{a}{b}`),
          exponents (`x^{2}`), subscripts (`a_{n}`), Greek letters
          (`\pi`, `\theta`, `\Delta`), roots (`\sqrt{x}`),
          summations (`\sum_{i=1}^{n}`), integrals (`\int_0^1`),
          vectors (`\vec{F}`), matrices (`\begin{pmatrix}…\end{pmatrix}`),
          and any structured math.
        - Plain-text math is OK ONLY for trivial inline asides
          ("speed × time"). Anything with structure → LaTeX.
        - DO NOT escape dollar signs. DO NOT use unicode like ²/³ to
          mimic exponents — write `x^{2}` so MathJax handles it.
        - The host wraps the page with the MathJax script tag itself,
          so you do not need to add it; just emit clean LaTeX.

        ╔════════════════════════════════════════════════════════════╗
        ║  VISUAL HIERARCHY RULES                                     ║
        ╚════════════════════════════════════════════════════════════╝
        - Every `<h2>` opens with a 1-line intro `<p>` BEFORE bullets.
        - Every `<h2>` closes with a `.callout.summary`.
        - No paragraph longer than ~120 words. Break into list / sub-
          headings if it grows.
        - Use `<strong>` to highlight the technical term being
          defined; `<em>` for emphasis on a verb / mnemonic. Do not
          over-bold — one or two per paragraph is enough.

        ╔════════════════════════════════════════════════════════════╗
        ║  LANGUAGE — WHEN TO SIMPLIFY                                ║
        ╚════════════════════════════════════════════════════════════╝
        - PRESERVE technical terms verbatim ("integration", "torque",
          "ज्यामितीय माध्य", etc.).
        - SIMPLIFY long sentences into shorter, student-friendly form.
          Replace verbose phrasings with plain ones.
          Example BEFORE (Hinglish): "Toh ab humein yeh dekhna hai
          ki jab koi force apply ho raha hai us object pe, aur woh
          object move nahi kar raha hai, toh kya hoga…"
          Example AFTER (Hinglish): "<strong>Static friction</strong>
          tab kaam karta hai jab object par force lage par object
          hile nahi."
        - Where the teacher uses an analogy or memory trick, keep it
          but in a `.callout.note`.

        ╔════════════════════════════════════════════════════════════╗
        ║  REQUIRED <style> BLOCK                                     ║
        ╚════════════════════════════════════════════════════════════╝
        You MUST emit a `<style>` block in `<head>` that produces a
        visually-distinct, print-ready document. Required rules:

        - `@page { size: A4; margin: 1.8cm; }`.
        - System sans-serif body, 11pt, line-height 1.55.
        - `h1` 22pt bold, with a thin 2px bottom border.
        - `h2` 16pt bold, with a 2px bottom accent border (use a
          warm colour like `#cc6611`) and 24px top margin.
        - `h3` 13pt bold; `h4` 12pt bold italic.
        - `nav.toc` — light grey background `#f5f5f0`, 12px padding,
          rounded 6px corners. Inner `ol` numbered, anchor links
          underlined and in the accent colour.
        - `.callout` base — left 4px solid border, 10px 14px padding,
          margin 12px 0, border-radius 6px, `page-break-inside: avoid`.
        - `.callout.important` — background `#fff3e0`, border `#ff9800`.
        - `.callout.note`      — background `#e8f4fd`, border `#1e88e5`.
        - `.callout.example`   — background `#e9f5ec`, border `#43a047`.
        - `.callout.formula`   — background `#f5f0fa`, border `#8e24aa`.
        - `.callout.warning`   — background `#ffebee`, border `#e53935`.
        - `.callout.summary`   — background `#fff8e1`, border `#fbc02d`.
        - `ul`, `ol` — 12px top margin, 6px between items.
        - `ol.solution li` — 8px between steps, slight left padding.
        - `table` — full collapse borders, header row in `#eaeaea`
          background, cells 6px 8px padding, `page-break-inside:
          avoid`.
        - `.muted` — `color: #888; font-style: italic;`.

        ╔════════════════════════════════════════════════════════════╗
        ║  OUTPUT REQUIREMENTS                                        ║
        ╚════════════════════════════════════════════════════════════╝
        - Reply with EXACTLY one COMPLETE HTML document starting with
          `<!DOCTYPE html>` and ending with `</html>`.
        - No prose before or after. No markdown fences. No
          `Here is the document:` preamble.
        - If part of the transcript is unintelligible, write
          `<span class="muted">[transcript spasht nahi]</span>`
          rather than inventing.
        - All teaching content from the transcript must appear; no
          omissions. But re-organised, not pasted.
        - The document MUST contain at least 3 `<h2>` sections and
          end with `</html>`. Truncated output = wrong output;
          re-balance section length so the whole document fits.
    """.trimIndent().replace(DOL, "\$")

    /**
     * Read-only handle to the built-in default system prompt, so the
     * Notes settings UI can pre-fill its custom-prompt editor with
     * "what we'd send by default" for the user to edit. The user's
     * unsaved edits are kept in the screen's state; persisting them
     * happens via [com.reader.app.data.repository.NotesRepository.saveCustomization].
     */
    val defaultSystemPrompt: String get() = SYSTEM_DIRECTIVE

    /**
     * Generate one HTML document for the given transcript.
     *
     * Pipeline:
     *  1. Resolve the OUTPUT LANGUAGE: explicit [languageOverride]
     *     wins, otherwise auto-detect from the transcript. The line
     *     is stamped at the top of the user prompt regardless of
     *     whether [customSystemPrompt] is set, so the model always
     *     knows the target language.
     *  2. Choose the system prompt: either [customSystemPrompt] (used
     *     EXACTLY as given — no app-side wrapping, per the spec
     *     "jab user Apne hisab se kuchh kar raha hai to completely
     *     uske hisab Se Hi hone dijiye") or the built-in
     *     [SYSTEM_DIRECTIVE] default.
     *  3. Stream-accumulate the LLM response (16 K output tokens; the
     *     stream beats the non-streaming HTTP read timeout and avoids
     *     silent `max_tokens=4096` truncation).
     *  4. Default-prompt path: validate structure
     *     ([looksLikeWellFormedNotes]), retry ONCE on first-attempt
     *     malformed output, then strip fences + wrap-if-needed +
     *     inject MathJax — the wrapping shell is what makes the
     *     "first regenerate just works" UX possible for the default
     *     prompt.
     *  5. Custom-prompt path: only stripFences + retry-once-if-empty.
     *     No structural validation, no shell wrapping, no MathJax
     *     injection — the user said exact, so the LLM's output goes
     *     to the WebView unchanged. If their prompt produces HTML
     *     with math, they include MathJax in the HTML themselves.
     *
     * @param customSystemPrompt user-supplied system prompt to use
     *   verbatim, or null to use the built-in default. Blank treated
     *   the same as null.
     * @param languageOverride explicit OUTPUT LANGUAGE, or null to
     *   auto-detect from the transcript.
     */
    suspend fun generate(
        config: ApiConfig,
        title: String,
        transcript: String,
        customSystemPrompt: String? = null,
        languageOverride: LanguageDetect.Lang? = null,
    ): Result<String> = runCatching {
        require(transcript.isNotBlank()) { "transcript is empty" }
        val lang = languageOverride ?: LanguageDetect.detect(transcript)

        val custom = customSystemPrompt?.takeIf { it.isNotBlank() }
        val systemPrompt = custom ?: SYSTEM_DIRECTIVE
        val isCustom = custom != null

        // First attempt.
        var raw = oneShot(config, title, transcript, lang, systemPrompt, retryNudge = false)
        var html: String

        if (isCustom) {
            // Custom-prompt path — minimal post-processing. Retry
            // once only if the LLM came back essentially empty
            // (stripped of fences + whitespace, < 100 chars). Anything
            // longer is what the user's prompt asked for and we
            // respect it as-is.
            if (stripFences(raw).trim().length < 100) {
                raw = oneShot(config, title, transcript, lang, systemPrompt, retryNudge = true)
            }
            html = stripFences(raw)
        } else {
            // Default-prompt path — full validation + structural
            // wrap + MathJax injection (existing behaviour).
            html = postProcess(raw, title)
            if (!looksLikeWellFormedNotes(html)) {
                // The first attempt produced something we can't ship.
                // Try once more with an explicit "your last reply was
                // malformed" nudge before giving up — this catches
                // the most common regenerate-loop the user used to
                // hit (truncated stream, empty body, single <p>-
                // wrapped wall of text, etc.). If retry also fails,
                // we still return what we got — the wrapper turns a
                // broken fragment into a readable shell at least.
                raw = oneShot(config, title, transcript, lang, systemPrompt, retryNudge = true)
                val retried = postProcess(raw, title)
                if (looksLikeWellFormedNotes(retried)) html = retried
                else if (retried.length > html.length) html = retried
            }
        }
        html
    }

    /** One round-trip to the LLM with the structured-notes prompt. */
    private suspend fun oneShot(
        config: ApiConfig,
        title: String,
        transcript: String,
        lang: LanguageDetect.Lang,
        systemPrompt: String,
        retryNudge: Boolean,
    ): String {
        val nudge = if (retryNudge) {
            "\n\nIMPORTANT: Your previous attempt was malformed (truncated, " +
                "empty, or just a wall of <p> tags). This time, emit a " +
                "COMPLETE document. Do not stop early.\n"
        } else ""
        val raw = LlmRepository().askStreamingFull(
            config       = config,
            systemPrompt = systemPrompt,
            userPrompt   = buildString {
                // Stamp the language directive at the very top of the
                // user prompt so the model literally cannot miss it.
                // We always emit this — even with a custom system
                // prompt — because the language picker is its own
                // user-facing knob and the user expects it to apply
                // regardless of which prompt is active.
                append("OUTPUT LANGUAGE: ").append(lang.directive).append('\n')
                append("DOCUMENT TITLE: ").append(title.trim().ifBlank { "Notes" }).append("\n")
                append(nudge)
                append("\nTRANSCRIPT:\n").append(transcript)
            },
            maxTokens   = 16_384,
            // 0.5 keeps output structured / predictable while still
            // allowing the model to rephrase clearly for a student
            // audience.
            temperature = 0.5,
        ).getOrThrow()
        return raw
    }

    /** stripFences → wrapIfNeeded → ensure MathJax script in <head>. */
    private fun postProcess(raw: String, title: String): String {
        val unfenced = stripFences(raw)
        val shelled = wrapIfNeeded(unfenced, title)
        return ensureMathJaxScript(shelled)
    }

    /**
     * Returns true if the document looks like a complete notes
     * artifact — has a top-level shell, at least one major section,
     * a closing `</html>`, and a non-trivial body length. Used to
     * decide whether to retry generation on the first try.
     */
    private fun looksLikeWellFormedNotes(html: String): Boolean {
        if (html.length < 800) return false
        val lower = html.lowercase()
        if (!(lower.contains("<html") || lower.startsWith("<!doctype"))) return false
        if (!lower.contains("</html>")) return false
        // Need at least one h2 — a bare h1 + paragraphs is the very
        // failure mode the prompt exists to prevent.
        if (!lower.contains("<h2")) return false
        return true
    }

    /**
     * Strip ```html / ``` markdown fences if the LLM wrapped its
     * answer.
     */
    private fun stripFences(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val firstNewline = s.indexOf('\n')
            if (firstNewline > 0) s = s.substring(firstNewline + 1)
            val closing = s.lastIndexOf("```")
            if (closing > 0) s = s.substring(0, closing)
            s = s.trim()
        }
        return s
    }

    /**
     * Defensive wrapper — if the LLM forgot `<html>` / `<body>` (it
     * sometimes returns just the section markup) we wrap it in a
     * default shell so the WebView still renders something readable
     * and the PDF export keeps working.
     *
     * The shell ships the same callout / TOC / page-print CSS the
     * prompt asks the LLM for, plus the MathJax loader, so even on
     * the degraded path the document is structured, math typesets,
     * and Save as PDF works.
     */
    private fun wrapIfNeeded(html: String, title: String): String {
        val trimmed = html.trim()
        val looksLikeFullDoc = trimmed.contains("<html", ignoreCase = true) ||
            trimmed.startsWith("<!DOCTYPE", ignoreCase = true)
        if (looksLikeFullDoc) return trimmed
        val safeTitle = title.replace("<", "&lt;").replace(">", "&gt;")
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <title>$safeTitle</title>
              $MATHJAX_HEAD
              <style>
                @page { size: A4; margin: 1.8cm; }
                body { font-family: -apple-system, "Segoe UI", Roboto, sans-serif;
                       font-size: 11pt; line-height: 1.55; color: #1d1d1f; }
                h1 { font-size: 22pt; font-weight: 700; border-bottom: 2px solid #1d1d1f; padding-bottom: 6px; }
                h2 { font-size: 16pt; font-weight: 700; border-bottom: 2px solid #cc6611;
                     padding-bottom: 4px; margin-top: 24px; }
                h3 { font-size: 13pt; font-weight: 700; }
                h4 { font-size: 12pt; font-weight: 700; font-style: italic; }
                nav.toc { background: #f5f5f0; padding: 12px 16px; border-radius: 6px;
                          margin: 16px 0; page-break-inside: avoid; }
                nav.toc ol { margin: 4px 0 4px 24px; padding: 0; }
                nav.toc a { color: #cc6611; text-decoration: underline; }
                ul, ol { margin-top: 12px; }
                ul li, ol li { margin-bottom: 6px; }
                ol.solution li { margin-bottom: 8px; padding-left: 4px; }
                .callout { border-left: 4px solid #888; padding: 10px 14px;
                           margin: 12px 0; border-radius: 6px;
                           page-break-inside: avoid; }
                .callout.important { background: #fff3e0; border-color: #ff9800; }
                .callout.note      { background: #e8f4fd; border-color: #1e88e5; }
                .callout.example   { background: #e9f5ec; border-color: #43a047; }
                .callout.formula   { background: #f5f0fa; border-color: #8e24aa; }
                .callout.warning   { background: #ffebee; border-color: #e53935; }
                .callout.summary   { background: #fff8e1; border-color: #fbc02d; }
                table { border-collapse: collapse; width: 100%;
                        page-break-inside: avoid; margin: 12px 0; }
                th, td { border: 1px solid #d0d0c8; padding: 6px 8px; text-align: left; }
                thead th { background: #eaeaea; }
                .muted { color: #888; font-style: italic; }
                mjx-container[display="true"] { margin: 12px 0; }
              </style>
            </head>
            <body>
              <h1>$safeTitle</h1>
              $trimmed
            </body>
            </html>
        """.trimIndent()
    }

    /**
     * If the LLM produced a full `<html>` document but forgot to
     * include the MathJax script, splice our standard MathJax head
     * fragment into the existing `<head>` (or just before
     * `</head>`). This is the difference between "the LLM gets the
     * details right" and "math actually renders for the user" —
     * we'd rather be correct unconditionally than depend on prompt
     * compliance.
     */
    private fun ensureMathJaxScript(html: String): String {
        val lower = html.lowercase()
        if (lower.contains("mathjax") || lower.contains("/tex-mml-chtml")) {
            return html  // already present, don't double-load
        }
        val headEnd = lower.indexOf("</head>")
        if (headEnd >= 0) {
            return html.substring(0, headEnd) + "\n" + MATHJAX_HEAD + "\n" + html.substring(headEnd)
        }
        // No </head> tag at all — the wrapIfNeeded shell injects one,
        // so we only get here for full <html> documents the LLM
        // emitted without a head close. Splice before <body> as a
        // last resort.
        val bodyStart = lower.indexOf("<body")
        if (bodyStart >= 0) {
            return html.substring(0, bodyStart) + "<head>" + MATHJAX_HEAD + "</head>\n" +
                html.substring(bodyStart)
        }
        // Pathological: no body either. Prepend MathJax + return; the
        // WebView will still render any markup that follows.
        return MATHJAX_HEAD + "\n" + html
    }
}
