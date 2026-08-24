package com.chatbuddy.domain.model

sealed interface VoiceTranscript {
    data class Partial(val text: String) : VoiceTranscript
    data class Final(val text: String) : VoiceTranscript
    data class Failed(val message: String) : VoiceTranscript
}

data class VoiceCapabilities(
    val whisperReady: Boolean,
    val offlineTtsReady: Boolean,
    val message: String
)

enum class LiveTranslationPhase {
    Idle,
    Starting,
    Listening,
    Transcribing,
    Translating,
    Speaking,
    Error
}
