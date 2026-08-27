package com.chatbuddy.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.ChatMessage
import com.chatbuddy.domain.model.ChatRequest
import com.chatbuddy.domain.model.ChatStreamEvent
import com.chatbuddy.domain.model.BuiltInPersonaCatalog
import com.chatbuddy.domain.model.Persona
import com.chatbuddy.domain.repository.ChatRepository
import com.chatbuddy.domain.repository.ModelRepository
import com.chatbuddy.domain.repository.PersonaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val useRag: Boolean = true,
    val ragAvailable: Boolean = false,
    val allowWebFallback: Boolean = false,
    val streaming: Boolean = false,
    val webSearching: Boolean = false,
    val errorMessage: String? = null,
    val activePersona: Persona? = null,
    val personaActionInProgress: Boolean = false
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val personaRepository: PersonaRepository,
    modelRepository: ModelRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun activateDefaultPersona() {
        if (_state.value.personaActionInProgress) return
        _state.update { it.copy(personaActionInProgress = true, errorMessage = null) }
        viewModelScope.launch {
            val result = personaRepository.save(BuiltInPersonaCatalog.default.toPersona(active = true))
            when (result) {
                is com.chatbuddy.domain.model.AppResult.Success -> _events.emit("Sunny Companion is ready")
                is com.chatbuddy.domain.model.AppResult.Error -> {
                    _state.update { it.copy(errorMessage = result.message) }
                    _events.emit(result.message)
                }
                com.chatbuddy.domain.model.AppResult.Loading -> Unit
            }
            _state.update { it.copy(personaActionInProgress = false) }
        }
    }

    init {
        viewModelScope.launch {
            personaRepository.observePersonas().collect { personas ->
                _state.update { it.copy(activePersona = personas.firstOrNull { persona -> persona.active }) }
            }
        }
        viewModelScope.launch {
            modelRepository.observeStates().collect { models ->
                val required = setOf(
                    "all-minilm-l6-v2-qint8-arm64",
                    "all-minilm-l6-v2-vocab"
                )
                val ready = required.all { id ->
                    models.firstOrNull { it.artifact.id == id }?.status is com.chatbuddy.domain.model.ModelStatus.Ready
                }
                _state.update { current ->
                    current.copy(
                        ragAvailable = ready,
                        useRag = if (ready) {
                            current.useRag || current.messages.isEmpty()
                        } else {
                            false
                        }
                    )
                }
            }
        }
    }

    fun setInput(value: String) = _state.update { it.copy(input = value) }
    fun setUseRag(enabled: Boolean) {
        if (enabled && !_state.value.ragAvailable) {
            val message = "Download the embedding models before using local documents"
            _state.update { it.copy(errorMessage = message) }
            viewModelScope.launch { _events.emit(message) }
        } else {
            _state.update { it.copy(useRag = enabled, errorMessage = null) }
        }
    }
    fun setAllowWebFallback(enabled: Boolean) = _state.update { it.copy(allowWebFallback = enabled) }
    fun clearError() = _state.update { it.copy(errorMessage = null) }

    fun send() {
        val current = _state.value
        val text = current.input.trim()
        val persona = current.activePersona
        when {
            text.isEmpty() -> return
            persona == null -> {
                val message = "Create and activate a persona before chatting"
                _state.update { it.copy(errorMessage = message) }
                viewModelScope.launch { _events.emit(message) }
                return
            }
            current.streaming -> return
        }
        _state.update {
            it.copy(
                input = "",
                streaming = true,
                webSearching = false,
                errorMessage = null,
                messages = it.messages + ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.USER, text)
            )
        }
        viewModelScope.launch {
            try {
                chatRepository.stream(
                    ChatRequest(
                        text = text,
                        persona = persona,
                        useRag = current.useRag,
                        allowWebFallback = current.allowWebFallback,
                        history = current.messages.takeLast(MAX_HISTORY_MESSAGES)
                    )
                ).collect { event ->
                    when (event) {
                        ChatStreamEvent.Started -> Unit
                        ChatStreamEvent.WebSearchStarted -> _state.update { it.copy(webSearching = true) }
                        is ChatStreamEvent.SourcesFound -> _state.update {
                            it.copy(webSearching = false)
                        }
                        is ChatStreamEvent.Token -> appendAssistant(event.value)
                        is ChatStreamEvent.Completed -> completeAssistant(event.message)
                        is ChatStreamEvent.Failed -> {
                            removeAssistantDraft()
                            _state.update {
                                it.copy(
                                    streaming = false,
                                    webSearching = false,
                                    errorMessage = event.message
                                )
                            }
                            _events.emit(event.message)
                        }
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                removeAssistantDraft()
                val message = "Chat failed: ${error.message ?: "unknown error"}"
                _state.update {
                    it.copy(
                        streaming = false,
                        webSearching = false,
                        errorMessage = message
                    )
                }
                _events.emit(message)
            } finally {
                if (currentCoroutineContext().isActive) {
                    _state.update { it.copy(streaming = false, webSearching = false) }
                }
            }
        }
    }

    private fun appendAssistant(token: String) = _state.update { current ->
        val last = current.messages.lastOrNull()
        if (last?.role == ChatMessage.Role.ASSISTANT) {
            current.copy(messages = current.messages.dropLast(1) + last.copy(text = last.text + token))
        } else {
            current.copy(messages = current.messages + ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.ASSISTANT, token))
        }
    }

    private fun completeAssistant(message: ChatMessage) = _state.update { current ->
        val last = current.messages.lastOrNull()
        if (last?.role == ChatMessage.Role.ASSISTANT) {
            current.copy(messages = current.messages.dropLast(1) + message)
        } else {
            current.copy(messages = current.messages + message)
        }
    }

    private fun removeAssistantDraft() = _state.update { current ->
        val last = current.messages.lastOrNull()
        if (last?.role == ChatMessage.Role.ASSISTANT) {
            current.copy(messages = current.messages.dropLast(1))
        } else {
            current
        }
    }

    companion object {
        private const val MAX_HISTORY_MESSAGES = 8
    }
}
