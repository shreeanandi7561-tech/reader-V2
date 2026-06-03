package com.reader.app.ui.screens.notes

import android.annotation.SuppressLint
import android.app.Application
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.reader.app.domain.notes.NotesPrint

/**
 * Notes screen — preview the cached HTML + tweak view prefs + export PDF.
 *
 * Generation runs in the background (via `GenerationManager`) so the
 * user can press Back and walk away while the LLM is working — a
 * system notification fires when the document is ready. Coming back
 * to the screen at any time is safe: the cached HTML is observed via
 * Flow and renders as soon as it lands in the DB.
 *
 * Layout (top → bottom):
 *   1. Top bar — back, document title, regenerate, prefs toggle.
 *   2. Optional prefs strip — theme chips, font scale slider, margin chips.
 *   3. WebView occupying the rest of the screen, loading the cached
 *      HTML (or a "generating in background" / "no notes yet" status).
 *   4. Sticky bottom row — "Save as PDF" button.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NotesScreen(
    documentId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: NotesViewModel = viewModel(
        factory = NotesViewModel.factory(documentId, context.applicationContext as Application)
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var showPrefs by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lastLoadedHtmlSig by remember { mutableStateOf<String?>(null) }

    com.reader.app.ui.components.RequestNotificationPermission()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ---------- top bar ----------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text(
                    state.title.ifBlank { "Notes" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { showPrefs = !showPrefs },
                    // Allow opening prefs even before the first
                    // generation has produced HTML — the user wants
                    // to customise (language / prompt) BEFORE
                    // regenerate kicks in. notesReady === html
                    // present, but the prefs UI is useful before
                    // that point too. We just gate the WebView /
                    // Save-as-PDF off notesReady (below).
                    enabled = state.ready,
                ) {
                    Icon(Icons.Default.Tune, contentDescription = "View prefs")
                }
                IconButton(
                    onClick = vm::generate,
                    enabled = !state.isGenerating,
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Regenerate")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            // ---------- prefs strip ----------
            if (showPrefs && state.ready) {
                PrefsStrip(state = state, vm = vm)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            // Background-mode banner — visible whenever a generation is
            // in flight. Tells the user explicitly that they can leave
            // and gives them a one-tap way to do so.
            if (state.isGenerating) {
                BackgroundBanner(
                    label = state.genProgressLabel ?: "Notes background mein ban rahe hain…",
                    onLeave = onBack,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            }

            if (state.genErrorMessage != null) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            state.genErrorMessage!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = vm::consumeError,
                            modifier = Modifier.padding(start = 8.dp),
                        ) { Text("OK") }
                    }
                }
            }

            // ---------- preview body ----------
            Box(modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
            ) {
                when {
                    state.isGenerating && !state.notesReady -> CenterStatus(
                        title = "Notes background mein ban rahe hain…",
                        subtitle = "1-2 minute lag sakte hain. Aap Back jaa kar kuch aur kar sakte hain — " +
                            "jaise hi notes ready honge, notification mil jaayega aur yahan vapas aakar dekh sakte hain.",
                    )
                    !state.notesReady -> CenterStatus(
                        title = "Notes nahi bane hain",
                        subtitle = "Upar regenerate dabaiye.",
                    )
                    else -> {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    settings.useWideViewPort = true
                                    settings.loadWithOverviewMode = true
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            view?.evaluateJavascript(
                                                buildPrefsCss(state.theme, state.fontScale, state.margin),
                                                null
                                            )
                                        }
                                    }
                                    webView = this
                                }
                            },
                            update = { wv ->
                                webView = wv
                                val cached = state.cached ?: return@AndroidView
                                if (cached.html.isBlank()) return@AndroidView
                                val sig = "${cached.documentId}:${cached.updatedAt}"
                                if (sig != lastLoadedHtmlSig) {
                                    lastLoadedHtmlSig = sig
                                    // Base URL note: MathJax 3 is loaded
                                    // from cdn.jsdelivr.net; that load
                                    // is blocked when we pass `null`
                                    // here (page becomes a `data:`
                                    // origin and modern WebView denies
                                    // mixed/cross-origin script
                                    // requests from data: pages). A
                                    // synthetic https origin works —
                                    // there's no real server, the path
                                    // just lets MathJax download. If
                                    // the device is offline, MathJax
                                    // simply doesn't load and math
                                    // shows as raw `$…$` which is
                                    // still readable.
                                    wv.loadDataWithBaseURL(
                                        "https://reader.local/",
                                        cached.html,
                                        "text/html",
                                        "utf-8",
                                        null
                                    )
                                } else {
                                    wv.evaluateJavascript(
                                        buildPrefsCss(state.theme, state.fontScale, state.margin),
                                        null
                                    )
                                }
                            }
                        )

                        // Subtle pill that confirms a regenerate is in
                        // flight while the existing cached preview is
                        // still visible underneath.
                        if (state.isGenerating) {
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .clip(RoundedCornerShape(20.dp)),
                                color = MaterialTheme.colorScheme.primary,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(end = 8.dp).size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Text(
                                        "Re-generating…",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------- bottom action ----------
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        val wv = webView ?: return@Button
                        val title = state.cached?.title ?: state.title
                        // Explicitly trigger a refresh of typesetting promise and wait for it
                        wv.evaluateJavascript(
                            "(function(){try{if(window.MathJax&&MathJax.typesetPromise){window.mathjaxReady=false;MathJax.typesetPromise().then(function(){window.mathjaxReady=true;});return 1;}return 0;}catch(e){window.mathjaxReady=true;return 0;}})();",
                            null
                        )
                        fun checkAndPrint(attempts: Int) {
                            wv.evaluateJavascript("window.mathjaxReady") { result ->
                                val isReady = result?.trim() == "true"
                                if (isReady || attempts <= 0) {
                                    NotesPrint.printToPdf(context, wv, title)
                                } else {
                                    wv.postDelayed({
                                        checkAndPrint(attempts - 1)
                                    }, 100L)
                                }
                            }
                        }
                        checkAndPrint(20)
                    },
                    enabled = state.notesReady && !state.isGenerating,
                ) {
                    Text("Save as PDF")
                }
            }
        }
    }

    // Push prefs into the live WebView whenever they change, so the
    // slider feels instant.
    LaunchedEffect(state.theme, state.fontScale, state.margin) {
        webView?.evaluateJavascript(
            buildPrefsCss(state.theme, state.fontScale, state.margin),
            null
        )
    }
}

/* --------------------------- background banner --------------------------- */

