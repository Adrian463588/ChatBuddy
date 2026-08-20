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
    val error: String? = null,
    val modelReady: Boolean = false,
    val modelChecking: Boolean = true,
    val modelDownloading: Boolean = false,
    val modelError: String? = null
)

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val repository: TranslationRepository
) : ViewModel() {
    private val _state = MutableStateFlow(TranslationUiState())
    val state: StateFlow<TranslationUiState> = _state.asStateFlow()
    private var translationJob: Job? = null
    private var modelJob: Job? = null

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
                }.also { refreshModelStatus() }
                is AppResult.Error -> _state.update {
                    it.copy(
                        error = result.message,
                        modelChecking = false,
                        modelError = result.message
                    )
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun setSourceLanguage(value: String) {
        _state.update { it.copy(sourceLanguage = value, modelError = null) }
        refreshModelStatus()
        scheduleTranslation()
    }

    fun setTargetLanguage(value: String) {
        _state.update { it.copy(targetLanguage = value, modelError = null) }
        refreshModelStatus()
        scheduleTranslation()
    }

    fun setSourceText(value: String) { _state.update { it.copy(sourceText = value, error = null) }; scheduleTranslation() }

    fun swapLanguages() {
        _state.update {
            it.copy(
                sourceLanguage = it.targetLanguage,
                targetLanguage = it.sourceLanguage,
                modelError = null
            )
        }
        refreshModelStatus()
        scheduleTranslation()
    }

    fun downloadLanguageModels() {
        val current = _state.value
        if (current.sourceLanguage.isBlank() || current.targetLanguage.isBlank()) {
            _state.update { it.copy(modelError = "Select source and target languages first.") }
            return
        }
        modelJob?.cancel()
        modelJob = viewModelScope.launch {
            _state.update { it.copy(modelDownloading = true, modelChecking = false, modelError = null) }
            when (val result = repository.downloadModels(current.sourceLanguage, current.targetLanguage)) {
                is AppResult.Success -> {
                    if (_state.value.sourceLanguage == current.sourceLanguage &&
                        _state.value.targetLanguage == current.targetLanguage
                    ) {
                        _state.update {
                            it.copy(
                                modelReady = true,
                                modelChecking = false,
                                modelDownloading = false,
                                modelError = null
                            )
                        }
                        scheduleTranslation()
                    }
                }
                is AppResult.Error -> _state.update {
                    it.copy(
                        modelReady = false,
                        modelChecking = false,
                        modelDownloading = false,
                        modelError = result.message
                    )
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun refreshModelStatus() {
        modelJob?.cancel()
        modelJob = viewModelScope.launch {
            val current = _state.value
            if (current.sourceLanguage.isBlank() || current.targetLanguage.isBlank()) {
                _state.update { it.copy(modelReady = false, modelChecking = false) }
                return@launch
            }
            _state.update { it.copy(modelChecking = true, modelError = null) }
            when (val result = repository.modelStatus(current.sourceLanguage, current.targetLanguage)) {
                is AppResult.Success -> _state.update {
                    it.copy(modelReady = result.data.ready, modelChecking = false)
                }
                is AppResult.Error -> _state.update {
                    it.copy(modelReady = false, modelChecking = false, modelError = result.message)
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun scheduleTranslation() {
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            delay(300)
            val current = _state.value
            if (current.sourceText.isBlank()) {
                _state.update { it.copy(result = null, loading = false) }
                return@launch
            }
            if (!current.modelReady || current.modelChecking || current.modelDownloading) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            val request = TranslationRequest(current.sourceText, current.sourceLanguage, current.targetLanguage)
            when (val result = repository.translate(request)) {
                is AppResult.Success -> _state.update {
                    if (it.sourceText == request.text &&
                        it.sourceLanguage == request.sourceLanguage &&
                        it.targetLanguage == request.targetLanguage
                    ) {
                        it.copy(result = result.data, loading = false)
                    } else {
                        it
                    }
                }
                is AppResult.Error -> _state.update {
                    if (it.sourceText == request.text &&
                        it.sourceLanguage == request.sourceLanguage &&
                        it.targetLanguage == request.targetLanguage
                    ) {
                        it.copy(loading = false, error = result.message)
                    } else {
                        it
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }
}
