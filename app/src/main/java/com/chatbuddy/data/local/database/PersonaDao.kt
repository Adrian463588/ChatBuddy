package com.chatbuddy.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY active DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<PersonaEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(persona: PersonaEntity)

    @Transaction
    suspend fun insertAndActivate(persona: PersonaEntity) {
        clearActive()
        insert(persona.copy(active = true))
    }

    @Query("DELETE FROM personas WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("SELECT id FROM personas WHERE id = :id LIMIT 1")
    suspend fun findId(id: String): String?

    @Query("UPDATE personas SET active = 0")
    suspend fun clearActive()

    @Query("UPDATE personas SET active = 1 WHERE id = :id")
    suspend fun markActive(id: String): Int

    @Transaction
    suspend fun setActive(id: String): Boolean {
        if (findId(id) == null) return false
        clearActive()
        return markActive(id) == 1
    }
}
