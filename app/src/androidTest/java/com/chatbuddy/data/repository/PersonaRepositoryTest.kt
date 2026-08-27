package com.chatbuddy.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.InstrumentationRegistry
import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.Persona
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PersonaRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: PersonaRepositoryImpl

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PersonaRepositoryImpl(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun missingActivationPreservesTheCurrentActivePersona() = runBlocking {
        repository.save(persona(id = "sunny", active = true))

        val result = repository.setActive("missing")

        assertTrue(result is AppResult.Error)
        val personas = database.personaDao().observeAll().first()
        assertTrue(personas.single { it.id == "sunny" }.active)
    }

    @Test
    fun activatingAnotherPersonaLeavesOnlyOneActive() = runBlocking {
        repository.save(persona(id = "sunny", active = true))
        repository.save(persona(id = "study", active = true))

        val personas = database.personaDao().observeAll().first()

        assertTrue(personas.single { it.id == "study" }.active)
        assertTrue(!personas.single { it.id == "sunny" }.active)
    }

    private fun persona(id: String, active: Boolean) = Persona(
        id = id,
        name = id,
        description = "Test persona",
        systemPrompt = "Answer from available evidence.",
        active = active
    )
}
