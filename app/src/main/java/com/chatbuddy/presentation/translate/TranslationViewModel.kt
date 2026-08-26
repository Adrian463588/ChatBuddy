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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    private val translationGeneration = AtomicLong(0L)
    private val modelGeneration = AtomicLong(0L)
    private val liveGeneration = AtomicLong(0L)

    init {
        viewModelScope.launch {
            val result = try {
                repository.availableLanguages()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                AppResult.Error("Unable to load translation languages.", error)
            }
            when (result) {
                is AppResult.Success -> {
                    val languages = result.data
                    val tags = languages.map(LanguageOption::tag).toSet()
                    val source = if ("en" in tags) "en" else languages.firstOrNull()?.tag.orEmpty()
                    val target = if ("id" in tags) "id" else languages
                        .firstOrNull { it.tag != source }
                        ?.tag
                        .orEmpty()
                    _state.update {
                        it.copy(
                            languages = languages,
                            sourceLanguage = source,
                            targetLanguage = target
                        )
                    }
                    refreshModelStatus()
                }
                is AppResult.Error -> _state.update {
                    it.copy(
                        error = result.message,
                        modelChecking = false,
                        modelError = result.message
                    )
                }
                AppResult.Loading -> _state.update { it.copy(modelChecking = true) }
            }
        }
    }

    fun setSourceLanguage(value: String) {
        stopLiveTranslationInternal(clearError = true)
        _state.update { it.copy(sourceLanguage = value, modelError = null) }
        refreshModelStatus()
        scheduleTranslation()
    }

    fun setTargetLanguage(value: String) {
        stopLiveTranslationInternal(clearError = true)
        _state.update { it.copy(targetLanguage = value, modelError = null) }
        refreshModelStatus()
        scheduleTranslation()
    }

    fun setSourceText(value: String) {
        _state.update { it.copy(sourceText = value, error = null) }
        scheduleTranslation()
    }

    fun swapLanguages() {
        stopLiveTranslationInternal(clearError = true)
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
        val current = _state.value
        if (current.liveEnabled || current.liveChecking || current.livePhase == LiveTranslationPhase.Starting) {
            stopLiveTranslation()
        } else {
            startLiveTranslation()
        }
    }

    fun stopLiveTranslation() {
        stopLiveTranslationInternal(clearError = false)
    }

    private fun stopLiveTranslationInternal(clearError: Boolean) {
        liveGeneration.incrementAndGet()
        liveJob?.cancel()
        liveJob = null
        _state.update {
            it.copy(
                liveEnabled = false,
                livePhase = LiveTranslationPhase.Idle,
                liveChecking = false,
                liveError = if (clearError) null else it.liveError
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
                val session = liveGeneration.incrementAndGet()
                liveJob?.cancel()
                liveJob = viewModelScope.launch {
                    updateLive(session, allowIdle = true) {
                        it.copy(
                            liveChecking = true,
                            liveEnabled = false,
                            livePhase = LiveTranslationPhase.Starting,
                            liveError = null,
                            liveTranscript = "",
                            liveTranslation = null
                        )
                    }

                    val capabilitiesResult = try {
                        voiceRepository.capabilities(current.sourceLanguage)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        AppResult.Error("Voice provider is unavailable.", error)
                    }
                    currentCoroutineContext().ensureActive()
                    when (capabilitiesResult) {
                        is AppResult.Error -> {
                            updateLive(session, allowIdle = true) {
                                it.copy(
                                    liveChecking = false,
                                    livePhase = LiveTranslationPhase.Error,
                                    liveError = capabilitiesResult.message
                                )
                            }
                            return@launch
                        }
                        AppResult.Loading -> {
                            updateLive(session, allowIdle = true) {
                                it.copy(
                                    liveChecking = false,
                                    livePhase = LiveTranslationPhase.Error,
                                    liveError = "Voice provider is still preparing. Try again."
                                )
                            }
                            return@launch
                        }
                        is AppResult.Success -> Unit
                    }
                    val capabilities = (capabilitiesResult as AppResult.Success).data
                    if (!capabilities.whisperReady) {
                        updateLive(session, allowIdle = true) {
                            it.copy(
                                liveChecking = false,
                                livePhase = LiveTranslationPhase.Error,
                                voiceCapabilities = capabilities,
                                liveError = capabilities.message
                            )
                        }
                        return@launch
                    }

                    updateLive(session, allowIdle = true) {
                        it.copy(
                            liveChecking = false,
                            liveEnabled = true,
                            livePhase = LiveTranslationPhase.Listening,
                            voiceCapabilities = capabilities,
                            liveError = null
                        )
                    }

                    try {
                        voiceRepository.transcribe(current.sourceLanguage).collect { transcript ->
                            if (!isLiveSessionCurrent(session)) return@collect
                            when (transcript) {
                                is VoiceTranscript.Partial -> updateLive(session) {
                                    it.copy(
                                        livePhase = LiveTranslationPhase.Transcribing,
                                        liveTranscript = transcript.text,
                                        liveError = null
                                    )
                                }
                                is VoiceTranscript.Final -> translateLiveTurn(
                                    session = session,
                                    text = transcript.text,
                                    sourceLanguage = current.sourceLanguage,
                                    targetLanguage = current.targetLanguage,
                                    capabilities = capabilities
                                )
                                is VoiceTranscript.Failed -> updateLive(session) {
                                    it.copy(
                                        liveEnabled = false,
                                        livePhase = LiveTranslationPhase.Error,
                                        liveError = transcript.message
                                    )
                                }
                            }
                        }
                        if (currentCoroutineContext().isActive &&
                            isLiveSessionCurrent(session) &&
                            _state.value.liveEnabled
                        ) {
                            updateLive(session) {
                                it.copy(
                                    liveEnabled = false,
                                    livePhase = LiveTranslationPhase.Error,
                                    liveError = "Live microphone session ended."
                                )
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        updateLive(session) {
                            it.copy(
                                liveEnabled = false,
                                livePhase = LiveTranslationPhase.Error,
                                liveError = error.message ?: "Live translation stopped unexpectedly."
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun translateLiveTurn(
        session: Long,
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        capabilities: VoiceCapabilities
    ) {
        if (!isLiveSessionCurrent(session)) return
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            updateLive(session) { it.copy(livePhase = LiveTranslationPhase.Listening) }
            return
        }
        updateLive(session) {
            it.copy(
                livePhase = LiveTranslationPhase.Translating,
                liveTranscript = cleanText,
                liveError = null
            )
        }

        val result = try {
            repository.translate(TranslationRequest(cleanText, sourceLanguage, targetLanguage))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            AppResult.Error("Live translation failed.", error)
        }
        currentCoroutineContext().ensureActive()
        when (result) {
            is AppResult.Success -> {
                if (result.data.text.isBlank()) {
                    updateLive(session) {
                        it.copy(
                            livePhase = LiveTranslationPhase.Listening,
                            liveError = "Translation provider returned no text."
                        )
                    }
                    return
                }
                val shouldSpeak = isLiveSessionCurrent(session) &&
                    _state.value.liveSpeakTranslation &&
                    capabilities.offlineTtsReady
                updateLive(session) {
                    it.copy(
                        liveTranslation = result.data,
                        livePhase = if (shouldSpeak) {
                            LiveTranslationPhase.Speaking
                        } else {
                            LiveTranslationPhase.Listening
                        },
                        liveError = null
                    )
                }
                if (shouldSpeak) {
                    val spoken = try {
                        voiceRepository.speak(result.data.text, targetLanguage)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        AppResult.Error("Offline TTS failed.", error)
                    }
                    when (spoken) {
                        is AppResult.Success -> updateLive(session) {
                            it.copy(livePhase = LiveTranslationPhase.Listening)
                        }
                        is AppResult.Error -> updateLive(session) {
                            it.copy(
                                livePhase = LiveTranslationPhase.Listening,
                                liveError = spoken.message
                            )
                        }
                        AppResult.Loading -> updateLive(session) {
                            it.copy(livePhase = LiveTranslationPhase.Listening)
                        }
                    }
                }
            }
            is AppResult.Error -> updateLive(session) {
                it.copy(
                    livePhase = LiveTranslationPhase.Listening,
                    liveError = result.message
                )
            }
            AppResult.Loading -> updateLive(session) {
                it.copy(
                    livePhase = LiveTranslationPhase.Listening,
                    liveError = "Translation provider is still preparing."
                )
            }
        }
    }

    fun downloadLanguageModels() {
        val current = _state.value
        if (current.sourceLanguage.isBlank() || current.targetLanguage.isBlank()) {
            _state.update { it.copy(modelError = "Select source and target languages first.") }
            return
        }
        val request = modelGeneration.incrementAndGet()
        modelJob?.cancel()
        modelJob = viewModelScope.launch {
            updateModel(request, current.sourceLanguage, current.targetLanguage) {
                it.copy(modelDownloading = true, modelChecking = false, modelError = null)
            }
            val downloadResult = try {
                repository.downloadModels(current.sourceLanguage, current.targetLanguage)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                AppResult.Error("Language pack download failed.", error)
            }
            currentCoroutineContext().ensureActive()
            when (downloadResult) {
                is AppResult.Error -> updateModel(request, current.sourceLanguage, current.targetLanguage) {
                    it.copy(
                        modelReady = false,
                        modelChecking = false,
                        modelDownloading = false,
                        modelError = downloadResult.message
                    )
                }
                AppResult.Loading -> updateModel(request, current.sourceLanguage, current.targetLanguage) {
                    it.copy(modelChecking = true, modelDownloading = true)
                }
                is AppResult.Success -> {
                    updateModel(request, current.sourceLanguage, current.targetLanguage) {
                        it.copy(modelChecking = true, modelDownloading = false, modelError = null)
                    }
                    val status = try {
                        repository.modelStatus(current.sourceLanguage, current.targetLanguage)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (error: Throwable) {
                        AppResult.Error("Unable to verify the downloaded language pack.", error)
                    }
                    when (status) {
                        is AppResult.Success -> updateModel(request, current.sourceLanguage, current.targetLanguage) {
                            it.copy(
                                modelReady = status.data.ready,
                                modelChecking = false,
                                modelDownloading = false,
                                modelError = if (status.data.ready) null else {
                                    "Downloaded language pack is not ready yet. Try again."
                                }
                            )
                        }
                        is AppResult.Error -> updateModel(request, current.sourceLanguage, current.targetLanguage) {
                            it.copy(
                                modelReady = false,
                                modelChecking = false,
                                modelDownloading = false,
                                modelError = status.message
                            )
                        }
                        AppResult.Loading -> updateModel(request, current.sourceLanguage, current.targetLanguage) {
                            it.copy(modelReady = false, modelChecking = true, modelDownloading = false)
                        }
                    }
                }
            }
        }
    }

    private fun refreshModelStatus() {
        val request = modelGeneration.incrementAndGet()
        modelJob?.cancel()
        modelJob = viewModelScope.launch {
            val current = _state.value
            if (current.sourceLanguage.isBlank() || current.targetLanguage.isBlank()) {
                updateModel(request, current.sourceLanguage, current.targetLanguage) {
                    it.copy(modelReady = false, modelChecking = false, modelDownloading = false)
                }
                return@launch
            }
            updateModel(request, current.sourceLanguage, current.targetLanguage) {
                it.copy(modelChecking = true, modelError = null)
            }
            val result = try {
                repository.modelStatus(current.sourceLanguage, current.targetLanguage)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                AppResult.Error("Unable to check offline language packs.", error)
            }
            currentCoroutineContext().ensureActive()
            when (result) {
                is AppResult.Success -> updateModel(request, current.sourceLanguage, current.targetLanguage) {
                    it.copy(modelReady = result.data.ready, modelChecking = false, modelDownloading = false)
                }
                is AppResult.Error -> updateModel(request, current.sourceLanguage, current.targetLanguage) {
                    it.copy(
                        modelReady = false,
                        modelChecking = false,
                        modelDownloading = false,
                        modelError = result.message
                    )
                }
                AppResult.Loading -> updateModel(request, current.sourceLanguage, current.targetLanguage) {
                    it.copy(modelChecking = true)
                }
            }
        }
    }

    private fun scheduleTranslation() {
        val requestId = translationGeneration.incrementAndGet()
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            delay(300)
            currentCoroutineContext().ensureActive()
            val current = _state.value
            if (requestId != translationGeneration.get()) return@launch
            if (current.sourceText.isBlank()) {
                _state.update { it.copy(result = null, loading = false, error = null) }
                return@launch
            }
            if (!current.modelReady || current.modelChecking || current.modelDownloading) {
                _state.update { it.copy(loading = false) }
                return@launch
            }
            _state.update { it.copy(loading = true, error = null) }
            val request = TranslationRequest(current.sourceText, current.sourceLanguage, current.targetLanguage)
            val result = try {
                repository.translate(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                AppResult.Error("Translation failed.", error)
            }
            currentCoroutineContext().ensureActive()
            if (requestId != translationGeneration.get()) return@launch
            when (result) {
                is AppResult.Success -> _state.update {
                    if (it.sourceText == request.text &&
                        it.sourceLanguage == request.sourceLanguage &&
                        it.targetLanguage == request.targetLanguage
                    ) {
                        it.copy(result = result.data, loading = false, error = null)
                    } else it
                }
                is AppResult.Error -> _state.update {
                    if (it.sourceText == request.text &&
                        it.sourceLanguage == request.sourceLanguage &&
                        it.targetLanguage == request.targetLanguage
                    ) {
                        it.copy(loading = false, error = result.message)
                    } else it
                }
                AppResult.Loading -> _state.update { it.copy(loading = true) }
            }
        }
    }

    private fun isLiveSessionCurrent(session: Long): Boolean = liveGeneration.get() == session

    private inline fun updateLive(
        session: Long,
        allowIdle: Boolean = false,
        crossinline transform: (TranslationUiState) -> TranslationUiState
    ) {
        _state.update { current ->
            if (isLiveSessionCurrent(session) && (allowIdle || current.liveEnabled)) {
                transform(current)
            } else {
                current
            }
        }
    }

    private inline fun updateModel(
        request: Long,
        sourceLanguage: String,
        targetLanguage: String,
        crossinline transform: (TranslationUiState) -> TranslationUiState
    ) {
        _state.update { current ->
            if (modelGeneration.get() == request &&
                current.sourceLanguage == sourceLanguage &&
                current.targetLanguage == targetLanguage
            ) {
                transform(current)
            } else {
                current
            }
        }
    }
}
