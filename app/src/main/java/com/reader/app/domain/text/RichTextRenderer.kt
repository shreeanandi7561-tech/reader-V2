package com.reader.app.domain.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Markdown + simple-LaTeX renderer.
 *
 * Renders raw text into a [Rendered] pair:
 *
 *  - **plain**: the same text with markdown syntax stripped and
 *    whitespace normalised. This is the string we feed to TTS.
 *  - **annotated**: an [AnnotatedString] of the SAME plain text, with
 *    span styles applied for `**bold**`, `*italic*`, `` `code` ``,
 *    headings, bullets, blockquotes, links and inline `$math$`.
 *
 * The two strings have identical character indices on purpose. The TTS
 * engine reports the word it is currently pronouncing as a char range
 * inside *plain*, and the chat row applies that range as an
 * additional span on top of *annotated* — so word-level highlighting
 * works the same way for AI answers, book paragraphs and any other
 * piece of text the app reads aloud.
 *
 * **Math** is intentionally simple. We do NOT spin up a WebView for
 * MathJax — that would break the per-word highlight contract and add
 * heavy weight per chat row. Instead, [LatexLite] converts common
 * LaTeX into a Unicode + spoken-form mix (Greek letters → π/θ/…,
 * `\times` → ×, `x^2` → x², `\frac{a}{b}` → (a)/(b), `x_{n}` → ` sub n`)
 * which both displays cleanly and reads naturally through the TTS
 * engine.
 *
 * The contract `MarkdownStripper.strip(raw) == render(raw).plain`
 * holds — see [MarkdownStripper] which delegates here.
 */
object RichTextRenderer {

    data class Rendered(val plain: String, val annotated: AnnotatedString)

    /** Theme-friendly colour overrides; defaults work on light or dark. */
    data class Palette(
        val codeBg: Color   = Color(0x14888888),
        val codeFg: Color   = Color(0xFFD15B5B),
        val mathFg: Color   = Color(0xFF1E6FB8),
        val linkFg: Color   = Color(0xFF1E6FB8),
        val mutedFg: Color  = Color(0xFF8A8A8A)
    )

    fun render(raw: String, palette: Palette = Palette()): Rendered {
        if (raw.isBlank()) return Rendered("", AnnotatedString(""))
        val ctx = RenderCtx(palette)
        ctx.runBlocks(raw)
        ctx.normalize()
        return ctx.build()
    }
}

/* ------------------------------------------------------------------- */
/* Internal renderer machinery                                          */
/* ------------------------------------------------------------------- */

private data class SpanRec(val start: Int, val end: Int, val style: SpanStyle)

private class RenderCtx(pal: RichTextRenderer.Palette) {
    val sb    = StringBuilder()
    val spans = mutableListOf<SpanRec>()

