package com.chatbuddy.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.ChatMessage
import com.chatbuddy.domain.model.ChatRequest
import com.chatbuddy.domain.model.ChatStreamEvent
import com.chatbuddy.domain.model.Persona
import com.chatbuddy.domain.repository.ChatRepository
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
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val useRag: Boolean = true,
    val streaming: Boolean = false,
    val activePersona: Persona? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    personaRepository: PersonaRepository
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            personaRepository.observePersonas().collect { personas ->
                _state.update { it.copy(activePersona = personas.firstOrNull { persona -> persona.active }) }
            }
        }
    }

    fun setInput(value: String) = _state.update { it.copy(input = value) }
    fun setUseRag(enabled: Boolean) = _state.update { it.copy(useRag = enabled) }

    fun send() {
        val current = _state.value
        val text = current.input.trim()
        val persona = current.activePersona
        when {
            text.isEmpty() -> return
            persona == null -> {
                viewModelScope.launch { _events.emit("Create and activate a persona before chatting") }
                return
            }
            current.streaming -> return
        }
        _state.update {
            it.copy(
                input = "",
                streaming = true,
                messages = it.messages + ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.USER, text)
            )
        }
        viewModelScope.launch {
            chatRepository.stream(ChatRequest(text, persona, current.useRag)).collect { event ->
                when (event) {
                    ChatStreamEvent.Started -> Unit
                    is ChatStreamEvent.EvidenceFound -> Unit
                    is ChatStreamEvent.Token -> appendAssistant(event.value)
                    is ChatStreamEvent.Completed -> _state.update { it.copy(messages = it.messages + event.message) }
                    is ChatStreamEvent.Failed -> {
                        _state.update { it.copy(streaming = false) }
                        _events.emit(event.message)
                    }
                }
            }
            _state.update { it.copy(streaming = false) }
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
}
