package com.chatbuddy.presentation.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.LanguageOption
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.model.TranslationResult
import com.chatbuddy.domain.repository.TranslationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TranslationUiState(
    val languages: List<LanguageOption> = emptyList(),
    val sourceLanguage: String = "en",
    val targetLanguage: String = "id",
    val sourceText: String = "",
    val result: TranslationResult? = null,
    val loading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val repository: TranslationRepository
) : ViewModel() {
    private val _state = MutableStateFlow(TranslationUiState())
    val state: StateFlow<TranslationUiState> = _state.asStateFlow()
    private var translationJob: Job? = null

    init {
        viewModelScope.launch {
            when (val result = repository.availableLanguages()) {
                is AppResult.Success -> _state.update { current ->
                    val tags = result.data.map(LanguageOption::tag).toSet()
                    current.copy(
                        languages = result.data,
                        sourceLanguage = if ("en" in tags) "en" else result.data.firstOrNull()?.tag.orEmpty(),
                        targetLanguage = if ("id" in tags) "id" else result.data.drop(1).firstOrNull()?.tag.orEmpty()
                    )
                }
                is AppResult.Error -> _state.update { it.copy(error = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun setSourceLanguage(value: String) { _state.update { it.copy(sourceLanguage = value) }; scheduleTranslation() }
    fun setTargetLanguage(value: String) { _state.update { it.copy(targetLanguage = value) }; scheduleTranslation() }
    fun setSourceText(value: String) { _state.update { it.copy(sourceText = value, error = null) }; scheduleTranslation() }

    fun swapLanguages() = _state.update { it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage) }.also { scheduleTranslation() }

    private fun scheduleTranslation() {
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            delay(300)
            val current = _state.value
            if (current.sourceText.isBlank()) {
                _state.update { it.copy(result = null, loading = false) }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            when (val result = repository.translate(TranslationRequest(current.sourceText, current.sourceLanguage, current.targetLanguage))) {
                is AppResult.Success -> _state.update { it.copy(result = result.data, loading = false) }
                is AppResult.Error -> _state.update { it.copy(loading = false, error = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }
}
