package com.chatbuddy.domain.model

sealed interface VoiceTranscript {
    data class Partial(val text: String) : VoiceTranscript
    data class Final(val text: String) : VoiceTranscript
    data class Failed(val message: String) : VoiceTranscript
}

data class VoiceCapabilities(
    val whisperReady: Boolean,
    val offlineTtsReady: Boolean,
    val message: String,
    val headsetConnected: Boolean = false
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

enum class LiveSpeaker {
    SPEAKER_A,
    SPEAKER_B
}

data class LiveConversationTurn(
    val id: String = java.util.UUID.randomUUID().toString(),
    val speaker: LiveSpeaker,
    val sourceText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val timestamp: Long = System.currentTimeMillis()
)
