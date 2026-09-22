package com.chatbuddy.presentation.ocr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.TranslatedBlock
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.domain.repository.ImageTranslationRepository
import com.chatbuddy.domain.repository.OcrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OcrUiState(
    val result: OcrResult? = null,
    val imageUri: String? = null,
    val processing: Boolean = false,
    val error: String? = null,
    val translatedBlocks: List<TranslatedBlock> = emptyList(),
    val fullTranslation: String? = null,
    val translationProcessing: Boolean = false,
    val translationError: String? = null,
    val isLiveTranslateEnabled: Boolean = true,
    val isFrozen: Boolean = false
)

@HiltViewModel
class OcrViewModel @Inject constructor(
    private val repository: OcrRepository,
    private val imageTranslationRepository: ImageTranslationRepository
) : ViewModel() {
    private val _state = MutableStateFlow(OcrUiState())
    val state: StateFlow<OcrUiState> = _state.asStateFlow()
    private val operationGeneration = AtomicLong(0L)
    private val translationGeneration = AtomicLong(0L)
    private var recognitionJob: Job? = null
    private var translationJob: Job? = null
    private var pendingResult: OcrResult? = null
    private var sourceLanguage = "en"
    private var targetLanguage = "id"
    private var lastTranslationKey: String? = null

    fun onCameraResult(result: OcrResult) {
        if (_state.value.isFrozen) return
        operationGeneration.incrementAndGet()
        recognitionJob?.cancel()
        publishResult(result, imageUri = null)
    }

    fun onCameraError(message: String) {
        _state.update { it.copy(processing = false, error = message) }
    }

    fun freezeFrame() {
        _state.update { it.copy(isFrozen = true) }
    }

    fun unfreezeCamera() {
        _state.update { it.copy(isFrozen = false, imageUri = null) }
        lastTranslationKey = null
        pendingResult = null
    }

    fun clearCapturedImage() {
        unfreezeCamera()
    }

    fun setLiveTranslateEnabled(enabled: Boolean) {
        if (_state.value.isLiveTranslateEnabled == enabled) return
        _state.update { it.copy(isLiveTranslateEnabled = enabled) }
        if (enabled) {
            retryTranslation()
        }
    }

    fun toggleLiveTranslate() {
        setLiveTranslateEnabled(!_state.value.isLiveTranslateEnabled)
    }

    fun recognize(uri: String, languageTag: String = "en") {
        val operation = operationGeneration.incrementAndGet()
        recognitionJob?.cancel()
        recognitionJob = viewModelScope.launch {
            _state.update {
                it.copy(result = null, imageUri = uri, processing = true, error = null)
            }
            if (uri.isBlank()) {
                _state.update {
                    if (operation == operationGeneration.get()) {
                        it.copy(processing = false, error = "An image URI is required for OCR.")
                    } else it
                }
                return@launch
            }
            try {
                when (val result = repository.recognizeImage(uri, languageTag.ifBlank { "en" })) {
                    is AppResult.Success -> {
                        if (operation == operationGeneration.get()) {
                            publishResult(result.data, uri)
                        }
                    }
                    is AppResult.Error -> _state.update {
                        if (operation == operationGeneration.get()) {
                            it.copy(processing = false, error = result.message)
                        } else it
                    }
                    AppResult.Loading -> _state.update {
                        if (operation == operationGeneration.get()) it.copy(processing = true) else it
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update {
                    if (operation == operationGeneration.get()) {
                        it.copy(processing = false, error = error.message ?: "On-device OCR failed.")
                    } else it
                }
            }
        }
    }

    fun setTranslationLanguages(source: String, target: String) {
        val normalizedSource = source.trim().lowercase().ifBlank { "en" }
        val normalizedTarget = target.trim().lowercase().ifBlank { "id" }
        if (sourceLanguage == normalizedSource && targetLanguage == normalizedTarget) return
        sourceLanguage = normalizedSource
        targetLanguage = normalizedTarget
        lastTranslationKey = null
        _state.value.result?.let(::translateBlocks)
    }

    fun setCameraError(message: String) {
        _state.update { it.copy(processing = false, error = message) }
    }

    fun clearCameraError() {
        _state.update { it.copy(error = null) }
    }

    /** Re-runs the real OCR-to-translation pipeline after a provider becomes ready. */
    fun retryTranslation() {
        lastTranslationKey = null
        _state.value.result?.let(::translateBlocks)
    }

    override fun onCleared() {
        operationGeneration.incrementAndGet()
        recognitionJob?.cancel()
        translationJob?.cancel()
        super.onCleared()
    }

    private fun publishResult(result: OcrResult, imageUri: String?) {
        val existingTranslationMap = _state.value.translatedBlocks
            .filter { it.translatedText.isNotBlank() && it.translatedText.trim() != it.source.text.trim() }
            .associate { it.source.text.trim() to it.translatedText }

        val immediateBlocks = if (_state.value.isLiveTranslateEnabled && existingTranslationMap.isNotEmpty()) {
            result.blocks.mapNotNull { block ->
                val tr = existingTranslationMap[block.text.trim()] ?: return@mapNotNull null
                TranslatedBlock(
                    source = block,
                    translatedText = tr,
                    provider = TranslationProviderKind.ML_KIT_PLAY_SERVICES
                )
            }
        } else {
            emptyList()
        }

        _state.update {
            it.copy(
                result = result,
                imageUri = imageUri,
                processing = false,
                error = null,
                translatedBlocks = if (immediateBlocks.isNotEmpty()) immediateBlocks else it.translatedBlocks
            )
        }

        if (_state.value.isLiveTranslateEnabled || imageUri != null) {
            translateBlocks(result)
        }
    }

    private fun translateBlocks(result: OcrResult) {
        if (result.blocks.isEmpty() || sourceLanguage.equals(targetLanguage, ignoreCase = true)) {
            _state.update {
                it.copy(
                    translatedBlocks = emptyList(),
                    fullTranslation = if (sourceLanguage.equals(targetLanguage, ignoreCase = true)) result.text else null,
                    translationProcessing = false,
                    translationError = null
                )
            }
            return
        }

        val key = "$sourceLanguage|$targetLanguage|${result.text.trim()}"
        if (key == lastTranslationKey && _state.value.translatedBlocks.isNotEmpty() && _state.value.fullTranslation != null) {
            return
        }

        if (translationJob?.isActive == true) {
            pendingResult = result
            return
        }

        lastTranslationKey = key
        pendingResult = null

        translationGeneration.incrementAndGet()
        val generation = translationGeneration.get()

        translationJob = viewModelScope.launch {
            _state.update { it.copy(translationProcessing = true, translationError = null) }
            try {
                val blocksDeferred = async {
                    imageTranslationRepository.translateBlocks(
                        result,
                        sourceLanguage,
                        targetLanguage
                    )
                }
                val textDeferred = async {
                    imageTranslationRepository.translateText(
                        result.text,
                        sourceLanguage,
                        targetLanguage
                    )
                }

                val blocksRes = blocksDeferred.await()
                val textRes = textDeferred.await()

                if (generation == translationGeneration.get()) {
                    val blocks = if (blocksRes is AppResult.Success) blocksRes.data else emptyList()
                    val fullText = if (textRes is AppResult.Success) textRes.data else null
                    val error = if (blocksRes is AppResult.Error) {
                        blocksRes.message
                    } else if (textRes is AppResult.Error) {
                        textRes.message
                    } else {
                        null
                    }

                    _state.update {
                        it.copy(
                            translatedBlocks = blocks,
                            fullTranslation = fullText,
                            translationProcessing = false,
                            translationError = error
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                if (generation == translationGeneration.get()) {
                    _state.update {
                        it.copy(
                            translationProcessing = false,
                            translationError = error.message ?: "Image translation failed."
                        )
                    }
                }
            } finally {
                val next = pendingResult
                if (next != null && next.text.trim() != result.text.trim() && isActive) {
                    pendingResult = null
                    translateBlocks(next)
                }
            }
        }
    }

}
