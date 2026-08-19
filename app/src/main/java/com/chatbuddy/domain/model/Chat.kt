package com.chatbuddy.domain.model

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val citations: List<Evidence> = emptyList()
) {
    enum class Role { USER, ASSISTANT }
}

data class ChatRequest(
    val text: String,
    val persona: Persona,
    val useRag: Boolean
)

sealed interface ChatStreamEvent {
    data object Started : ChatStreamEvent
    data class Token(val value: String) : ChatStreamEvent
    data class EvidenceFound(val evidence: List<Evidence>) : ChatStreamEvent
    data class Completed(val message: ChatMessage) : ChatStreamEvent
    data class Failed(val message: String) : ChatStreamEvent
}
