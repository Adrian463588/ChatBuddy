package com.chatbuddy.data.repository

import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.data.local.database.PersonaEntity
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.Persona
import com.chatbuddy.domain.repository.PersonaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonaRepositoryImpl @Inject constructor(
    database: AppDatabase
) : PersonaRepository {
    private val dao = database.personaDao()

    override fun observePersonas(): Flow<List<Persona>> = dao.observeAll().map { list -> list.map { entity -> entity.toDomain() } }

    override suspend fun save(persona: Persona): AppResult<Unit> = withContext(Dispatchers.IO) {
        runCatching { dao.insert(persona.toEntity()) }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Error("Unable to save persona", it) }
        )
    }

    override suspend fun delete(id: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        runCatching { dao.delete(id) }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Error("Unable to delete persona", it) }
        )
    }

    override suspend fun setActive(id: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        runCatching { dao.setActive(id) }.fold(
            onSuccess = { AppResult.Success(Unit) },
            onFailure = { AppResult.Error("Unable to activate persona", it) }
        )
    }

    private fun Persona.toEntity() = PersonaEntity(id, name, description, systemPrompt, temperature, topP, maxTokens, active)
    private fun PersonaEntity.toDomain() = Persona(id, name, description, systemPrompt, temperature, topP, maxTokens, active)
}
