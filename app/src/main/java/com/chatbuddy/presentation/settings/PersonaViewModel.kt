package com.chatbuddy.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.Persona
import com.chatbuddy.domain.model.PersonaTemplate
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
import java.util.UUID

data class PersonaUiState(val personas: List<Persona> = emptyList(), val editing: Persona? = null)

data class PersonaEvent(val message: String, val isError: Boolean)

@HiltViewModel
class PersonaViewModel @Inject constructor(
    private val repository: PersonaRepository,
    private val validate: ValidatePersonaUseCase
) : ViewModel() {
    private val _state = MutableStateFlow(PersonaUiState())
    val state: StateFlow<PersonaUiState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<PersonaEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PersonaEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { repository.observePersonas().collect { personas -> _state.update { it.copy(personas = personas) } } }
    }

    fun save(persona: Persona) {
        persist(persona, "Persona saved")
    }

    fun saveAndActivate(persona: Persona) {
        persist(persona.copy(active = true), "Persona activated")
    }

    fun activateTemplate(template: PersonaTemplate) {
        saveAndActivate(template.toPersona(active = true))
    }

    private fun persist(persona: Persona, successMessage: String) {
        viewModelScope.launch {
            when (val valid = validate(persona)) {
                is AppResult.Success -> when (val saved = repository.save(valid.data)) {
                    is AppResult.Success -> _events.emit(
                        PersonaEvent(successMessage, isError = false)
                    )
                    is AppResult.Error -> _events.emit(PersonaEvent(saved.message, isError = true))
                    AppResult.Loading -> Unit
                }
                is AppResult.Error -> _events.emit(PersonaEvent(valid.message, isError = true))
                AppResult.Loading -> Unit
            }
        }
    }

    fun setActive(id: String) {
        viewModelScope.launch {
            when (val result = repository.setActive(id)) {
                is AppResult.Success -> _events.emit(PersonaEvent("Persona activated", isError = false))
                is AppResult.Error -> _events.emit(PersonaEvent(result.message, isError = true))
                AppResult.Loading -> Unit
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            when (val result = repository.delete(id)) {
                is AppResult.Success -> _events.emit(PersonaEvent("Persona deleted", isError = false))
                is AppResult.Error -> _events.emit(PersonaEvent(result.message, isError = true))
                AppResult.Loading -> Unit
            }
        }
    }

    fun edit(persona: Persona) = _state.update { it.copy(editing = persona) }

    fun clearEditing() = _state.update { it.copy(editing = null) }

    fun duplicate(persona: Persona) {
        save(
            persona.copy(
                id = UUID.randomUUID().toString(),
                name = "${persona.name} copy",
                active = false
            )
        )
    }
}
