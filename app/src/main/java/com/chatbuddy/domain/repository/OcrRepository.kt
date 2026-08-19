package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.OcrResult

interface OcrRepository {
    suspend fun recognizeImage(uri: String, languageTag: String): AppResult<OcrResult>
}
