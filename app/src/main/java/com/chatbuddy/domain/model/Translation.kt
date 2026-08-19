package com.chatbuddy.domain.model

data class LanguageOption(val tag: String, val displayName: String)

enum class TranslationProviderKind {
    ML_KIT_PLAY_SERVICES,
    LOCAL_OPUS_ONNX,
    UNAVAILABLE
}

data class TranslationRequest(
    val text: String,
    val sourceLanguage: String,
    val targetLanguage: String
)

data class TranslationResult(
    val text: String,
    val provider: TranslationProviderKind,
    val sourceLanguage: String,
    val targetLanguage: String
)
