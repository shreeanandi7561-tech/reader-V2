package com.reader.app.di

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide one-shot mailbox for "user shared a YouTube URL into us"
 * events.
 *
 * Why a singleton instead of intent extras / SavedStateHandle / nav
 * arguments:
 *  - The URL has to flow from `MainActivity.onCreate` (or
 *    `onNewIntent`) into a freshly-constructed `UploadViewModel`. Both
 *    of those happen in different lifetimes; the URL has to survive
 *    the navigation hop.
 *  - Encoding the URL into the nav route string would force every
 *    consumer (and all the tests) to URL-encode/decode it, which is
 *    fragile.
 *  - SavedStateHandle would work but requires plumbing a special
 *    factory through the existing UploadViewModel.Factory just for
 *    this case. The singleton keeps the surface tiny.
 *
 * Lifecycle contract:
 *  - [post] is called from `MainActivity` when an `Intent.ACTION_SEND`
 *    carrying a YouTube URL arrives.
 *  - [pendingUrl] is observed by `ReaderNavGraph` so it can navigate to
 *    the Upload route the moment a fresh URL appears (covers both
 *    cold-start-from-share and hot-share-while-app-is-open).
 *  - [consume] is called by `UploadViewModel.init` to grab + clear the
 *    URL, prefill the input field, and auto-trigger the existing
 *    fetch flow. Clearing prevents a stale URL from getting re-prefilled
 *    if the user later reopens the Upload screen by hand.
 */
object SharedYouTubeUrlBus {

    private val _pendingUrl = MutableStateFlow<String?>(null)

    /** Last URL posted that hasn't been consumed yet, or `null`. */
    val pendingUrl: StateFlow<String?> = _pendingUrl.asStateFlow()

    /**
     * Drop a YouTube URL into the mailbox. Replaces any earlier
     * un-consumed value (the most recent share wins).
     */
    fun post(url: String) {
        _pendingUrl.value = url.trim().ifEmpty { null }
    }

    /**
     * Atomically read + clear the mailbox. Returns `null` if there was
     * nothing pending, otherwise returns the URL exactly once.
     */
    fun consume(): String? {
        val v = _pendingUrl.value
        if (v != null) _pendingUrl.value = null
        return v
    }

    /** Test-only helper: drop everything without observing it. */
    internal fun reset() {
        _pendingUrl.value = null
    }
}

/**
 * Pull the FIRST YouTube watch URL out of an arbitrary block of
 * shared text. The YouTube app's "Share" sheet typically just sends
 * the bare URL via `Intent.EXTRA_TEXT`, but other clients (chats,
 * SMS, browser address bars copied with surrounding context) often
 * include extra words around it — we want to be tolerant of that
 * while still rejecting non-YouTube URLs.
 *
 * Recognised hosts: `youtube.com`, `m.youtube.com`, `www.youtube.com`,
 * `youtu.be` (the canonical share-sheet shortener), and the rarer
 * `youtube-nocookie.com` (used by some embed shares). Returns the raw
 * matched URL string so [YouTubeTranscriptFetcher.parseVideoId] can do
 * the actual id extraction — keeps the two regexes orthogonal.
 */
fun extractYouTubeUrlFromSharedText(rawText: String): String? {
    if (rawText.isBlank()) return null
    val regex = Regex(
        pattern = """https?://(?:www\.|m\.)?(?:youtube\.com|youtu\.be|youtube-nocookie\.com)/\S+""",
        option  = RegexOption.IGNORE_CASE
    )
    return regex.find(rawText)?.value?.trim()
}
