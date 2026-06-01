package com.reader.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.reader.app.data.local.entity.DocumentEntity
import com.reader.app.data.repository.DocumentRepository
import com.reader.app.di.ServiceLocator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

class HomeViewModel(
    private val docs: DocumentRepository
) : ViewModel() {

    val documents: StateFlow<List<DocumentEntity>> =
        docs.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    companion object {
        val Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                HomeViewModel(ServiceLocator.documentRepository) as T
        }
    }
}