    val boldStyle   = SpanStyle(fontWeight = FontWeight.Bold)
    val italicStyle = SpanStyle(fontStyle = FontStyle.Italic)
    val strikeStyle = SpanStyle(textDecoration = TextDecoration.LineThrough)
    val codeStyle   = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = pal.codeBg,
        color      = pal.codeFg
    )
    val mathStyle   = SpanStyle(
        fontFamily = FontFamily.Monospace,
        color      = pal.mathFg,
        fontStyle  = FontStyle.Italic
    )
    val linkStyle   = SpanStyle(
        color          = pal.linkFg,
        textDecoration = TextDecoration.Underline
    )
    val h1Style     = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp)
    val h2Style     = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp)
    val h3Style     = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)
    val h4Style     = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp)
    val mutedStyle  = SpanStyle(color = pal.mutedFg)
    /**
     * Monospace + slightly smaller body for GFM-style pipe tables. We
     * deliberately don't render tables with a fancy column widget — the
     * plain string MUST stay byte-identical to what TTS reads, so the
     * cleanest approach is "monospace text with whitespace-padded
     * columns" which both displays cleanly and reads naturally
     * left-to-right cell by cell.
     */
    val tableStyle  = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)

    /* ------- block-level pass ------- */

    fun runBlocks(raw: String) {
        // Lift fenced code blocks out FIRST so the math-delimiter and
        // line passes can't mistake their inner content for math, headings,
        // bullets, etc. Code blocks come back in as monospace spans below.
        val (markedRaw0, codeBlocks) = extractFencedCode(raw)

        // Then rewrite display-math delimiters ($$…$$, \(…\), \[…\]) into
        // the inline `$…$` form. Doing this AFTER the code-block lift means
        // we never touch math-looking syntax inside ``` … ``` bodies.
        val markedRaw = normalizeMathDelimiters(markedRaw0)

        val lines = markedRaw.split('\n')
        var i = 0
        while (i < lines.size) {
            if (i > 0) appendChar('\n')
            val line = lines[i]

            // Try to consume a multi-line GFM pipe table starting here.
            // Tables span N+2 consecutive lines (header + separator + body),
            // so we lookahead instead of relying on per-line dispatch.
            val table = tryParseTable(lines, i)
            if (table != null) {
                renderTable(table.first)
                i = table.second
                continue
            }

            val placeholder = PLACEHOLDER_REGEX.matchEntire(line)
            if (placeholder != null) {
                val idx = placeholder.groupValues[1].toInt()
                val codeBody = codeBlocks.getOrNull(idx).orEmpty()
                val start = sb.length
                appendString(codeBody)
                if (sb.length > start) {
                    spans += SpanRec(start, sb.length, codeStyle)
                }
                i++
                continue
            }

            renderLineBlock(line)
            i++
        }
    }

    private fun renderLineBlock(line: String) {
        // Heading: # … through ###### …
        HEADING_REGEX.matchEntire(line)?.let { m ->
            val level   = m.groupValues[1].length
            val content = m.groupValues[2]
            val start   = sb.length
            renderInline(content)
            val style = when (level) {
                1    -> h1Style
                2    -> h2Style
                3    -> h3Style
                else -> h4Style
            }
            if (sb.length > start) spans += SpanRec(start, sb.length, style)
            return
        }

        // Bullet: dash/plus/asterisk item   →   drop marker, keep content
        BULLET_REGEX.matchEntire(line)?.let { m ->
            renderInline(m.groupValues[1])
            return
        }

        // Numbered: 1. item    →   drop marker, keep content
        NUMBERED_REGEX.matchEntire(line)?.let { m ->
            renderInline(m.groupValues[1])
            return
        }

        // Blockquote: > … →   italic on the line content
        BLOCKQUOTE_REGEX.matchEntire(line)?.let { m ->
            val start = sb.length
            renderInline(m.groupValues[1])
            if (sb.length > start) spans += SpanRec(start, sb.length, italicStyle)
            return
        }

        // Horizontal rule — drop entirely.
        if (HORIZONTAL_REGEX.matches(line)) return

        renderInline(line)
    }

    /* ------- inline pass ------- */

    /**
     * Walk [text] character-by-character and append to [sb] with style
     * spans recorded in [spans]. Recursive — bold/italic content can
     * itself contain inline markdown.
     */
    private fun renderInline(text: String) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                // Inline code: `…`
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        val start = sb.length
                        appendString(text.substring(i + 1, end))
                        if (sb.length > start) spans += SpanRec(start, sb.length, codeStyle)
                        i = end + 1
                        continue
                    }
                }

                // Bold: **…**
                c == '*' && i + 1 < text.length && text[i + 1] == '*' -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1 && end > i + 2) {
                        val start = sb.length
                        renderInline(text.substring(i + 2, end))
                        if (sb.length > start) spans += SpanRec(start, sb.length, boldStyle)
                        i = end + 2
                        continue
                    }
                }

                // Bold underscore: __…__
                c == '_' && i + 1 < text.length && text[i + 1] == '_' -> {
                    val end = text.indexOf("__", i + 2)
                    if (end != -1 && end > i + 2) {
                        val start = sb.length
                        renderInline(text.substring(i + 2, end))
                        if (sb.length > start) spans += SpanRec(start, sb.length, boldStyle)
                        i = end + 2
                        continue
                    }
                }

                // Strikethrough: ~~…~~
                c == '~' && i + 1 < text.length && text[i + 1] == '~' -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end != -1 && end > i + 2) {
                        val start = sb.length
                        renderInline(text.substring(i + 2, end))
                        if (sb.length > start) spans += SpanRec(start, sb.length, strikeStyle)
                        i = end + 2
                        continue
                    }
                }

                // Italic: *…*  (word-boundary aware to dodge "1.5 * 2" etc.)
                c == '*' -> {
                    val end = findSingleClose(text, '*', i + 1)
                    if (end != -1 && isItalicValid(text, i, end)) {
                        val start = sb.length
                        renderInline(text.substring(i + 1, end))
                        if (sb.length > start) spans += SpanRec(start, sb.length, italicStyle)
                        i = end + 1
                        continue
                    }
                }

                // Italic underscore: _…_
                c == '_' -> {
                    val end = findSingleClose(text, '_', i + 1)
                    if (end != -1 && isItalicValid(text, i, end)) {
                        val start = sb.length
                        renderInline(text.substring(i + 1, end))
                        if (sb.length > start) spans += SpanRec(start, sb.length, italicStyle)
                        i = end + 1
                        continue
                    }
                }

                // Inline math: $…$  →  LatexLite + math span
                c == '$' -> {
                    val end = text.indexOf('$', i + 1)
                    if (end != -1 && end > i + 1) {
                        val src = text.substring(i + 1, end)
                        val readable = LatexLite.toReadable(src)
                        val start = sb.length
                        appendString(readable)
                        if (sb.length > start) spans += SpanRec(start, sb.length, mathStyle)
                        i = end + 1
                        continue
                    }
                }

                // Image: ![alt](url) → keep alt only, no link span.
                c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
                    val parsed = parseLink(text, i + 1)
                    if (parsed != null) {
                        appendString(parsed.first)
                        i = parsed.second
                        continue
                    }
                }

                // Link: [text](url) → render text inline, link span on top.
                c == '[' -> {
                    val parsed = parseLink(text, i)
                    if (parsed != null) {
                        val start = sb.length
                        renderInline(parsed.first)
                        if (sb.length > start) spans += SpanRec(start, sb.length, linkStyle)
                        i = parsed.second
                        continue
                    }
                }
            }
            appendChar(c)
            i++
        }
    }

    /* ------- helpers ------- */

    /** Append a single char with whitespace coalescing. */
    private fun appendChar(c: Char) {
        when {
            c == '\n' -> {
                // No more than 2 consecutive newlines in the output —
                // mirrors the old MarkdownStripper.MULTI_NEWLINE rule.
                val len = sb.length
                if (len >= 2 && sb[len - 1] == '\n' && sb[len - 2] == '\n') return
                sb.append('\n')
            }
            c == ' ' || c == '\t' -> {
                if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') return
                sb.append(' ')
            }
            else -> sb.append(c)
        }
    }

    private fun appendString(s: String) {
        for (c in s) appendChar(c)
    }

    /**
     * Find the first occurrence of [ch] in [text] from [fromIdx] that is
     * NOT part of a doubled marker (e.g. when scanning for a single `*`
     * close, `**` doesn't count).
     */
    private fun findSingleClose(text: String, ch: Char, fromIdx: Int): Int {
        var idx = fromIdx
        while (idx < text.length) {
            val pos = text.indexOf(ch, idx)
            if (pos == -1) return -1
            if (pos + 1 < text.length && text[pos + 1] == ch) {
                idx = pos + 2
                continue
            }
            return pos
        }
        return -1
    }

    /**
     * Italic markers are only valid when the chars immediately outside
     * them are NOT word characters. This avoids treating `1*2*3` as an
     * italic run, and `word_with_underscores` from being italicized.
     */
    private fun isItalicValid(text: String, openIdx: Int, closeIdx: Int): Boolean {
        if (closeIdx <= openIdx) return false
        val before     = if (openIdx == 0) ' ' else text[openIdx - 1]
        val afterClose = if (closeIdx + 1 >= text.length) ' ' else text[closeIdx + 1]
        fun isWordy(c: Char) = c.isLetterOrDigit() || c == '_' || c == '*'
        if (isWordy(before)) return false
        if (isWordy(afterClose)) return false
        // Don't allow empty italic *  *  or pure-whitespace content.
        return text.substring(openIdx + 1, closeIdx).any { !it.isWhitespace() }
    }

    /**
     * Parse a markdown link `[text](url)` starting at [openIdx] (must
     * point at `[`). Returns (text, indexAfterClosingParen) or null.
     */
    private fun parseLink(text: String, openIdx: Int): Pair<String, Int>? {
        if (openIdx >= text.length || text[openIdx] != '[') return null
        // Match the FIRST `]` that is followed by `(`. Inner brackets in
        // the link text aren't supported (rare in our domain).
        var search = openIdx + 1
        while (search < text.length) {
            val close = text.indexOf(']', search)
            if (close == -1) return null
            if (close + 1 < text.length && text[close + 1] == '(') {
                val closeParen = text.indexOf(')', close + 2)
                if (closeParen == -1) return null
                val linkText = text.substring(openIdx + 1, close)
                return linkText to (closeParen + 1)
            }
            search = close + 1
        }
        return null
    }

    /* ------- normalisation + final assembly ------- */

    fun normalize() {
        // Trim leading whitespace, shifting span starts.
        var leading = 0
        while (leading < sb.length && sb[leading].isWhitespace()) leading++
        if (leading > 0) {
            sb.delete(0, leading)
            for (i in spans.indices) {
                val s = spans[i]
                spans[i] = SpanRec(
                    (s.start - leading).coerceAtLeast(0),
                    (s.end   - leading).coerceAtLeast(0),
                    s.style
                )
            }
        }
        // Trim trailing whitespace, clamping span ends.
        var trailing = sb.length
        while (trailing > 0 && sb[trailing - 1].isWhitespace()) trailing--
        if (trailing < sb.length) {
            sb.delete(trailing, sb.length)
            for (i in spans.indices) {
                val s = spans[i]
                spans[i] = SpanRec(
                    s.start.coerceAtMost(sb.length),
                    s.end.coerceAtMost(sb.length),
                    s.style
                )
            }
        }
    }

    fun build(): RichTextRenderer.Rendered {
        val plain = sb.toString()
        val annotated = buildAnnotatedString {
            append(plain)
            for (sp in spans) {
                if (sp.start in 0 until plain.length &&
                    sp.end > sp.start &&
                    sp.end <= plain.length
                ) {
                    addStyle(sp.style, sp.start, sp.end)
                }
            }
        }
        return RichTextRenderer.Rendered(plain, annotated)
    }

    /**
     * Lift ``` … ``` fenced code blocks out of the raw text so they
     * survive the line-by-line markdown pass intact. Each block is
     * replaced with a placeholder line `<NUL>FENCEDCODE<idx><SOH>` that
     * [PLACEHOLDER_REGEX] matches against in [runBlocks].
     */
    private fun extractFencedCode(raw: String): Pair<String, List<String>> {
        if (!raw.contains("```")) return raw to emptyList()
        val out = StringBuilder(raw.length)
        val codeBlocks = mutableListOf<String>()
        var i = 0
        while (i < raw.length) {
            val atLineStart = i == 0 || raw[i - 1] == '\n'
            if (atLineStart && raw.startsWith("```", i)) {
                val openLineEnd = raw.indexOf('\n', i + 3).let { if (it == -1) raw.length else it }
                val close = raw.indexOf("\n```", openLineEnd)
                if (close != -1) {
                    val codeBody = raw.substring(openLineEnd + 1, close)
                    val id = codeBlocks.size
                    codeBlocks += codeBody
                    out.append(PLACEHOLDER_PREFIX).append(id).append(PLACEHOLDER_SUFFIX)
                    val closeLineEnd = raw.indexOf('\n', close + 4).let {
                        if (it == -1) raw.length else it
                    }
                    i = closeLineEnd
                    continue
                }
            }
            out.append(raw[i])
            i++
        }
        return out.toString() to codeBlocks
    }

    /**
     * Pre-processor that rewrites display-math delimiters into the inline
     * `$…$` form so the existing inline-math path handles them
     * transparently. Recognised inputs:
     *
     *  - `$$X$$`   →  `$X$`
     *  - `\(X\)`   →  `$X$`
     *  - `\[X\]`   →  `$X$`
     *
     * Internal whitespace inside the math is collapsed to single spaces
     * so multi-line `\[ … \]` blocks survive the upcoming line split (the
     * inline parser only sees one line at a time, so the open/close `$`
     * markers MUST end up on the same line).
     *
     * Currency text like "$5" is unaffected — every regex requires a
     * matching pair of delimiters.
     */
    private fun normalizeMathDelimiters(text: String): String {
        if (text.isEmpty()) return text
        var s = text
        s = DOUBLE_DOLLAR_REGEX.replace(s)     { m -> "$" + flattenMath(m.groupValues[1]) + "$" }
        s = BACKSLASH_PAREN_REGEX.replace(s)   { m -> "$" + flattenMath(m.groupValues[1]) + "$" }
        s = BACKSLASH_BRACKET_REGEX.replace(s) { m -> "$" + flattenMath(m.groupValues[1]) + "$" }
        return s
    }

    private fun flattenMath(inner: String): String =
        inner.trim().replace(MATH_WHITESPACE_REGEX, " ")

    /* ------- table parsing + rendering ------- */

    private data class TableSpec(val header: List<String>, val rows: List<List<String>>)

    /**
     * Try to consume a GFM-style pipe table starting at `lines[startIdx]`.
     *
     *     | h1 | h2 |
     *     |----|----|
     *     | c1 | c2 |
     *
     * Outer pipes are required (so `|x|` math inside prose can't trigger a
     * false positive), the separator row must be all `-`/`:` (alignment
     * markers are accepted but ignored), and body rows are normalised to
     * the header arity (short rows pad with empty cells, long rows are
     * truncated).
     *
     * Returns `(spec, indexAfterTable)` or `null` if the lookahead doesn't
     * match a table.
     */
    private fun tryParseTable(lines: List<String>, startIdx: Int): Pair<TableSpec, Int>? {
        if (startIdx + 1 >= lines.size) return null
        val header = parseTableRow(lines[startIdx]) ?: return null
        if (header.isEmpty()) return null
        val sepCells = parseTableRow(lines[startIdx + 1]) ?: return null
        if (sepCells.size != header.size) return null
        if (!sepCells.all { isTableSeparatorCell(it) }) return null

        val rows = mutableListOf<List<String>>()
        var i = startIdx + 2
        while (i < lines.size) {
            val row = parseTableRow(lines[i]) ?: break
            // Pad short rows / truncate long rows to match the header.
            rows += (0 until header.size).map { col -> row.getOrElse(col) { "" } }
            i++
        }
        return TableSpec(header, rows) to i
    }

    private fun parseTableRow(line: String): List<String>? {
        val t = line.trim()
        if (t.length < 2) return null
        if (!t.startsWith("|") || !t.endsWith("|")) return null
        val inner = t.substring(1, t.length - 1)
        return inner.split("|").map { it.trim() }
    }

    private fun isTableSeparatorCell(cell: String): Boolean {
        // Accept :--, --:, :--: alignment markers — we ignore alignment
        // (just left-pad everything) but should still recognise the row.
        val core = cell.trim().trimStart(':').trimEnd(':')
        return core.isNotEmpty() && core.all { it == '-' }
    }

    /**
     * Render the parsed table as monospace text with whitespace-padded
     * columns. The header row gets a `boldStyle` span on top of the
     * table-wide monospace span, so column titles still pop visually.
     *
     * No internal trailing newline — the outer [runBlocks] loop already
     * separates blocks with a single `\n`. The `appendChar('\n')`
     * dedup rule then keeps consecutive blanks at most 2.
     */
    private fun renderTable(spec: TableSpec) {
        val cols = spec.header.size
        if (cols == 0) return
        val widths = IntArray(cols) { col ->
            val all = listOf(spec.header[col]) + spec.rows.map { it.getOrElse(col) { "" } }
            all.maxOf { it.length }
        }

        val tableStart = sb.length

        // Header row — bold.
        val headerStart = sb.length
        appendString(formatTableRow(spec.header, widths))
        val headerEnd = sb.length
        if (headerEnd > headerStart) spans += SpanRec(headerStart, headerEnd, boldStyle)

        // Body rows separated by single newlines (no trailing one).
        for (row in spec.rows) {
            appendChar('\n')
            appendString(formatTableRow(row, widths))
        }

        if (sb.length > tableStart) spans += SpanRec(tableStart, sb.length, tableStyle)
    }

    private fun formatTableRow(cells: List<String>, widths: IntArray): String =
        widths.indices.joinToString("  ") { col ->
            cells.getOrElse(col) { "" }.padEnd(widths[col])
        }

    companion object {
        private val HEADING_REGEX    = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
        private val BULLET_REGEX     = Regex("^\\s*[-*+]\\s+(.*)$")
        private val NUMBERED_REGEX   = Regex("^\\s*\\d+\\.\\s+(.*)$")
        private val BLOCKQUOTE_REGEX = Regex("^\\s*>\\s?(.*)$")
        private val HORIZONTAL_REGEX = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")

        // Display-math delimiters. These three are matched non-greedily;
        // \( and \[ use DOT_MATCHES_ALL so multi-line math blocks survive
        // the rewrite (their content is then whitespace-flattened so the
        // resulting `$…$` lands on one line).
        private val DOUBLE_DOLLAR_REGEX     = Regex("""\$\$([^$]+?)\$\$""")
        private val BACKSLASH_PAREN_REGEX   = Regex("""\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
        private val BACKSLASH_BRACKET_REGEX = Regex("""\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)
        private val MATH_WHITESPACE_REGEX   = Regex("\\s+")

        private const val PLACEHOLDER_PREFIX = "\u0000FENCEDCODE"
        private const val PLACEHOLDER_SUFFIX = "\u0001"
        private val PLACEHOLDER_REGEX = Regex(
            "^${Regex.escape(PLACEHOLDER_PREFIX)}(\\d+)${Regex.escape(PLACEHOLDER_SUFFIX)}$"
        )
    }
}

