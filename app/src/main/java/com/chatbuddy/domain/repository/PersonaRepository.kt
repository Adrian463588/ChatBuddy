package com.chatbuddy.domain.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.Persona
import kotlinx.coroutines.flow.Flow

interface PersonaRepository {
    fun observePersonas(): Flow<List<Persona>>
    suspend fun save(persona: Persona): AppResult<Unit>
    suspend fun delete(id: String): AppResult<Unit>
    suspend fun setActive(id: String): AppResult<Unit>
}
