package com.chatbuddy.domain.model

data class Persona(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val maxTokens: Int = 1024,
    val active: Boolean = false
)
