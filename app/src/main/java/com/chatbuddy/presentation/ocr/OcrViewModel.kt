package com.chatbuddy.presentation.ocr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.repository.OcrRepository
import com.chatbuddy.data.repository.CameraOcrAnalyzer
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
    val error: String? = null
)

@HiltViewModel
class OcrViewModel @Inject constructor(
    private val repository: OcrRepository,
    val cameraAnalyzer: CameraOcrAnalyzer
) : ViewModel() {
    private val _state = MutableStateFlow(OcrUiState())
    val state: StateFlow<OcrUiState> = _state.asStateFlow()
    private val operationGeneration = AtomicLong(0L)
    private var recognitionJob: Job? = null

    init {
        cameraAnalyzer.setCallbacks(
            onResult = { result ->
                operationGeneration.incrementAndGet()
                recognitionJob?.cancel()
                _state.update {
                    it.copy(result = result, imageUri = null, processing = false, error = null)
                }
            },
            onError = { message ->
                _state.update { it.copy(processing = false, error = message) }
            }
        )
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
                    is AppResult.Success -> _state.update {
                        if (operation == operationGeneration.get()) {
                            it.copy(result = result.data, processing = false, error = null)
                        } else it
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

    fun setCameraLanguage(languageTag: String) = cameraAnalyzer.setLanguageTag(languageTag)

    fun setCameraError(message: String) {
        _state.update { it.copy(processing = false, error = message) }
    }

    fun clearCameraError() {
        _state.update { it.copy(error = null) }
    }

    override fun onCleared() {
        operationGeneration.incrementAndGet()
        recognitionJob?.cancel()
        cameraAnalyzer.clearCallbacks()
        cameraAnalyzer.close()
        super.onCleared()
    }
}
