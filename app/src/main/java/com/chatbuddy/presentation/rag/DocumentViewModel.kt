package com.chatbuddy.presentation.rag

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.DocumentRecord
import com.chatbuddy.domain.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DocumentUiState(
    val documents: List<DocumentRecord> = emptyList(),
    val processing: Boolean = false
)

@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val repository: DocumentRepository
) : ViewModel() {
    private val _state = MutableStateFlow(DocumentUiState())
    val state: StateFlow<DocumentUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch { repository.observeDocuments().collect { docs -> _state.update { it.copy(documents = docs) } } }
    }

    fun add(uri: String) {
        viewModelScope.launch {
            _state.update { it.copy(processing = true) }
            when (val result = repository.addDocument(uri)) {
                is AppResult.Success -> _events.emit("Document indexed")
                is AppResult.Error -> _events.emit(result.message)
                AppResult.Loading -> Unit
            }
            _state.update { it.copy(processing = false) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            when (val result = repository.deleteDocument(id)) {
                is AppResult.Success -> _events.emit("Document removed")
                is AppResult.Error -> _events.emit(result.message)
                AppResult.Loading -> Unit
            }
        }
    }
}
