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

data class TranslationModelStatus(
    val sourceLanguage: String,
    val targetLanguage: String,
    val ready: Boolean
)

data class TranslationResult(
    val text: String,
    val provider: TranslationProviderKind,
    val sourceLanguage: String,
    val targetLanguage: String
)

data class TranslationHistoryEntry(
    val id: Long = 0L,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val provider: TranslationProviderKind,
    val createdAtEpochMs: Long
)

data class TranslatedBlock(
    val source: OcrTextBlock,
    val translatedText: String,
    val provider: TranslationProviderKind
)

data class ImageTranslationResult(
    val sourceUri: String,
    val sourceOcr: OcrResult,
    val blocks: List<TranslatedBlock>
)
