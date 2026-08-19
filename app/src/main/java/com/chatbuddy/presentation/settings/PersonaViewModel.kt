package com.chatbuddy.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.Persona
import com.chatbuddy.domain.repository.PersonaRepository
import com.chatbuddy.domain.usecase.ValidatePersonaUseCase
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

data class PersonaUiState(val personas: List<Persona> = emptyList(), val editing: Persona? = null)

@HiltViewModel
class PersonaViewModel @Inject constructor(
    private val repository: PersonaRepository,
    private val validate: ValidatePersonaUseCase
) : ViewModel() {
    private val _state = MutableStateFlow(PersonaUiState())
    val state: StateFlow<PersonaUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch { repository.observePersonas().collect { personas -> _state.update { it.copy(personas = personas) } } }
    }

    fun save(persona: Persona) {
        viewModelScope.launch {
            when (val valid = validate(persona)) {
                is AppResult.Success -> when (val saved = repository.save(valid.data)) {
                    is AppResult.Success -> _events.emit("Persona saved")
                    is AppResult.Error -> _events.emit(saved.message)
                    AppResult.Loading -> Unit
                }
                is AppResult.Error -> _events.emit(valid.message)
                AppResult.Loading -> Unit
            }
        }
    }

    fun setActive(id: String) {
        viewModelScope.launch {
            when (val result = repository.setActive(id)) {
                is AppResult.Success -> _events.emit("Persona activated")
                is AppResult.Error -> _events.emit(result.message)
                AppResult.Loading -> Unit
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            when (val result = repository.delete(id)) {
                is AppResult.Success -> _events.emit("Persona deleted")
                is AppResult.Error -> _events.emit(result.message)
                AppResult.Loading -> Unit
            }
        }
    }
}
