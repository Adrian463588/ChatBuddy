package com.chatbuddy.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.repository.WebProviderSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WebSettingsUiState(
    val apiKeyInput: String = "",
    val braveApiKeyConfigured: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null
)

@HiltViewModel
class WebSettingsViewModel @Inject constructor(
    private val repository: WebProviderSettingsRepository
) : ViewModel() {
    private val _state = MutableStateFlow(WebSettingsUiState())
    val state: StateFlow<WebSettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.settings.collect { settings ->
                _state.update { it.copy(braveApiKeyConfigured = settings.braveApiKeyConfigured) }
            }
        }
    }

    fun setApiKey(value: String) {
        _state.update { it.copy(apiKeyInput = value, message = null, error = null) }
    }

    fun save() {
        val value = _state.value.apiKeyInput
        if (value.isBlank()) {
            _state.update { it.copy(error = "Enter an API key or clear the configured key") }
            return
        }
        updateKeyState(busy = true, message = null, error = null)
        viewModelScope.launch {
            when (val result = repository.saveBraveApiKey(value)) {
                is AppResult.Success -> _state.update {
                    it.copy(apiKeyInput = "", busy = false, message = "Search provider key saved securely", error = null)
                }
                is AppResult.Error -> _state.update { it.copy(busy = false, error = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun clear() {
        updateKeyState(busy = true, message = null, error = null)
        viewModelScope.launch {
            when (val result = repository.clearBraveApiKey()) {
                is AppResult.Success -> _state.update {
                    it.copy(apiKeyInput = "", busy = false, message = "Search provider key removed", error = null)
                }
                is AppResult.Error -> _state.update { it.copy(busy = false, error = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun updateKeyState(busy: Boolean, message: String?, error: String?) {
        _state.update { it.copy(busy = busy, message = message, error = error) }
    }
}
