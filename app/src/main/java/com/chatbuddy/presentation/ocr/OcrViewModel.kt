package com.chatbuddy.presentation.ocr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.TranslatedBlock
import com.chatbuddy.domain.repository.ImageTranslationRepository
import com.chatbuddy.domain.repository.OcrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
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
    val translationProcessing: Boolean = false,
    val translationError: String? = null
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
    private var sourceLanguage = "en"
    private var targetLanguage = "id"
    private var lastTranslationKey: String? = null

    fun onCameraResult(result: OcrResult) {
        operationGeneration.incrementAndGet()
        recognitionJob?.cancel()
        publishResult(result, imageUri = null)
    }

    fun onCameraError(message: String) {
        _state.update { it.copy(processing = false, error = message) }
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
        _state.update {
            it.copy(
                result = result,
                imageUri = imageUri,
                processing = false,
                error = null,
                translatedBlocks = emptyList(),
                translationProcessing = false,
                translationError = null
            )
        }
        translateBlocks(result)
    }

    private fun translateBlocks(result: OcrResult) {
        val key = buildString {
            append(sourceLanguage)
            append('|')
            append(targetLanguage)
            append('|')
            append(result.text)
        }
        if (key == lastTranslationKey) return
        lastTranslationKey = key
        translationGeneration.incrementAndGet()
        val generation = translationGeneration.get()
        translationJob?.cancel()
        if (result.blocks.isEmpty() || sourceLanguage == targetLanguage) {
            _state.update {
                if (it.result === result || it.result?.text == result.text) {
                    it.copy(
                        translatedBlocks = result.blocks.map { block ->
                            TranslatedBlock(
                                source = block,
                                translatedText = block.text,
                                provider = com.chatbuddy.domain.model.TranslationProviderKind.ML_KIT_PLAY_SERVICES
                            )
                        },
                        translationProcessing = false,
                        translationError = null
                    )
                } else it
            }
            return
        }
        translationJob = viewModelScope.launch {
            _state.update { it.copy(translationProcessing = true, translationError = null) }
            try {
                when (
                    val result = imageTranslationRepository.translateBlocks(
                        result,
                        sourceLanguage,
                        targetLanguage
                    )
                ) {
                    is AppResult.Success -> _state.update {
                        if (generation == translationGeneration.get()) {
                            it.copy(
                                translatedBlocks = result.data,
                                translationProcessing = false,
                                translationError = null
                            )
                        } else it
                    }
                    is AppResult.Error -> _state.update {
                        if (generation == translationGeneration.get()) {
                            it.copy(
                                translatedBlocks = emptyList(),
                                translationProcessing = false,
                                translationError = result.message
                            )
                        } else it
                    }
                    AppResult.Loading -> _state.update {
                        if (generation == translationGeneration.get()) it.copy(translationProcessing = true) else it
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                _state.update {
                    if (generation == translationGeneration.get()) {
                        it.copy(
                            translatedBlocks = emptyList(),
                            translationProcessing = false,
                            translationError = error.message ?: "Image translation failed."
                        )
                    } else it
                }
            }
        }
    }
}