/* ------------------------------------------------------------------- */
/* Lightweight LaTeX → readable Unicode/spoken-form converter           */
/* ------------------------------------------------------------------- */

/**
 * Best-effort LaTeX simplifier for inline `$…$` math.
 *
 * This is **not** MathJax. We don't render glyph-perfect equations —
 * that would require a WebView and break per-word TTS highlight. Instead
 * we lower LaTeX into a Unicode + spoken-form mix:
 *
 *  - Greek letters → `α β γ Δ Σ Π Ω …`
 *  - Common operators → `× ÷ ± ≤ ≥ ≠ ≈ → ∞ Σ ∫ √ …`
 *  - `\frac{a}{b}`  → `(a)/(b)`
 *  - `\sqrt{x}`     → `√(x)`
 *  - `x^2`/`x_2`    → Unicode super/subscript when possible (²/₂)
 *  - `x^{n}`/`x_{n}`→ `x to the power of n` / `x sub n` (TTS-friendly)
 *
 * The output is plain text, so a TTS engine can pronounce it naturally
 * and per-word highlight works as usual.
 */
internal object LatexLite {

    private val GREEK = mapOf(
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε",
        "\\zeta" to "ζ", "\\eta" to "η",
        "\\theta" to "θ", "\\vartheta" to "ϑ",
        "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ", "\\mu" to "μ",
        "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\varpi" to "ϖ",
        "\\rho" to "ρ", "\\varrho" to "ϱ",
        "\\sigma" to "σ", "\\varsigma" to "ς",
        "\\tau" to "τ", "\\phi" to "φ", "\\varphi" to "ϕ",
        "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Alpha" to "Α", "\\Beta" to "Β", "\\Gamma" to "Γ", "\\Delta" to "Δ",
        "\\Theta" to "Θ", "\\Lambda" to "Λ", "\\Pi" to "Π", "\\Sigma" to "Σ",
        "\\Phi" to "Φ", "\\Psi" to "Ψ", "\\Omega" to "Ω"
    )

