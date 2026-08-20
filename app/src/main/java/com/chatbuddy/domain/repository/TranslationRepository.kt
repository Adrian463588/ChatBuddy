package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.LanguageOption
import com.chatbuddy.domain.model.TranslationModelStatus
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.model.TranslationResult

interface TranslationRepository {
    suspend fun availableLanguages(): AppResult<List<LanguageOption>>
    suspend fun modelStatus(sourceLanguage: String, targetLanguage: String): AppResult<TranslationModelStatus>
    suspend fun downloadModels(sourceLanguage: String, targetLanguage: String): AppResult<Unit>
    suspend fun translate(request: TranslationRequest): AppResult<TranslationResult>
}
