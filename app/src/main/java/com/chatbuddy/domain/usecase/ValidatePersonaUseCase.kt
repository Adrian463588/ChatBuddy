package com.chatbuddy.domain.usecase

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.Persona
import javax.inject.Inject

class ValidatePersonaUseCase @Inject constructor() {
    operator fun invoke(persona: Persona): AppResult<Persona> {
        val name = persona.name.trim()
        val prompt = persona.systemPrompt.trim()
        return when {
            name.isEmpty() -> AppResult.Error("Persona name is required")
            name.length > 80 -> AppResult.Error("Persona name must be 80 characters or fewer")
            prompt.isEmpty() -> AppResult.Error("System prompt is required")
            prompt.length > 20_000 -> AppResult.Error("System prompt is too long")
            persona.temperature !in 0.1f..1.0f -> AppResult.Error("Temperature must be between 0.1 and 1.0")
            persona.topP !in 0.5f..1.0f -> AppResult.Error("Top-P must be between 0.5 and 1.0")
            persona.maxTokens !in 256..4096 -> AppResult.Error("Max tokens must be between 256 and 4096")
            else -> AppResult.Success(persona.copy(name = name, systemPrompt = prompt))
        }
    }
}
