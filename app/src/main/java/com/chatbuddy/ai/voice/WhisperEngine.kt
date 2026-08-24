package com.chatbuddy.ai.voice

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.VoiceTranscript
import kotlinx.coroutines.flow.Flow

interface WhisperEngine {
    val isReady: Boolean
    suspend fun ensureLoaded(): AppResult<Unit>
    fun transcribe(samples: ShortArray, languageTag: String, partial: Boolean): Flow<VoiceTranscript>
}