    private val OPS = mapOf(
        "\\times" to "×", "\\cdot" to "·", "\\div" to "÷",
        "\\pm" to "±", "\\mp" to "∓",
        "\\leq" to "≤", "\\geq" to "≥", "\\le" to "≤", "\\ge" to "≥",
        "\\neq" to "≠", "\\ne" to "≠",
        "\\approx" to "≈", "\\equiv" to "≡", "\\sim" to "∼",
        "\\to" to "→", "\\rightarrow" to "→", "\\leftarrow" to "←",
        "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\Leftrightarrow" to "⇔",
        "\\infty" to "∞",
        "\\sum" to "Σ", "\\prod" to "Π", "\\int" to "∫",
        "\\partial" to "∂", "\\nabla" to "∇",
        "\\degree" to "°", "\\circ" to "°",
        "\\cdots" to "…", "\\ldots" to "…", "\\dots" to "…",
        "\\cup" to "∪", "\\cap" to "∩",
        "\\subset" to "⊂", "\\supset" to "⊃",
        "\\in" to "∈", "\\notin" to "∉",
        "\\forall" to "∀", "\\exists" to "∃",
        "\\Re" to "ℜ", "\\Im" to "ℑ"
    )

    private val SUPERS = mapOf(
        '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴",
        '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹",
        '+' to "⁺", '-' to "⁻", 'n' to "ⁿ", 'i' to "ⁱ"
    )
    private val SUBS = mapOf(
        '0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄",
        '5' to "₅", '6' to "₆", '7' to "₇", '8' to "₈", '9' to "₉",
        '+' to "₊", '-' to "₋", 'n' to "ₙ", 'i' to "ᵢ"
    )

