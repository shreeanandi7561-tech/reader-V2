package com.reader.app.domain.youtube

/**
 * Parsed YouTube preview-thumbnail descriptor, captured from the
 * `playerStoryboardSpecRenderer.spec` field on the InnerTube `player`
 * response.
 *
 * YouTube serves these tile-sheets publicly for every video — they are
 * the same images you see hovering over the seek-bar on youtube.com.
 * One JPEG sheet packs many small "preview frames" of the video laid
 * out in a `cols × rows` grid; together with the spec metadata
 * (cellWidth, cellHeight, intervalMs) we can compute exactly which
 * cell of which sheet corresponds to a given playback timestamp and
 * crop it out.
 *
 * # Why this exists
 *
 * The Discussion-mode multimodal frame pipeline already grabs
 * full-resolution frames out of the IFrame WebView's bitmap when that
 * succeeds — see [com.reader.app.ui.video.WebViewFrameSource]. But on
 * Android API levels / device GPU stacks where the video surface is
 * hardware-only and is composited behind (not into) the WebView's
 * `Canvas`, the WebView path returns mostly-black bitmaps the AI
 * can't make sense of.
 *
 * Storyboards are the only on-device, ToS-clean source of timestamp-
 * aligned imagery that works regardless of GPU compositing. They are
 * lower-resolution (typically 160×90 px at the highest level), but
 * for tutorial / whiteboard / slide content modern vision models
 * still extract a lot of useful detail at this size — and a
 * 160×90-px frame is infinitely better than a black rectangle when
 * the WebView path fails.
 *
 * This parser does NOT do any network I/O. It just translates the
 * opaque spec string into a usable structure with a `frameAt(timeSec)`
 * helper that returns the URL + pixel-offsets needed to fetch and crop
 * a single preview frame. The actual sheet fetch + crop lives in
 * [YouTubeStoryboardClient].
 *
 * # Spec format (the reverse-engineered cheat-sheet)
 *
 * ```
 * <baseUrl>|<level0>|<level1>|<level2>
 *
 * baseUrl  : https://i.ytimg.com/sb/<id>/storyboard3_L$L/$M.jpg?sqp=…&sigh=rs$rs
 * level k  : W # H # frames # cols # rows # intervalMs # name # sigh
 *            (some old specs omit `name`; sigh is then the last token)
 *
 * Placeholders in baseUrl that get substituted at fetch-time:
 *   $L  → level index (0, 1, 2 …)
 *   $M  → first replaced by the level's `name` (often `M$M`),
 *         then any remaining `$M` is replaced by the sheet number.
 *   $N  → sheet number (alternative format)
 *   $rs → the level's sigh token (signed query parameter)
 * ```
 *
 * Typical resolutions YouTube exposes:
 *  - L0: 48×27   (single static thumbnail; `name="default"`,
 *                  intervalMs=0  — used for poster, not seek preview)
 *  - L1: 80×45   (10×10 cells/sheet, 5s/cell)
 *  - L2: 160×90  (5×5  cells/sheet, 5s/cell)  ← the highest-res level on most videos
 *
 * Some long videos / hi-def content add an L3 at 240×135 px. The
 * parser keeps every level it finds and exposes them via [levels];
 * the caller (StoryboardClient) usually picks the highest one for
 * the best frame quality.
 *
 * # Defensiveness
 *
 * YouTube has changed this format twice in the past three years
 * (added a `name` field, swapped `$M`/`$N` placeholders, added a
 * sigh-rotation `$rs` token). The parser is therefore very forgiving:
 *  - any level with malformed cell/grid dimensions is skipped, the
 *    rest of the spec still works,
 *  - all placeholders are processed in a single deterministic pass
 *    so legacy URLs that lack `$M` or `$rs` don't double-substitute,
 *  - `frameAt` clamps the requested time to `[0, ∞)` and gracefully
 *    rolls into sheet `0` for negative inputs.
 *
 * Returns `null` from [parse] when the input doesn't look like a
 * spec at all (empty, no `|`, no parseable level) — caller treats
 * that as "no storyboard available" and skips the storyboard fallback.
 */
