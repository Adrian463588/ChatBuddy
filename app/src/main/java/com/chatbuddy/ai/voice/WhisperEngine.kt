package com.chatbuddy.ai.voice

import com.chatbuddy.domain.model.VoiceTranscript
import kotlinx.coroutines.flow.Flow

interface WhisperEngine {
    val isReady: Boolean
    fun transcribe(samples: ShortArray): Flow<VoiceTranscript>
}
