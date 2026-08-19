package com.chatbuddy.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ModelState
import com.chatbuddy.domain.repository.ModelRepository
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

data class HomeUiState(
    val storageConfigured: Boolean = false,
    val modelStates: List<ModelState> = emptyList(),
    val selectedTab: HomeTab = HomeTab.CHAT,
    val busy: Boolean = false,
    val pendingChatText: String? = null,
    val pendingTranslationText: String? = null
)

enum class HomeTab { CHAT, TRANSLATE, OCR, SETTINGS }

sealed interface HomeEvent {
    data class Message(val value: String) : HomeEvent
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val modelRepository: ModelRepository
) : ViewModel() {
    private val _state = MutableStateFlow(
        HomeUiState(storageConfigured = modelRepository.hasStorageFolder())
    )
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HomeEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            modelRepository.observeStates().collect { models ->
                _state.update { it.copy(modelStates = models) }
            }
        }
        viewModelScope.launch {
            when (val result = modelRepository.listArtifacts()) {
                is AppResult.Success -> result.data.forEach { modelRepository.verify(it.id) }
                is AppResult.Error, AppResult.Loading -> Unit
            }
        }
    }

    fun selectTab(tab: HomeTab) = _state.update { it.copy(selectedTab = tab) }

    fun sendToChat(text: String) = _state.update { it.copy(pendingChatText = text, selectedTab = HomeTab.CHAT) }

    fun sendToTranslation(text: String) = _state.update { it.copy(pendingTranslationText = text, selectedTab = HomeTab.TRANSLATE) }

    fun consumeChatText() = _state.update { it.copy(pendingChatText = null) }

    fun consumeTranslationText() = _state.update { it.copy(pendingTranslationText = null) }

    fun selectStorageFolder(uri: String) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            when (val result = modelRepository.selectStorageFolder(uri)) {
                is AppResult.Success -> {
                    _state.update { it.copy(storageConfigured = true, busy = false) }
                    _events.emit(HomeEvent.Message("SAF folder ready"))
                }
                is AppResult.Error -> {
                    _state.update { it.copy(busy = false) }
                    _events.emit(HomeEvent.Message(result.message))
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun downloadModel(id: String) {
        viewModelScope.launch {
            when (val result = modelRepository.download(id)) {
                is AppResult.Success -> _events.emit(HomeEvent.Message("Download queued; progress is live"))
                is AppResult.Error -> _events.emit(HomeEvent.Message(result.message))
                AppResult.Loading -> Unit
            }
        }
    }

    fun pauseModel(id: String) {
        viewModelScope.launch {
            when (val result = modelRepository.pause(id)) {
                is AppResult.Success -> _events.emit(HomeEvent.Message("Download paused"))
                is AppResult.Error -> _events.emit(HomeEvent.Message(result.message))
                AppResult.Loading -> Unit
            }
        }
    }
}