data class StoryboardSpec(
    val baseUrlTemplate: String,
    val levels: List<Level>
) {
    /** A single resolution tier of the storyboard. */
    data class Level(
        /** 0-indexed level number, in source order. */
        val index: Int,
        val cellWidthPx: Int,
        val cellHeightPx: Int,
        val cols: Int,
        val rows: Int,
        /** Time per cell in milliseconds. 0 for the static-poster level. */
        val intervalMs: Int,
        /**
         * Sheet-name template (e.g. `"M$M"` or `"default"`). Spliced
         * into the URL where `$M` first appears in the base template.
         * Empty when the spec uses an older format without per-level
         * names — the URL builder then leaves the original `$M` for
         * direct sheet-number substitution.
         */
        val nameTemplate: String,
        /** Per-level signed-query token, replaces `$rs` in the URL. */
        val sigh: String
    ) {
        /** `cols × rows` — total number of preview cells packed in one sheet. */
        val cellsPerSheet: Int get() = cols * rows
    }

    /**
     * One resolved frame: which sheet to fetch and where in it to crop
     * the cell. All pixel coordinates are top-left origin, fully
     * within `[0, cellsPerSheet * cell-size]`.
     */
    data class FrameLocation(
        val level: Level,
        /** 0-indexed sheet number. */
        val sheetIndex: Int,
        /** Linear cell index within the sheet, `0..cellsPerSheet-1`. */
        val cellIndex: Int,
        /** Row of the cell within the sheet grid. */
        val row: Int,
        /** Column of the cell within the sheet grid. */
        val col: Int,
        /** Top-left X in pixels of the cell within the fetched sheet. */
        val xPx: Int,
        /** Top-left Y in pixels of the cell within the fetched sheet. */
        val yPx: Int,
        /** Cell width in pixels (== `level.cellWidthPx`). */
        val widthPx: Int,
        /** Cell height in pixels (== `level.cellHeightPx`). */
        val heightPx: Int,
        /** Fully-resolved sheet URL (`$L`/`$M`/`$N`/`$rs` substituted). */
        val sheetUrl: String
    )

    /**
     * Highest-resolution time-aligned level, or `null` when the spec
     * only contains the level-0 static poster (intervalMs=0). Picks
     * the largest cellWidth × cellHeight among levels with positive
     * interval; ties broken by source order (later levels in YouTube's
     * spec are typically the higher-res ones, but we don't rely on
     * that).
     */
    val bestLevel: Level?
        get() = levels.filter { it.intervalMs > 0 }
            .maxByOrNull { it.cellWidthPx.toLong() * it.cellHeightPx.toLong() }

    /**
     * Resolve the frame location at the requested [timeSec] using
     * [level] (defaults to [bestLevel]).
     *
     * Returns `null` when [bestLevel] is null (no time-aligned level)
     * or [level] has zero interval / zero cells.
     */
    fun frameAt(
        timeSec: Double,
        level: Level? = bestLevel
    ): FrameLocation? {
        val l = level ?: return null
        if (l.intervalMs <= 0 || l.cellsPerSheet <= 0) return null
        val tSec = timeSec.coerceAtLeast(0.0)

        // Linear cell index across the entire video, then split into
        // (sheet, cellInSheet) by the cells-per-sheet packing.
        // Using millisecond arithmetic so we don't accumulate fp error
        // for long videos (~hours).
        val tMs = (tSec * 1000.0).toLong()
        val absoluteCell = (tMs / l.intervalMs).toInt()
        val sheetIndex = absoluteCell / l.cellsPerSheet
        val cellInSheet = absoluteCell - sheetIndex * l.cellsPerSheet
        val row = cellInSheet / l.cols
        val col = cellInSheet - row * l.cols

        return FrameLocation(
            level       = l,
            sheetIndex  = sheetIndex,
            cellIndex   = cellInSheet,
            row         = row,
            col         = col,
            xPx         = col * l.cellWidthPx,
            yPx         = row * l.cellHeightPx,
            widthPx     = l.cellWidthPx,
            heightPx    = l.cellHeightPx,
            sheetUrl    = buildSheetUrl(l, sheetIndex)
        )
    }

    /**
     * Build a fully-substituted sheet URL for [level] / [sheetIndex].
     *
     * Substitution order matters because some placeholders compose:
     * the URL template's `$M` is first replaced with the level's
     * `nameTemplate` (typically `"M$M"`), so a SECOND pass over `$M`
     * is then needed to swap in the actual sheet number. We chain
     * `replace` calls accordingly.
     *
     * Public so tests / debug logging can resolve a URL without going
     * through `frameAt()` first.
     */
    fun buildSheetUrl(level: Level, sheetIndex: Int): String {
        var url = baseUrlTemplate
        url = url.replace("\$L", level.index.toString())
        // Splice the name template in where the URL has $M. The template
        // typically contains another $M placeholder which is resolved
        // in the next replace, giving the final sheet name like "M0",
        // "M1", etc. For old formats with empty nameTemplate, this is
        // a no-op and the next pass handles $M → sheet number directly.
        if (level.nameTemplate.isNotEmpty()) {
            url = url.replace("\$M", level.nameTemplate)
        }
        url = url.replace("\$M", sheetIndex.toString())
        url = url.replace("\$N", sheetIndex.toString())
        url = url.replace("\$rs", level.sigh)
        return url
    }

    companion object {
        /**
         * Parse a raw `playerStoryboardSpecRenderer.spec` string.
         * Returns `null` when [raw] is null/blank/malformed.
         */
        fun parse(raw: String?): StoryboardSpec? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('|')
            if (parts.size < 2) return null
            val baseUrl = parts[0].trim()
            if (baseUrl.isBlank()) return null

            val levels = ArrayList<Level>(parts.size - 1)
            // index 0 is the URL template; levels start at index 1 of
            // the split, but their LEVEL number (which substitutes into
            // `$L`) is 0-based against the level list itself.
            for (i in 1 until parts.size) {
                val level = parseLevel(index = i - 1, raw = parts[i]) ?: continue
                levels += level
            }
            if (levels.isEmpty()) return null
            return StoryboardSpec(
                baseUrlTemplate = baseUrl,
                levels          = levels
            )
        }

        /**
         * Parse one `#`-separated level descriptor. Defensive — returns
         * `null` for malformed input rather than throwing, so a single
         * bad level doesn't take down the whole spec.
         *
         * Field layout, based on observed real-world specs:
         * ```
         *   parts.size == 6 :  W # H # totalFrames # cols # rows # intervalMs
         *   parts.size == 7 :  W # H # totalFrames # cols # rows # intervalMs # sigh
         *   parts.size >= 8 :  W # H # totalFrames # cols # rows # intervalMs # name # sigh
         * ```
         * `totalFrames` is captured but unused — different videos
         * report it inconsistently (sometimes per-sheet, sometimes
         * across all sheets). Cell math relies only on `cols × rows`
         * and `intervalMs`, both of which are stable across spec
         * versions.
         */
        private fun parseLevel(index: Int, raw: String): Level? {
            val parts = raw.split('#')
            if (parts.size < 6) return null
            val w        = parts[0].toIntOrNull() ?: return null
            val h        = parts[1].toIntOrNull() ?: return null
            val cols     = parts[3].toIntOrNull() ?: return null
            val rows     = parts[4].toIntOrNull() ?: return null
            val interval = parts[5].toIntOrNull() ?: return null
            if (w <= 0 || h <= 0 || cols <= 0 || rows <= 0) return null

            val name: String
            val sigh: String
            when {
                parts.size >= 8 -> {
                    name = parts[6]
                    sigh = parts[7]
                }
                parts.size == 7 -> {
                    name = ""
                    sigh = parts[6]
                }
                else -> {
                    name = ""
                    sigh = ""
                }
            }
            return Level(
                index         = index,
                cellWidthPx   = w,
                cellHeightPx  = h,
                cols          = cols,
                rows          = rows,
                intervalMs    = interval,
                nameTemplate  = name,
                sigh          = sigh
            )
        }
    }
}
