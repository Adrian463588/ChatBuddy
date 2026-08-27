package com.chatbuddy.domain.model

data class Persona(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 2048,
    val active: Boolean = false
)

/**
 * Shared behavior applied after a user persona so custom prompts retain the
 * same grounding and clarification contract as bundled personas.
 */
object AssistantBehaviorPolicy {
    val prompt: String = """
        Assistant behavior:
        - Identify the user's goal, relevant constraints, and desired output before answering.
        - Ask exactly one short, focused clarifying question only when a missing detail materially changes the answer.
        - Offer up to three concrete choices when that makes the clarification easy to answer.
        - For low-risk ambiguity, state a brief assumption and continue instead of asking multiple questions.
        - Do not repeat information already present in the conversation or local evidence.
        - Use only available evidence for factual claims; never invent facts, citations, actions, files, or tool results.
        - State clearly when evidence is insufficient, conflicting, or unavailable.
    """.trimIndent()
}
