package com.reader.app.ui.nav

object Routes {
    const val HOME         = "home"
    const val SETTINGS     = "settings"
    const val TTS_SETTINGS = "tts_settings"
    const val UPLOAD       = "upload"
    const val ENROLLMENT   = "enrollment"

    /** reading/{documentId} */
    const val READING_PATTERN = "reading/{documentId}"
    fun reading(documentId: Long) = "reading/$documentId"

    /** discussion/{documentId} */
    const val DISCUSSION_PATTERN = "discussion/{documentId}"
    fun discussion(documentId: Long) = "discussion/$documentId"

    /** generate/{documentId} — hub with MCQ + PDF cards. */
    const val GENERATE_PATTERN = "generate/{documentId}"
    fun generate(documentId: Long) = "generate/$documentId"

    /** mcq/home/{documentId} — list quizzes + 'Generate new'. */
    const val MCQ_HOME_PATTERN = "mcq/home/{documentId}"
    fun mcqHome(documentId: Long) = "mcq/home/$documentId"

    /** mcq/attempt/{quizId} — take / resume the test. */
    const val MCQ_ATTEMPT_PATTERN = "mcq/attempt/{quizId}"
    fun mcqAttempt(quizId: Long) = "mcq/attempt/$quizId"

    /** mcq/result/{attemptId} — score + breakdown. */
    const val MCQ_RESULT_PATTERN = "mcq/result/{attemptId}"
    fun mcqResult(attemptId: Long) = "mcq/result/$attemptId"

    /** notes/{documentId} — preview + export PDF. */
    const val NOTES_PATTERN = "notes/{documentId}"
    fun notes(documentId: Long) = "notes/$documentId"

    const val ARG_DOC_ID    = "documentId"
    const val ARG_QUIZ_ID   = "quizId"
    const val ARG_ATTEMPT_ID = "attemptId"
}
