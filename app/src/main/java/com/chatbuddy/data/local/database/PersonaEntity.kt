package com.chatbuddy.data.local.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val temperature: Float,
    val topP: Float,
    val maxTokens: Int,
    val active: Boolean
)
