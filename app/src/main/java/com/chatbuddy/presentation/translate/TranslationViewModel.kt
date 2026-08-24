package com.chatbuddy.presentation.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.LanguageOption
import com.chatbuddy.domain.model.LiveTranslationPhase
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.model.TranslationResult
import com.chatbuddy.domain.model.VoiceCapabilities
import com.chatbuddy.domain.model.VoiceTranscript
import com.chatbuddy.domain.repository.TranslationRepository
import com.chatbuddy.domain.repository.VoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    val modelError: String? = null,
    val liveEnabled: Boolean = false,
    val livePhase: LiveTranslationPhase = LiveTranslationPhase.Idle,
    val liveTranscript: String = "",
    val liveTranslation: TranslationResult? = null,
    val liveError: String? = null,
    val liveChecking: Boolean = false,
    val liveSpeakTranslation: Boolean = true,
    val voiceCapabilities: VoiceCapabilities? = null
)

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val repository: TranslationRepository,
    private val voiceRepository: VoiceRepository
) : ViewModel() {
    private val _state = MutableStateFlow(TranslationUiState())
    val state: StateFlow<TranslationUiState> = _state.asStateFlow()
    private var translationJob: Job? = null
    private var modelJob: Job? = null
    private var liveJob: Job? = null

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
        stopLiveTranslation()
        _state.update { it.copy(sourceLanguage = value, modelError = null) }
        refreshModelStatus()
        scheduleTranslation()
    }

    fun setTargetLanguage(value: String) {
        stopLiveTranslation()
        _state.update { it.copy(targetLanguage = value, modelError = null) }
        refreshModelStatus()
        scheduleTranslation()
    }

    fun setSourceText(value: String) { _state.update { it.copy(sourceText = value, error = null) }; scheduleTranslation() }

    fun swapLanguages() {
        stopLiveTranslation()
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

    fun setLiveSpeakTranslation(enabled: Boolean) {
        _state.update { it.copy(liveSpeakTranslation = enabled) }
    }

    fun toggleLiveTranslation() {
        if (_state.value.liveEnabled) stopLiveTranslation() else startLiveTranslation()
    }

    fun stopLiveTranslation() {
        liveJob?.cancel()
        liveJob = null
        _state.update {
            it.copy(
                liveEnabled = false,
                livePhase = LiveTranslationPhase.Idle,
                liveChecking = false
            )
        }
    }

    private fun startLiveTranslation() {
        val current = _state.value
        when {
            current.sourceLanguage.isBlank() || current.targetLanguage.isBlank() -> {
                _state.update {
                    it.copy(
                        liveEnabled = false,
                        livePhase = LiveTranslationPhase.Error,
                        liveError = "Select source and target languages first."
                    )
                }
            }
            current.modelChecking || current.modelDownloading -> _state.update {
                it.copy(
                    liveEnabled = false,
                    livePhase = LiveTranslationPhase.Error,
                    liveError = "Wait for the offline translation pack to finish preparing."
                )
            }
            !current.modelReady -> _state.update {
                it.copy(
                    liveEnabled = false,
                    livePhase = LiveTranslationPhase.Error,
                    liveError = "Download the selected offline language pack before starting live translation."
                )
            }
            else -> {
                liveJob?.cancel()
                liveJob = viewModelScope.launch {
                    _state.update {
                        it.copy(
                            liveChecking = true,
                            liveEnabled = false,
                            livePhase = LiveTranslationPhase.Starting,
                            liveError = null,
                            liveTranscript = "",
                            liveTranslation = null
                        )
                    }
                    val capabilities = when (val result = voiceRepository.capabilities(current.sourceLanguage)) {
                        is AppResult.Success -> result.data
                        is AppResult.Error -> {
                            _state.update {
                                it.copy(
                                    liveChecking = false,
                                    livePhase = LiveTranslationPhase.Error,
                                    liveError = result.message
                                )
                            }
                            return@launch
                        }
                        AppResult.Loading -> {
                            _state.update {
                                it.copy(
                                    liveChecking = false,
                                    livePhase = LiveTranslationPhase.Error,
                                    liveError = "Voice provider is still preparing. Try again."
                                )
                            }
                            return@launch
                        }
                    }
                    if (!capabilities.whisperReady) {
                        _state.update {
                            it.copy(
                                liveChecking = false,
                                livePhase = LiveTranslationPhase.Error,
                                voiceCapabilities = capabilities,
                                liveError = capabilities.message
                            )
                        }
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            liveChecking = false,
                            liveEnabled = true,
                            livePhase = LiveTranslationPhase.Listening,
                            voiceCapabilities = capabilities,
                            liveError = null
                        )
                    }
                    voiceRepository.transcribe(current.sourceLanguage).collect { transcript ->
                        when (transcript) {
                            is VoiceTranscript.Partial -> _state.update {
                                if (it.liveEnabled) {
                                    it.copy(
                                        livePhase = LiveTranslationPhase.Transcribing,
                                        liveTranscript = transcript.text,
                                        liveError = null
                                    )
                                } else it
                            }
                            is VoiceTranscript.Final -> translateLiveTurn(
                                transcript.text,
                                current.sourceLanguage,
                                current.targetLanguage,
                                capabilities
                            )
                            is VoiceTranscript.Failed -> _state.update {
                                it.copy(
                                    liveEnabled = false,
                                    livePhase = LiveTranslationPhase.Error,
                                    liveError = transcript.message
                                )
                            }
                        }
                    }
                    if (currentCoroutineContext().isActive && _state.value.liveEnabled) {
                        _state.update {
                            it.copy(
                                liveEnabled = false,
                                livePhase = LiveTranslationPhase.Error,
                                liveError = "Live microphone session ended."
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun translateLiveTurn(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        capabilities: VoiceCapabilities
    ) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            _state.update { it.copy(livePhase = LiveTranslationPhase.Listening) }
            return
        }
        _state.update {
            if (it.liveEnabled) {
                it.copy(
                    livePhase = LiveTranslationPhase.Translating,
                    liveTranscript = cleanText,
                    liveError = null
                )
            } else it
        }
        when (val result = repository.translate(TranslationRequest(cleanText, sourceLanguage, targetLanguage))) {
            is AppResult.Success -> {
                _state.update {
                    if (it.liveEnabled) {
                        it.copy(
                            liveTranslation = result.data,
                            livePhase = if (it.liveSpeakTranslation && capabilities.offlineTtsReady) {
                                LiveTranslationPhase.Speaking
                            } else {
                                LiveTranslationPhase.Listening
                            },
                            liveError = null
                        )
                    } else it
                }
                if (_state.value.liveEnabled &&
                    _state.value.liveSpeakTranslation &&
                    capabilities.offlineTtsReady
                ) {
                    when (val spoken = voiceRepository.speak(result.data.text, targetLanguage)) {
                        is AppResult.Success -> _state.update {
                            if (it.liveEnabled) it.copy(livePhase = LiveTranslationPhase.Listening) else it
                        }
                        is AppResult.Error -> _state.update {
                            if (it.liveEnabled) it.copy(
                                livePhase = LiveTranslationPhase.Listening,
                                liveError = spoken.message
                            ) else it
                        }
                        AppResult.Loading -> Unit
                    }
                }
            }
            is AppResult.Error -> _state.update {
                if (it.liveEnabled) it.copy(
                    livePhase = LiveTranslationPhase.Listening,
                    liveError = result.message
                ) else it
            }
            AppResult.Loading -> Unit
        }
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
