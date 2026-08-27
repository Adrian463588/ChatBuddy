package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ImageTranslationResult
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.TranslatedBlock

interface ImageTranslationRepository {
    suspend fun translateImage(
        uri: String,
        sourceLanguage: String,
        targetLanguage: String
    ): AppResult<ImageTranslationResult>

    suspend fun translateBlocks(
        ocr: OcrResult,
        sourceLanguage: String,
        targetLanguage: String
    ): AppResult<List<TranslatedBlock>>
}
