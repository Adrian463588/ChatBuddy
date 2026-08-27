package com.chatbuddy.data.repository

import com.chatbuddy.data.local.database.AppDatabase
import com.chatbuddy.data.local.database.TranslationHistoryEntity
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.TranslationHistoryEntry
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.domain.repository.TranslationHistoryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@Singleton
class TranslationHistoryRepositoryImpl @Inject constructor(
    database: AppDatabase
) : TranslationHistoryRepository {
    private val dao = database.translationHistoryDao()

    override fun observeRecent(limit: Int): Flow<List<TranslationHistoryEntry>> {
        val safeLimit = limit.coerceIn(1, MAX_HISTORY)
        return dao.observeRecent(safeLimit).map { entries -> entries.map { it.toDomain() } }
    }

    override suspend fun add(entry: TranslationHistoryEntry): AppResult<Unit> =
        withContext(Dispatchers.IO) {
            if (entry.sourceText.isBlank() || entry.translatedText.isBlank()) {
                return@withContext AppResult.Error("Empty translations are not saved")
            }
            if (entry.sourceText.length > MAX_TEXT_LENGTH || entry.translatedText.length > MAX_TEXT_LENGTH) {
                return@withContext AppResult.Error("Translation is too long to save")
            }
            try {
                dao.insert(entry.toEntity())
                AppResult.Success(Unit)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppResult.Error("Translation history could not be saved", error)
            }
        }

    override suspend fun clear(): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            dao.deleteAll()
            AppResult.Success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AppResult.Error("Translation history could not be cleared", error)
        }
    }

    private fun TranslationHistoryEntry.toEntity() = TranslationHistoryEntity(
        id = id,
        sourceText = sourceText,
        translatedText = translatedText,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        provider = provider.name,
        createdAtEpochMs = createdAtEpochMs
    )

    private fun TranslationHistoryEntity.toDomain() = TranslationHistoryEntry(
        id = id,
        sourceText = sourceText,
        translatedText = translatedText,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        provider = runCatching { TranslationProviderKind.valueOf(provider) }
            .getOrDefault(TranslationProviderKind.UNAVAILABLE),
        createdAtEpochMs = createdAtEpochMs
    )

    companion object {
        private const val MAX_HISTORY = 100
        private const val MAX_TEXT_LENGTH = 20_000
    }
}