@Composable
private fun BackgroundBanner(label: String, onLeave: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier   = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color      = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Aap Back jaa sakte hain — ready hone par notification milega.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = onLeave) { Text("Theek hai") }
        }
    }
}

/* --------------------------- prefs strip --------------------------- */

@Composable
private fun PrefsStrip(state: NotesViewModel.UiState, vm: NotesViewModel) {
    // The custom-prompt editor is collapsible — most users never
    // touch it, and an always-visible 50-line text field would
    // dominate the prefs strip. Initially expanded only if the user
    // has already saved a custom prompt for this doc.
    var showPromptEditor by remember(state.customPrompt.isNotBlank()) {
        mutableStateOf(state.customPrompt.isNotBlank())
    }
    // Local mirror of the prompt textarea — committed to the VM
    // (and persisted to the DB) when the user taps Save / Reset, so
    // every keystroke doesn't trigger a Room write. The remember
    // key is `state.customPrompt` so that switching documents (or a
    // background process re-loading the row) refreshes the field.
    var promptDraft by remember(state.customPrompt) {
        mutableStateOf(state.customPrompt)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            "PREVIEW SETTINGS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotesViewModel.Theme.entries.forEach { t ->
                FilterChip(
                    selected = state.theme == t,
                    onClick  = { vm.setTheme(t) },
                    label    = { Text(t.name) },
                    enabled  = state.notesReady,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotesViewModel.Margin.entries.forEach { m ->
                FilterChip(
                    selected = state.margin == m,
                    onClick  = { vm.setMargin(m) },
                    label    = { Text(m.name) },
                    enabled  = state.notesReady,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Font size: ${"%.0f".format(state.fontScale * 100)}%",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value         = state.fontScale.toFloat(),
            onValueChange = { vm.setFontScale(it.toDouble()) },
            valueRange    = 0.85f..1.5f,
            enabled       = state.notesReady,
        )

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(12.dp))

        // ---- generation customisations (apply to next regenerate) ----
        Text(
            "GENERATION SETTINGS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Yeh settings agle Regenerate par apply hongi. Default Auto " +
                "language video se detect hota hai. Custom prompt khali " +
                "rakhne par built-in default prompt use hoga.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        // Language picker — Auto + the three explicit choices.
        Text(
            "Notes language",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NotesViewModel.LanguageChoice.entries.forEach { choice ->
                FilterChip(
                    selected = state.language == choice,
                    onClick  = { vm.setLanguage(choice) },
                    label    = { Text(choice.displayLabel()) },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Custom prompt — collapsible. The "Show / Hide" toggle is
        // also where the user pre-fills with the default prompt
        // (there's a button inside the editor for that).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Custom prompt",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showPromptEditor = !showPromptEditor }) {
                Text(if (showPromptEditor) "Hide" else "Edit")
            }
        }
        if (state.customPrompt.isNotBlank() && !showPromptEditor) {
            Text(
                "Custom prompt active (${state.customPrompt.length} chars). " +
                    "Tap Edit to view or change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (showPromptEditor) {
            OutlinedTextField(
                value = promptDraft,
                onValueChange = { promptDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                placeholder = {
                    Text(
                        "Default prompt rakhne ke liye yahan kuch type mat karein. " +
                            "Apne hisab se prompt likhne ke liye 'Pre-fill default' " +
                            "dabaiye, phir edit karein."
                    )
                },
                minLines = 4,
                maxLines = 12,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { promptDraft = vm.defaultPromptText }) {
                    Text("Pre-fill default")
                }
                OutlinedButton(onClick = {
                    promptDraft = ""
                    vm.resetCustomization()
                }) {
                    Text("Reset")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { vm.setCustomPrompt(promptDraft) },
                    enabled = promptDraft != state.customPrompt,
                ) {
                    Text("Save")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Save dabane ke baad upar Refresh dabaiye taki naya prompt apply ho.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Display label for the [NotesViewModel.LanguageChoice] picker.
 * Auto carries an explicit "(detect)" hint so the user knows the
 * default behaviour without reading the description above.
 */
private fun NotesViewModel.LanguageChoice.displayLabel(): String = when (this) {
    NotesViewModel.LanguageChoice.Auto     -> "Auto (detect)"
    NotesViewModel.LanguageChoice.Hindi    -> "हिंदी"
    NotesViewModel.LanguageChoice.Hinglish -> "Hinglish"
    NotesViewModel.LanguageChoice.English  -> "English"
}

/* --------------------------- helpers --------------------------- */

@Composable
private fun CenterStatus(title: String, subtitle: String) {
    Box(modifier = Modifier
        .fillMaxSize()
        .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Build a tiny JS snippet that injects (or replaces) a `<style id="reader-prefs">`
 * block on the document with theme + font-scale + margin overrides.
 *
 * The cached HTML already ships its own print-tuned CSS; this just
 * layers user-pref overrides on top. Re-running this on every pref
 * change is O(1) on the page side — no re-parse, no re-layout of the
 * full document.
 */
private fun buildPrefsCss(
    theme: NotesViewModel.Theme,
    fontScale: Double,
    margin: NotesViewModel.Margin,
): String {
    val (bg, fg) = when (theme) {
        NotesViewModel.Theme.Light -> "#ffffff" to "#1d1d1f"
        NotesViewModel.Theme.Sepia -> "#fbf3e3" to "#3a2f1f"
        NotesViewModel.Theme.Dark  -> "#15171a" to "#e6e6e6"
    }
    val pageMargin = when (margin) {
        NotesViewModel.Margin.Compact -> "1.2cm"
        NotesViewModel.Margin.Normal  -> "1.8cm"
        NotesViewModel.Margin.Wide    -> "2.5cm"
    }
    val css = """
        :root { --reader-font-scale: $fontScale; }
        html, body { background: $bg !important; color: $fg !important; }
        body { font-size: calc(11pt * var(--reader-font-scale)) !important; }
        h1 { font-size: calc(22pt * var(--reader-font-scale)) !important; }
        h2 { font-size: calc(16pt * var(--reader-font-scale)) !important; }
        h3 { font-size: calc(13pt * var(--reader-font-scale)) !important; }
        @page { margin: $pageMargin; }
    """.trimIndent()
    val escaped = css
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("\n", "\\n")
    return """
        (function() {
            var id = 'reader-prefs';
            var s = document.getElementById(id);
            if (!s) { s = document.createElement('style'); s.id = id; document.head.appendChild(s); }
            s.textContent = `$escaped`;
        })();
    """.trimIndent()
}
