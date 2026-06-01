package com.reader.app.ui.screens.generate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.repository.DocumentRepository
import com.reader.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Tiny VM — only loads the doc title for the screen header. Both child
 * destinations (MCQ home, Notes) load their own document context, so
 * we don't pre-fetch the transcript here.
 */
class GenerateViewModel(
    private val documentId: Long,
    private val docs: DocumentRepository,
) : ViewModel() {

    data class UiState(val title: String = "")

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            docs.get(documentId)?.let { doc ->
                _state.update { it.copy(title = doc.title) }
            }
        }
    }

    companion object {
        fun factory(documentId: Long) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                GenerateViewModel(
                    documentId = documentId,
                    docs       = ServiceLocator.documentRepository,
                ) as T
        }
    }
}
