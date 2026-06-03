package com.reader.app.ui.screens.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.local.entity.GeneratedNoteEntity
import com.reader.app.data.repository.DocumentRepository
import com.reader.app.data.repository.NotesRepository
import com.reader.app.di.GenerationManager
import com.reader.app.di.ServiceLocator
import com.reader.app.domain.notes.NotesGenerator
import com.reader.app.domain.text.LanguageDetect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the Notes screen state.
 *
 * Notes generation is delegated to [GenerationManager], which runs
 * the LLM call on an Application-scoped coroutine. That means:
 *
 *   - The user can tap "Regenerate", press Back, and walk away. The
 *     generation keeps running. When it finishes a system
 *     notification fires + the cached row updates + the next visit
 *     to this screen shows the new HTML.
 *   - Auto-generate-on-first-open just enqueues; the manager's
 *     `enqueueUniqueWork(KEEP)` policy makes a duplicate enqueue
 *     a no-op while one is already running, so we don't need a
 *     separate `isRunning` guard here.
 *   - View prefs (theme / fontScale / margin) are still persisted
 *     locally — those are screen-side state, not generator state, so
 *     they don't need to go through the manager.
 */
class NotesViewModel(
    application: Application,
    private val documentId: Long,
    private val docs: DocumentRepository,
    private val notes: NotesRepository,
) : AndroidViewModel(application) {

    /**
     * Each enum carries its own [id] (the persisted-to-DB lowercase
     * string) plus a [fromString] constructor inside its companion so
     * we don't rely on top-level extensions on a nested-class
     * companion (which compiles but is unidiomatic).
     */
    enum class Theme(val id: String) {
        Light("light"),
        Sepia("sepia"),
        Dark("dark");

        companion object {
            fun fromString(s: String?): Theme = entries.firstOrNull { it.id == s?.lowercase() } ?: Light
        }
    }

    enum class Margin(val id: String) {
        Compact("compact"),
        Normal("normal"),
        Wide("wide");

        companion object {
            fun fromString(s: String?): Margin = entries.firstOrNull { it.id == s?.lowercase() } ?: Normal
        }
    }

    /**
     * User-facing language picker for the Notes generator.
     *
     * `Auto` = let [LanguageDetect] decide based on the transcript
     * (default behaviour, matches every pre-v7 row in the DB).
     * The other three are explicit overrides — the LLM gets told
     * "OUTPUT LANGUAGE: <this>" regardless of what the transcript
     * looks like, which is what the user wants when they record a
     * Hindi video but want English notes (or vice versa).
     *
     * Mapped 1:1 to [LanguageDetect.Lang] for the override path.
     * Persisted as the LanguageDetect.Lang's enum name (or null
     * for Auto) in `generated_note.languageOverride`.
     */
    enum class LanguageChoice {
        Auto, Hindi, Hinglish, English;

        /**
         * Convert to the [LanguageDetect.Lang] override the
         * generator expects: null for Auto, the matching enum
         * value otherwise.
         */
        fun toLangOverride(): LanguageDetect.Lang? = when (this) {
            Auto     -> null
            Hindi    -> LanguageDetect.Lang.Hindi
            Hinglish -> LanguageDetect.Lang.Hinglish
            English  -> LanguageDetect.Lang.English
        }

        /** String form persisted in the DB (null for Auto). */
        fun toStored(): String? = when (this) {
            Auto -> null
            else -> when (this) {
                Hindi    -> "Hindi"
                Hinglish -> "Hinglish"
                English  -> "English"
                Auto     -> null   // unreachable, kept for exhaustive-when
            }
        }

        companion object {
            /**
             * Inverse of [toStored]. Recovers the user's last choice
             * from the DB. Unknown / blank strings map to [Auto] so a
             * corrupt row never breaks the screen.
             */
            fun fromStored(s: String?): LanguageChoice = when (s) {
                null, "" -> Auto
                "Hindi"    -> Hindi
                "Hinglish" -> Hinglish
                "English"  -> English
                else       -> Auto
            }
        }
    }

    /** What the screen renders. */
    data class UiState(
        val title: String = "",
        val cached: GeneratedNoteEntity? = null,
        val theme: Theme = Theme.Light,
        val fontScale: Double = 1.0,
        val margin: Margin = Margin.Normal,
        val ready: Boolean = false,                 // doc loaded; UI can render
        /**
         * User's language pick for the next generation. `Auto` is
         * the default and matches "detect from transcript". Persisted
         * to `generated_note.languageOverride` whenever the user
         * touches the picker.
         */
        val language: LanguageChoice = LanguageChoice.Auto,
        /**
         * User's custom system prompt for the next generation. Empty
         * means "use the built-in default", which the prefs editor
         * pre-fills with [NotesGenerator.defaultSystemPrompt] so the
         * user can edit *from* the canonical text rather than starting
         * from a blank box. Persisted to `generated_note.customPrompt`
         * when the user taps Save in the editor.
         */
        val customPrompt: String = "",
        /**
         * Mirrors [GenerationManager.statuses] for this doc's notes
         * key. Composed into convenient bools below. Held here so the
         * screen doesn't need to know about [GenerationManager].
         */
        val genStatus: GenerationManager.Status = GenerationManager.Status.Idle,
        val polishStatus: GenerationManager.Status = GenerationManager.Status.Idle,
    ) {
        val isGenerating: Boolean get() = genStatus is GenerationManager.Status.Running || polishStatus is GenerationManager.Status.Running
        val genProgressLabel: String? get() = (genStatus as? GenerationManager.Status.Running)?.message 
            ?: (polishStatus as? GenerationManager.Status.Running)?.message
        val genErrorMessage: String? get() = (genStatus as? GenerationManager.Status.Failed)?.message
            ?: (polishStatus as? GenerationManager.Status.Failed)?.message

        /**
         * True iff the cached row contains real HTML the WebView can
         * render. The generator now creates "stub" rows (empty html)
         * when the user persists customisations BEFORE the first
         * generation, so [cached] alone is no longer a reliable
         * proxy for "notes are ready to display". The screen uses
         * this for the WebView / Save-as-PDF gating.
         */
        val notesReady: Boolean get() = cached?.html?.isNotBlank() == true
    }

    private val key = GenerationManager.Key(GenerationManager.Type.Notes, documentId)
    private val polishKey = GenerationManager.Key(GenerationManager.Type.PolishNotes, documentId)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    val cachedNote: StateFlow<GeneratedNoteEntity?> = notes
        .observe(documentId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    init {
        // 1. One-time doc + cached-notes load.
        viewModelScope.launch {
            val doc = docs.get(documentId)
            val title = doc?.title ?: "Notes"
            val existing = notes.get(documentId)
            _state.update {
                it.copy(
                    title    = title,
                    cached   = existing,
                    theme    = Theme.fromString(existing?.theme),
                    fontScale = existing?.fontScale ?: 1.0,
                    margin   = Margin.fromString(existing?.margin),
                    language = LanguageChoice.fromStored(existing?.languageOverride),
                    customPrompt = existing?.customPrompt.orEmpty(),
                    ready    = true,
                )
            }
            // First open with no real notes cached → auto-kick a
            // background generation. "No real notes" includes both
            // "no row at all" AND "row exists but html is blank" —
            // the latter happens when the user persisted a
            // customization BEFORE the first generation completed.
            // The KEEP unique-work policy means a duplicate enqueue
            // is a no-op while one is already running.
            if (existing == null || existing.html.isBlank()) {
                generate()
            }
        }

        // 2. Mirror cached-row updates (fires when the manager saves
        //    a fresh HTML blob, OR when prefs / customizations change).
        viewModelScope.launch {
            cachedNote.collect { e ->
                if (e != null) {
                    _state.update {
                        it.copy(
                            cached    = e,
                            theme     = Theme.fromString(e.theme),
                            fontScale = e.fontScale,
                            margin    = Margin.fromString(e.margin),
                            language  = LanguageChoice.fromStored(e.languageOverride),
                            customPrompt = e.customPrompt.orEmpty(),
                        )
                    }
                }
            }
        }

        // 3. Mirror generation status from WorkManager.
        viewModelScope.launch {
            GenerationManager.statusFor(getApplication(), key).collect { status ->
                _state.update { it.copy(genStatus = status) }
            }
        }

        viewModelScope.launch {
            GenerationManager.statusFor(getApplication(), polishKey).collect { status ->
                _state.update { it.copy(polishStatus = status) }
            }
        }
    }

    fun consumeError() {
        if (_state.value.genStatus is GenerationManager.Status.Failed) {
            GenerationManager.consume(getApplication(), key)
        }
        if (_state.value.polishStatus is GenerationManager.Status.Failed) {
            GenerationManager.consume(getApplication(), polishKey)
        }
    }

    /**
     * Kick off a background generation. Returns immediately; the
     * status flow above carries progress + completion.
     */
    fun generate() {
        val app = getApplication<Application>()
        GenerationManager.startNotes(
            application = app,
            documentId = documentId,
            documentTitle = _state.value.title.ifBlank { "Notes" },
        )
    }

    /**
     * Kick off notes visual/typographical polishing.
     */
    fun polish() {
        val app = getApplication<Application>()
        GenerationManager.startPolishNotes(
            application = app,
            documentId = documentId,
            documentTitle = _state.value.title.ifBlank { "Notes" },
        )
    }

    /* ---------- view prefs (in-memory + persisted) ---------- */

    fun setTheme(theme: Theme)        { _state.update { it.copy(theme = theme) }; persistPrefs() }
    fun setFontScale(scale: Double)   { _state.update { it.copy(fontScale = scale.coerceIn(0.85, 1.5)) }; persistPrefs() }
    fun setMargin(margin: Margin)     { _state.update { it.copy(margin = margin) }; persistPrefs() }

    private fun persistPrefs() {
        if (_state.value.cached == null) return
        viewModelScope.launch {
            notes.updatePrefs(
                documentId = documentId,
                theme      = _state.value.theme.id,
                fontScale  = _state.value.fontScale,
                margin     = _state.value.margin.id,
            )
        }
    }

    /* ---------- generation customizations (language + prompt) ---------- */

    /**
     * Update the language picker. Persists to the DB even before
     * the first generation has completed (creates a stub row if
     * needed) so the user's choice survives app restart and is
     * picked up by the next [generate] run.
     */
    fun setLanguage(choice: LanguageChoice) {
        _state.update { it.copy(language = choice) }
        persistCustomization()
    }

    /**
     * Update the custom system prompt. Whitespace-only input is
     * treated as "use default" (we persist null so the next
     * generation falls through to [NotesGenerator.defaultSystemPrompt]).
     * The screen's editor pre-fills with [defaultPromptText] so the
     * user starts from "what we'd send by default" rather than a
     * blank box.
     */
    fun setCustomPrompt(text: String) {
        _state.update { it.copy(customPrompt = text) }
        persistCustomization()
    }

    /**
     * Reset both customization knobs back to defaults: language to
     * Auto, prompt to "" (use built-in default). Persists
     * immediately.
     */
    fun resetCustomization() {
        _state.update {
            it.copy(language = LanguageChoice.Auto, customPrompt = "")
        }
        persistCustomization()
    }

    /**
     * Read-only access to the canonical default prompt, for the
     * editor's "Reset to default" / "Pre-fill" affordance.
     */
    val defaultPromptText: String get() = NotesGenerator.defaultSystemPrompt

    private fun persistCustomization() {
        val s = _state.value
        viewModelScope.launch {
            notes.saveCustomization(
                documentId       = documentId,
                title            = s.title.ifBlank { "Notes" },
                customPrompt     = s.customPrompt.takeIf { it.isNotBlank() },
                languageOverride = s.language.toStored(),
            )
        }
    }

    companion object {
        fun factory(documentId: Long, application: Application) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                NotesViewModel(
                    application = application,
                    documentId = documentId,
                    docs = ServiceLocator.documentRepository,
                    notes = ServiceLocator.notesRepository,
                ) as T
        }
    }
}
