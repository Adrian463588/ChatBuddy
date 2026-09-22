package com.chatbuddy.presentation.translate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.LanguageOption
import com.chatbuddy.domain.model.LiveConversationTurn
import com.chatbuddy.domain.model.LiveSpeaker
import com.chatbuddy.domain.model.LiveTranslationPhase
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.model.TranslationResult
import com.chatbuddy.domain.model.TranslationHistoryEntry
import com.chatbuddy.domain.model.VoiceCapabilities
import com.chatbuddy.domain.model.VoiceTranscript
import com.chatbuddy.domain.repository.TranslationRepository
import com.chatbuddy.domain.repository.TranslationHistoryRepository
import com.chatbuddy.domain.repository.VoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class TranslateMode {
    LIVE_CONVERSATION,
    TEXT_INPUT
}

enum class SayMiConversationMode {
    FREE_TALK,      // Mode Bicara Bebas (Continuous hands-free auto turn-taking)
    TOUCH_TWO_WAY   // Mode Earphone + Ponsel (Manual tap-to-speak dual mic)
}

data class TranslationUiState(
    val translateMode: TranslateMode = TranslateMode.LIVE_CONVERSATION,
    val sayMiMode: SayMiConversationMode = SayMiConversationMode.TOUCH_TWO_WAY,
    val partnerFlipped: Boolean = false,
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
    val autoTurnTaking: Boolean = false,
    val voiceCapabilities: VoiceCapabilities? = null,
    val activeSpeaker: LiveSpeaker? = null,
    val conversationTurns: List<LiveConversationTurn> = emptyList(),
    val activePlayingAudioText: String? = null,
    val history: List<TranslationHistoryEntry> = emptyList(),
    val historyError: String? = null
)

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val repository: TranslationRepository,
    private val voiceRepository: VoiceRepository,
    private val historyRepository: TranslationHistoryRepository
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
            historyRepository.observeRecent()
                .catch { error ->
                    _state.update {
                        it.copy(
                            historyError = error.message
                                ?.trim()
                                ?.takeIf(String::isNotEmpty)
                                ?: "Unable to load translation history."
                        )
                    }
                }
                .collect { entries ->
                    _state.update { it.copy(history = entries, historyError = null) }
                }
        }
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

    fun setAutoTurnTaking(enabled: Boolean) {
        _state.update {
            it.copy(
                autoTurnTaking = enabled,
                sayMiMode = if (enabled) SayMiConversationMode.FREE_TALK else SayMiConversationMode.TOUCH_TWO_WAY
            )
        }
    }

    fun setSayMiMode(mode: SayMiConversationMode) {
        _state.update {
            it.copy(
                sayMiMode = mode,
                autoTurnTaking = (mode == SayMiConversationMode.FREE_TALK)
            )
        }
    }

    fun setTranslateMode(mode: TranslateMode) {
        if (_state.value.translateMode != mode && mode == TranslateMode.TEXT_INPUT) {
            stopLiveTranslation()
        }
        _state.update { it.copy(translateMode = mode) }
    }

    fun togglePartnerFlipped() {
        _state.update { it.copy(partnerFlipped = !it.partnerFlipped) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            when (val result = historyRepository.clear()) {
                is AppResult.Success -> _state.update { it.copy(historyError = null) }
                is AppResult.Error -> _state.update { it.copy(historyError = result.message) }
                AppResult.Loading -> Unit
            }
        }
    }

    fun toggleLiveTranslation() {
        val current = _state.value
        if (current.liveEnabled || current.liveChecking || current.livePhase == LiveTranslationPhase.Starting) {
            stopLiveTranslation()
        } else {
            startLiveTranslation(LiveSpeaker.SPEAKER_A)
        }
    }

    fun toggleSpeaker(speaker: LiveSpeaker) {
        val current = _state.value
        if ((current.liveEnabled || current.liveChecking || current.livePhase == LiveTranslationPhase.Starting) &&
            current.activeSpeaker == speaker
        ) {
            stopLiveTranslation()
        } else {
            startLiveTranslation(speaker)
        }
    }

    fun stopLiveTranslation() {
        stopLiveTranslationInternal(clearError = false)
    }

    fun replayTurnSpeech(turn: LiveConversationTurn, speakTranslation: Boolean = true) {
        val textToSpeak = if (speakTranslation) turn.translatedText else turn.sourceText
        val langToSpeak = if (speakTranslation) turn.targetLanguage else turn.sourceLanguage
        if (textToSpeak.isBlank()) return

        if (_state.value.activePlayingAudioText == textToSpeak) {
            stopSpeaking()
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(activePlayingAudioText = textToSpeak, liveError = null) }
            val spoken = voiceRepository.speak(textToSpeak, langToSpeak)
            _state.update { current ->
                if (current.activePlayingAudioText == textToSpeak) {
                    current.copy(
                        activePlayingAudioText = null,
                        liveError = if (spoken is AppResult.Error) spoken.message else current.liveError
                    )
                } else current
            }
        }
    }

    fun stopSpeaking() {
        viewModelScope.launch {
            voiceRepository.stopSpeaking()
            _state.update { it.copy(activePlayingAudioText = null) }
        }
    }

    fun clearConversationSession() {
        stopSpeaking()
        _state.update {
            it.copy(
                conversationTurns = emptyList(),
                liveTranscript = "",
                liveTranslation = null,
                activePlayingAudioText = null
            )
        }
    }

    private fun stopLiveTranslationInternal(clearError: Boolean) {
        liveGeneration.incrementAndGet()
        liveJob?.cancel()
        liveJob = null
        stopSpeaking()
        _state.update {
            it.copy(
                liveEnabled = false,
                livePhase = LiveTranslationPhase.Idle,
                liveChecking = false,
                activeSpeaker = null,
                activePlayingAudioText = null,
                liveError = if (clearError) null else it.liveError
            )
        }
    }

    fun startLiveTranslation(speaker: LiveSpeaker = LiveSpeaker.SPEAKER_A) {
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
                val previousJob = liveJob
                liveJob?.cancel()
                val inputLang = if (speaker == LiveSpeaker.SPEAKER_B) current.targetLanguage else current.sourceLanguage
                val outputLang = if (speaker == LiveSpeaker.SPEAKER_B) current.sourceLanguage else current.targetLanguage
                liveJob = viewModelScope.launch {
                    runCatching { previousJob?.cancelAndJoin() }
                    voiceRepository.stopSpeaking()
                    updateLive(session, allowIdle = true) {
                        it.copy(
                            liveChecking = true,
                            liveEnabled = false,
                            activeSpeaker = speaker,
                            livePhase = LiveTranslationPhase.Starting,
                            liveError = null,
                            liveTranscript = "",
                            liveTranslation = null,
                            activePlayingAudioText = null
                        )
                    }

                    val capabilitiesResult = try {
                        voiceRepository.capabilities(outputLang)
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
                            activeSpeaker = speaker,
                            livePhase = LiveTranslationPhase.Listening,
                            voiceCapabilities = capabilities,
                            liveError = null
                        )
                    }

                    try {
                        voiceRepository.transcribe(inputLang).collect { transcript ->
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
                                    speaker = speaker,
                                    text = transcript.text,
                                    sourceLanguage = inputLang,
                                    targetLanguage = outputLang,
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
        speaker: LiveSpeaker,
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        capabilities: VoiceCapabilities
    ) {
        if (!isLiveSessionCurrent(session)) return
        val cleanText = cleanWhisperOutput(text)
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

        val request = TranslationRequest(cleanText, sourceLanguage, targetLanguage)
        val result = try {
            repository.translate(request)
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
                val newTurn = LiveConversationTurn(
                    speaker = speaker,
                    sourceText = cleanText,
                    translatedText = result.data.text,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage
                )
                updateLive(session) {
                    it.copy(
                        liveTranslation = result.data,
                        conversationTurns = it.conversationTurns + newTurn,
                        livePhase = if (shouldSpeak) {
                            LiveTranslationPhase.Speaking
                        } else {
                            LiveTranslationPhase.Listening
                        },
                        liveError = null
                    )
                }
                recordHistory(request, result.data)
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
                if (_state.value.autoTurnTaking && isLiveSessionCurrent(session) && _state.value.liveEnabled) {
                    val nextSpeaker = if (speaker == LiveSpeaker.SPEAKER_A) LiveSpeaker.SPEAKER_B else LiveSpeaker.SPEAKER_A
                    delay(350L)
                    if (_state.value.autoTurnTaking && isLiveSessionCurrent(session) && _state.value.liveEnabled) {
                        startLiveTranslation(nextSpeaker)
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
                is AppResult.Success -> {
                    val isCurrent = _state.value.let {
                        it.sourceText == request.text &&
                            it.sourceLanguage == request.sourceLanguage &&
                            it.targetLanguage == request.targetLanguage
                    }
                    if (isCurrent) {
                        _state.update { it.copy(result = result.data, loading = false, error = null) }
                        recordHistory(request, result.data)
                    }
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

    private suspend fun recordHistory(request: TranslationRequest, result: TranslationResult) {
        if (request.text.isBlank() || result.text.isBlank()) return
        when (
            val saved = historyRepository.add(
                TranslationHistoryEntry(
                    sourceText = request.text,
                    translatedText = result.text,
                    sourceLanguage = request.sourceLanguage,
                    targetLanguage = request.targetLanguage,
                    provider = result.provider,
                    createdAtEpochMs = System.currentTimeMillis()
                )
            )
        ) {
            is AppResult.Success -> _state.update { it.copy(historyError = null) }
            is AppResult.Error -> _state.update { it.copy(historyError = saved.message) }
            AppResult.Loading -> Unit
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

    fun speak(text: String, languageTag: String = _state.value.targetLanguage) {
        if (text.isBlank()) return
        if (_state.value.activePlayingAudioText == text) {
            stopSpeaking()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(activePlayingAudioText = text, liveError = null) }
            val spoken = voiceRepository.speak(text, languageTag)
            _state.update { current ->
                if (current.activePlayingAudioText == text) {
                    current.copy(
                        activePlayingAudioText = null,
                        liveError = if (spoken is AppResult.Error) spoken.message else current.liveError
                    )
                } else current
            }
        }
    }

    private fun cleanWhisperOutput(raw: String): String {
        // 1. Remove bracket/asterisk annotations (Whisper special tokens like [BLANK_AUDIO], *music*).
        val withoutBrackets = raw.replace(Regex("\\[.*?\\]|\\(.*?\\)|\\*.*?\\*"), " ").trim()

        // 2. Discard if the remaining content is a single non-alphanumeric character.
        if (withoutBrackets.length <= 1 && !withoutBrackets.all(Char::isLetterOrDigit)) {
            return ""
        }

        val collapsed = withoutBrackets.replace(Regex("\\s+"), " ").trim()

        // 3. Bag-of-Hallucinations filter.
        // Whisper commonly hallucinates these phrases when fed near-silence or low-SNR audio.
        // Matched case-insensitively; if the ENTIRE output is one of these phrases, discard it.
        val lower = collapsed.lowercase()
        if (HALLUCINATION_PHRASES.any { lower.contains(it) }) {
            return ""
        }

        // 4. Repetition-loop detection.
        // Split into tokens, then check whether any 1- or 2-token sequence repeats ≥4 times
        // consecutively. This catches the "the the the the" or "Thank you. Thank you." loops.
        val tokens = collapsed.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size >= 4 && hasRepetitionLoop(tokens, windowSize = 1, minRepeat = 4)) return ""
        if (tokens.size >= 6 && hasRepetitionLoop(tokens, windowSize = 2, minRepeat = 3)) return ""

        return collapsed
    }

    /**
     * Returns true if any n-gram of [windowSize] tokens repeats [minRepeat] or more times
     * consecutively anywhere in [tokens].
     */
    private fun hasRepetitionLoop(tokens: List<String>, windowSize: Int, minRepeat: Int): Boolean {
        var i = 0
        while (i <= tokens.size - windowSize * minRepeat) {
            val gram = tokens.subList(i, i + windowSize)
            var repeatCount = 1
            var j = i + windowSize
            while (j + windowSize <= tokens.size &&
                tokens.subList(j, j + windowSize) == gram) {
                repeatCount++
                j += windowSize
            }
            if (repeatCount >= minRepeat) return true
            i++
        }
        return false
    }

    companion object {
        /**
         * Common Whisper hallucination phrases. These are produced when the model receives
         * silence, very low-amplitude audio, or audio it cannot parse.
         * All entries are lowercase; matched against the lowercased transcript.
         */
        private val HALLUCINATION_PHRASES = setOf(
            // English
            "thank you for watching",
            "thanks for watching",
            "please subscribe",
            "like and subscribe",
            "see you next time",
            "don't forget to subscribe",
            "music",
            "applause",
            "laughter",
            // Indonesian
            "terima kasih telah menonton",
            "terima kasih sudah menonton",
            "sampai jumpa",
            "selamat tinggal",
            "jangan lupa subscribe",
        )
    }
}
