package com.reader.app.di

import android.content.Context
import com.reader.app.data.local.ReaderDatabase
import com.reader.app.data.repository.ConfigRepository
import com.reader.app.data.repository.DocumentRepository
import com.reader.app.data.repository.LlmRepository
import com.reader.app.data.repository.McqRepository
import com.reader.app.data.repository.NotesRepository
import com.reader.app.data.repository.SpeakerEnrollmentRepository
import com.reader.app.data.repository.TtsPreferencesRepository
import com.reader.app.domain.youtube.YouTubeStoryboardClient

object ServiceLocator {

    @Volatile private var initialized = false

    lateinit var configRepository: ConfigRepository                            private set
    lateinit var documentRepository: DocumentRepository                        private set
    lateinit var llmRepository: LlmRepository                                  private set
    lateinit var speakerEnrollmentRepository: SpeakerEnrollmentRepository      private set
    lateinit var ttsPreferencesRepository: TtsPreferencesRepository            private set
    lateinit var mcqRepository: McqRepository                                  private set
    lateinit var notesRepository: NotesRepository                              private set
    /**
     * Process-wide YouTube storyboard fetcher used by the multimodal
     * frame fallback path. Single instance so the in-memory sheet
     * LRU is shared across screens (e.g. user navigates Discussion
     * → Settings → Discussion without losing already-fetched sheets).
     */
    lateinit var youTubeStoryboardClient: YouTubeStoryboardClient              private set

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val db = ReaderDatabase.get(context)
            configRepository            = ConfigRepository(context, db.apiConfigDao())
            documentRepository          = DocumentRepository(db.documentDao(), db.documentChunkDao(), db.transcriptCueDao())
            llmRepository               = LlmRepository()
            speakerEnrollmentRepository = SpeakerEnrollmentRepository(db.speakerEnrollmentDao())
            ttsPreferencesRepository    = TtsPreferencesRepository(db.ttsPreferencesDao())
            mcqRepository               = McqRepository(db.mcqDao())
            notesRepository             = NotesRepository(db.generatedNoteDao())
            youTubeStoryboardClient     = YouTubeStoryboardClient()
            initialized = true
        }
    }
}
