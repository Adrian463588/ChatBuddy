package com.chatbuddy.data.repository

import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.data.local.database.PersonaEntity
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.Persona
import com.chatbuddy.domain.repository.PersonaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonaRepositoryImpl @Inject constructor(
    database: AppDatabase
) : PersonaRepository {
    private val dao = database.personaDao()

    override fun observePersonas(): Flow<List<Persona>> = dao.observeAll().map { list ->
        list.map { entity -> entity.toDomain() }
    }

    override suspend fun save(persona: Persona): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (persona.active) {
                dao.insertAndActivate(persona.toEntity())
            } else {
                dao.insert(persona.toEntity())
            }
            AppResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppResult.Error("Unable to save persona", error)
        }
    }

    override suspend fun delete(id: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (dao.delete(id) == 0) {
                AppResult.Error("Persona was not found; nothing was deleted")
            } else {
                AppResult.Success(Unit)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppResult.Error("Unable to delete persona", error)
        }
    }

    override suspend fun setActive(id: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            if (dao.setActive(id)) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error("Persona was not found; the active persona was unchanged")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppResult.Error("Unable to activate persona", error)
        }
    }

    private fun Persona.toEntity() = PersonaEntity(id, name, description, systemPrompt, temperature, topP, maxTokens, active)
    private fun PersonaEntity.toDomain() = Persona(id, name, description, systemPrompt, temperature, topP, maxTokens, active)
}
