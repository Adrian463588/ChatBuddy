package com.chatbuddy.data.local.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TranslationHistoryDao {
    @Query(
        "SELECT * FROM translation_history " +
            "ORDER BY createdAtEpochMs DESC, id DESC LIMIT :limit"
    )
    fun observeRecent(limit: Int): Flow<List<TranslationHistoryEntity>>

    @Insert
    suspend fun insert(entry: TranslationHistoryEntity): Long

    @Query("DELETE FROM translation_history")
    suspend fun deleteAll()
}