    private val FRAC_REGEX  = Regex("\\\\frac\\s*\\{([^{}]*)\\}\\s*\\{([^{}]*)\\}")
    private val SQRT_BRACE  = Regex("\\\\sqrt\\s*\\{([^{}]*)\\}")
    private val POW_BRACE   = Regex("\\^\\s*\\{([^{}]+)\\}")
    private val SUB_BRACE   = Regex("_\\s*\\{([^{}]+)\\}")
    private val POW_DIGIT   = Regex("\\^([0-9+\\-ni])")
    private val SUB_DIGIT   = Regex("_([0-9+\\-ni])")
    private val UNKNOWN_CMD = Regex("\\\\([a-zA-Z]+)")
    private val MULTI_SPACE = Regex("\\s+")

    fun toReadable(latex: String): String {
        if (latex.isBlank()) return ""
        // Defensive: strip surrounding `$` and whitespace if the caller
        // left them in. Using Kotlin's char-trim instead of a regex avoids
        // the awkward escaping of `$` inside a Kotlin string literal.
        var s = latex.trim('$', ' ', '\t', '\n')

        // Word-by-word substitutions. Order matters — operators first so
        // `\Sigma` (Greek) doesn't shadow `\sum` (operator), and so on.
        for ((k, v) in OPS)   s = s.replace(k, v)
        for ((k, v) in GREEK) s = s.replace(k, v)

        // \frac / \sqrt with brace arguments.
        s = FRAC_REGEX.replace(s)  { m -> "(${m.groupValues[1].trim()})/(${m.groupValues[2].trim()})" }
        s = SQRT_BRACE.replace(s)  { m -> "√(${m.groupValues[1].trim()})" }

        // x^{...} / x_{...}: spelled-out form keeps TTS readable when the
        // exponent / subscript is more than a single char.
        s = POW_BRACE.replace(s)   { m -> " to the power of ${m.groupValues[1].trim()} " }
        s = SUB_BRACE.replace(s)   { m -> " sub ${m.groupValues[1].trim()} " }

        // Single-char super/subscripts → real Unicode where possible.
        s = POW_DIGIT.replace(s)   { m ->
            val ch = m.groupValues[1][0]
            SUPERS[ch] ?: " to the power of $ch "
        }
        s = SUB_DIGIT.replace(s)   { m ->
            val ch = m.groupValues[1][0]
            SUBS[ch] ?: " sub $ch "
        }

        // Drop any still-present \cmd we don't recognise — keep just the name.
        s = UNKNOWN_CMD.replace(s) { m -> m.groupValues[1] }

        // Strip leftover braces.
        s = s.replace("{", "").replace("}", "")
        // Collapse whitespace.
        s = MULTI_SPACE.replace(s, " ").trim()
        return s
    }
}
