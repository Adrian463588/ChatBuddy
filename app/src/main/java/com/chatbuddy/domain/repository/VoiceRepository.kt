package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.VoiceCapabilities
import com.chatbuddy.domain.model.VoiceTranscript
import kotlinx.coroutines.flow.Flow

interface VoiceRepository {
    suspend fun capabilities(languageTag: String): AppResult<VoiceCapabilities>
    fun transcribe(): Flow<VoiceTranscript>
    suspend fun speak(text: String, languageTag: String): AppResult<Unit>
}
