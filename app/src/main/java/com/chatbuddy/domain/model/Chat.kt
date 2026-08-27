package com.chatbuddy.domain.model

data class ChatMessage(
    val id: String,
    val role: Role,
    val text: String,
    val citations: List<ChatCitation> = emptyList()
) {
    enum class Role { USER, ASSISTANT }
}

data class ChatRequest(
    val text: String,
    val persona: Persona,
    val useRag: Boolean,
    val allowWebFallback: Boolean = false,
    val history: List<ChatMessage> = emptyList()
)

enum class ChatCitationKind {
    LOCAL_DOCUMENT,
    WEB
}

data class ChatCitation(
    val kind: ChatCitationKind,
    val title: String,
    val uri: String?,
    val excerpt: String,
    val provider: String,
    val score: Float? = null,
    val sourceId: String = "",
    val retrievedAtEpochMs: Long = 0L
)

sealed interface ChatStreamEvent {
    data object Started : ChatStreamEvent
    data object WebSearchStarted : ChatStreamEvent
    data class Token(val value: String) : ChatStreamEvent
    data class SourcesFound(val sources: List<ChatCitation>) : ChatStreamEvent
    data class Completed(val message: ChatMessage) : ChatStreamEvent
    data class Failed(val message: String) : ChatStreamEvent
}
