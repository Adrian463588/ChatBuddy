package com.chatbuddy.ai.voice

import com.chatbuddy.domain.model.VoiceTranscript
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnavailableWhisperEngine @Inject constructor() : WhisperEngine {
    override val isReady: Boolean = false
    override fun transcribe(samples: ShortArray): Flow<VoiceTranscript> = flowOf(
        VoiceTranscript.Failed("Whisper JNI runtime is unavailable")
    )
}
