package com.chatbuddy.presentation.ocr

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.repository.OcrRepository
import com.chatbuddy.data.repository.CameraOcrAnalyzer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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

    init {
        cameraAnalyzer.setCallbacks(
            onResult = { result ->
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
        viewModelScope.launch {
            _state.update {
                it.copy(result = null, imageUri = uri, processing = true, error = null)
            }
            when (val result = repository.recognizeImage(uri, languageTag)) {
                is AppResult.Success -> _state.update { it.copy(result = result.data, processing = false) }
                is AppResult.Error -> _state.update { it.copy(processing = false, error = result.message) }
                AppResult.Loading -> Unit
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
        cameraAnalyzer.clearCallbacks()
        cameraAnalyzer.close()
        super.onCleared()
    }
}
