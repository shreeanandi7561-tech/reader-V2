package com.reader.app.ui.nav

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.reader.app.di.SharedYouTubeUrlBus
import com.reader.app.ui.screens.discussion.DiscussionScreen
import com.reader.app.ui.screens.enrollment.EnrollmentScreen
import com.reader.app.ui.screens.generate.GenerateScreen
import com.reader.app.ui.screens.home.HomeScreen
import com.reader.app.ui.screens.mcq.McqAttemptScreen
import com.reader.app.ui.screens.mcq.McqHomeScreen
import com.reader.app.ui.screens.mcq.McqResultScreen
import com.reader.app.ui.screens.notes.NotesScreen
import com.reader.app.ui.screens.reading.ReadingScreen
import com.reader.app.ui.screens.settings.SettingsScreen
import com.reader.app.ui.screens.ttssettings.TtsSettingsScreen
import com.reader.app.ui.screens.upload.UploadScreen

/**
 * Top-level navigation with a bottom NavigationBar shown only on
 * Library and Settings tabs. Detail screens (Reading, Discussion,
 * Generate hub, MCQ home/attempt/result, Notes, Upload, Enrollment,
 * TtsSettings) hide the bottom bar so the user can focus on the
 * content.
 */
@Composable
fun ReaderNavGraph() {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // Bridge: when a share-intent drops a URL into the bus, jump to
    // Upload. We DO NOT consume the URL here — the bus stays populated
    // until UploadViewModel.init reads it on the way in. That keeps
    // navigation and "actually do something with the URL" in lockstep
    // even if either side is recreated mid-flight (e.g. config change).
    LaunchedEffect(nav) {
        SharedYouTubeUrlBus.pendingUrl.collect { url ->
            if (url.isNullOrBlank()) return@collect
            nav.navigate(Routes.UPLOAD) {
                popUpTo(Routes.HOME)
                launchSingleTop = true
            }
        }
    }

    val topLevelRoutes = setOf(Routes.HOME, Routes.SETTINGS)
    val showBottomBar = currentRoute in topLevelRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(
                    currentRoute = currentRoute,
                    onNavigate = { route ->
                        nav.navigate(route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {

            composable(Routes.HOME) {
                HomeScreen(
                    onOpenSettings   = { nav.navigate(Routes.SETTINGS) },
                    onOpenUpload     = { nav.navigate(Routes.UPLOAD) },
                    onOpenReading    = { id -> nav.navigate(Routes.reading(id)) },
                    onOpenDiscussion = { id -> nav.navigate(Routes.discussion(id)) },
                    onOpenGenerate   = { id -> nav.navigate(Routes.generate(id)) },
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack            = null,   // top-level destination, no back arrow
                    onEnrollVoice     = { nav.navigate(Routes.ENROLLMENT) },
                    onOpenTtsSettings = { nav.navigate(Routes.TTS_SETTINGS) }
                )
            }

            composable(Routes.TTS_SETTINGS) {
                TtsSettingsScreen(onBack = { nav.popBackStack() })
            }

            composable(Routes.ENROLLMENT) {
                EnrollmentScreen(
                    onBack = { nav.popBackStack() },
                    onDone = { nav.popBackStack() }
                )
            }

            composable(Routes.UPLOAD) {
                UploadScreen(
                    onBack    = { nav.popBackStack() },
                    onCreated = { id, isVideoDoc ->
                        nav.popBackStack()
                        val target =
                            if (isVideoDoc) Routes.discussion(id)
                            else            Routes.reading(id)
                        nav.navigate(target)
                    }
                )
            }

            composable(
                route     = Routes.READING_PATTERN,
                arguments = listOf(navArgument(Routes.ARG_DOC_ID) { type = NavType.LongType })
            ) { entry ->
                val docId = entry.arguments?.getLong(Routes.ARG_DOC_ID) ?: return@composable
                ReadingScreen(documentId = docId, onBack = { nav.popBackStack() })
            }

            composable(
                route     = Routes.DISCUSSION_PATTERN,
                arguments = listOf(navArgument(Routes.ARG_DOC_ID) { type = NavType.LongType })
            ) { entry ->
                val docId = entry.arguments?.getLong(Routes.ARG_DOC_ID) ?: return@composable
                DiscussionScreen(documentId = docId, onBack = { nav.popBackStack() })
            }

            // ---------- Generate hub ----------
            composable(
                route     = Routes.GENERATE_PATTERN,
                arguments = listOf(navArgument(Routes.ARG_DOC_ID) { type = NavType.LongType })
            ) { entry ->
                val docId = entry.arguments?.getLong(Routes.ARG_DOC_ID) ?: return@composable
                GenerateScreen(
                    documentId   = docId,
                    onBack       = { nav.popBackStack() },
                    onOpenMcq    = { id -> nav.navigate(Routes.mcqHome(id)) },
                    onOpenNotes  = { id -> nav.navigate(Routes.notes(id)) },
                )
            }

            // ---------- MCQ subgraph ----------
            composable(
                route     = Routes.MCQ_HOME_PATTERN,
                arguments = listOf(navArgument(Routes.ARG_DOC_ID) { type = NavType.LongType })
            ) { entry ->
                val docId = entry.arguments?.getLong(Routes.ARG_DOC_ID) ?: return@composable
                McqHomeScreen(
                    documentId    = docId,
                    onBack        = { nav.popBackStack() },
                    onOpenAttempt = { quizId -> nav.navigate(Routes.mcqAttempt(quizId)) },
                )
            }

            composable(
                route     = Routes.MCQ_ATTEMPT_PATTERN,
                arguments = listOf(navArgument(Routes.ARG_QUIZ_ID) { type = NavType.LongType })
            ) { entry ->
                val quizId = entry.arguments?.getLong(Routes.ARG_QUIZ_ID) ?: return@composable
                McqAttemptScreen(
                    quizId       = quizId,
                    onBack       = { nav.popBackStack() },
                    onShowResult = { attemptId ->
                        // Pop the in-progress Attempt screen off the back
                        // stack so a back press from Result lands on MCQ
                        // home, not on a frozen attempt screen.
                        nav.popBackStack()
                        nav.navigate(Routes.mcqResult(attemptId))
                    },
                )
            }

            composable(
                route     = Routes.MCQ_RESULT_PATTERN,
                arguments = listOf(navArgument(Routes.ARG_ATTEMPT_ID) { type = NavType.LongType })
            ) { entry ->
                val attemptId = entry.arguments?.getLong(Routes.ARG_ATTEMPT_ID) ?: return@composable
                McqResultScreen(
                    attemptId = attemptId,
                    onBack    = { nav.popBackStack() },
                )
            }

            // ---------- Notes / PDF ----------
            composable(
                route     = Routes.NOTES_PATTERN,
                arguments = listOf(navArgument(Routes.ARG_DOC_ID) { type = NavType.LongType })
            ) { entry ->
                val docId = entry.arguments?.getLong(Routes.ARG_DOC_ID) ?: return@composable
                NotesScreen(
                    documentId = docId,
                    onBack     = { nav.popBackStack() },
                )
            }
        }
    }
}

@Composable
private fun BottomNavBar(currentRoute: String?, onNavigate: (String) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = currentRoute == Routes.HOME,
            onClick  = { onNavigate(Routes.HOME) },
            icon     = { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Library") },
            label    = { Text("Library") }
        )
        NavigationBarItem(
            selected = currentRoute == Routes.SETTINGS,
            onClick  = { onNavigate(Routes.SETTINGS) },
            icon     = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label    = { Text("Settings") }
        )
    }
}
