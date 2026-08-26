package com.chatbuddy.data.repository

import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.LanguageOption
import com.chatbuddy.domain.model.TranslationModelStatus
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.model.TranslationResult
import com.chatbuddy.domain.repository.TranslationRepository
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class MlKitTranslationRepository @Inject constructor() : TranslationRepository {
    /** ML Kit model operations are serialized so a status read cannot race a download. */
    private val modelMutex = Mutex()

    override suspend fun availableLanguages(): AppResult<List<LanguageOption>> =
        withContext(Dispatchers.Default) {
            try {
                AppResult.Success(
                    TranslateLanguage.getAllLanguages()
                        .asSequence()
                        .mapNotNull(::normalizeLanguageTag)
                        .distinct()
                        .map { tag ->
                            LanguageOption(
                                tag = tag,
                                displayName = Locale.forLanguageTag(tag)
                                    .getDisplayLanguage(Locale.getDefault())
                                    .ifBlank { tag }
                            )
                        }
                        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
                        .toList()
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AppResult.Error("Unable to list offline translation languages.", error)
            }
        }

    override suspend fun modelStatus(
        sourceLanguage: String,
        targetLanguage: String
    ): AppResult<TranslationModelStatus> = withContext(Dispatchers.IO) {
        val languages = validatedPair(sourceLanguage, targetLanguage)
            ?: return@withContext AppResult.Error("Select supported source and target languages first.")

        modelMutex.withLock {
            try {
                val manager = RemoteModelManager.getInstance()
                val sourceReady = manager
                    .isModelDownloaded(modelFor(languages.first))
                    .awaitCancellable()
                val targetReady = languages.first == languages.second || manager
                    .isModelDownloaded(modelFor(languages.second))
                    .awaitCancellable()
                AppResult.Success(
                    TranslationModelStatus(
                        sourceLanguage = languages.first,
                        targetLanguage = languages.second,
                        ready = sourceReady && targetReady
                    )
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AppResult.Error("Unable to check offline language packs.", error)
            }
        }
    }

    override suspend fun downloadModels(
        sourceLanguage: String,
        targetLanguage: String
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        val languages = validatedPair(sourceLanguage, targetLanguage)
            ?: return@withContext AppResult.Error("Select supported source and target languages first.")

        modelMutex.withLock {
            try {
                val manager = RemoteModelManager.getInstance()
                val conditions = DownloadConditions.Builder().requireWifi().build()
                val models = listOf(languages.first, languages.second)
                    .distinct()
                    .map(::modelFor)

                for (model in models) {
                    if (!manager.isModelDownloaded(model).awaitCancellable()) {
                        manager.download(model, conditions).awaitCancellable()
                    }
                }

                val ready = models.all { manager.isModelDownloaded(it).awaitCancellable() }
                if (!ready) {
                    return@withLock AppResult.Error(
                        "Offline language pack is not ready. Keep Wi-Fi connected and try again."
                    )
                }
                AppResult.Success(Unit)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                AppResult.Error(
                    "Language pack download failed. Keep Wi-Fi connected and try again.",
                    error
                )
            }
        }
    }

    override suspend fun translate(request: TranslationRequest): AppResult<TranslationResult> =
        withContext(Dispatchers.IO) {
            val languages = validatedPair(request.sourceLanguage, request.targetLanguage)
                ?: return@withContext AppResult.Error("Select supported source and target languages first.")

            if (request.text.isBlank()) {
                return@withContext AppResult.Success(
                    TranslationResult(
                        text = "",
                        provider = TranslationProviderKind.ML_KIT_PLAY_SERVICES,
                        sourceLanguage = languages.first,
                        targetLanguage = languages.second
                    )
                )
            }
            if (languages.first == languages.second) {
                return@withContext AppResult.Success(
                    TranslationResult(
                        text = request.text,
                        provider = TranslationProviderKind.ML_KIT_PLAY_SERVICES,
                        sourceLanguage = languages.first,
                        targetLanguage = languages.second
                    )
                )
            }

            modelMutex.withLock {
                try {
                    val manager = RemoteModelManager.getInstance()
                    val sourceModel = modelFor(languages.first)
                    val targetModel = modelFor(languages.second)
                    val sourceReady = manager.isModelDownloaded(sourceModel).awaitCancellable()
                    val targetReady = manager.isModelDownloaded(targetModel).awaitCancellable()
                    if (!sourceReady || !targetReady) {
                        return@withLock AppResult.Error(
                            "Offline language model is not downloaded. Use model setup before translating."
                        )
                    }

                    val translator = Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(languages.first)
                            .setTargetLanguage(languages.second)
                            .build()
                    )
                    translator.use {
                        val translated = it.translate(request.text).awaitCancellable()
                        AppResult.Success(
                            TranslationResult(
                                text = translated,
                                provider = TranslationProviderKind.ML_KIT_PLAY_SERVICES,
                                sourceLanguage = languages.first,
                                targetLanguage = languages.second
                            )
                        )
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    AppResult.Error("Offline ML Kit translation failed.", error)
                }
            }
        }

    private fun validatedPair(sourceLanguage: String, targetLanguage: String): Pair<String, String>? {
        val supported = TranslateLanguage.getAllLanguages()
            .asSequence()
            .mapNotNull(::normalizeLanguageTag)
            .toSet()
        val source = normalizeLanguageTag(sourceLanguage)
        val target = normalizeLanguageTag(targetLanguage)
        return if (source != null && target != null && source in supported && target in supported) {
            source to target
        } else {
            null
        }
    }

    private fun normalizeLanguageTag(languageTag: String): String? =
        languageTag.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank)

    private fun modelFor(languageTag: String): TranslateRemoteModel =
        TranslateRemoteModel.Builder(languageTag).build()

    /**
     * ML Kit tasks are callback based. The active-continuation guard prevents a completed
     * background task from resuming a cancelled ViewModel operation.
     */
    private suspend fun <T> Task<T>.awaitCancellable(): T =
        suspendCancellableCoroutine { continuation ->
            val listener = OnCompleteListener<T> { task ->
                if (!continuation.isActive) return@OnCompleteListener
                try {
                    if (task.isSuccessful) {
                        continuation.resume(task.result)
                    } else {
                        continuation.resumeWithException(
                            task.exception ?: IllegalStateException("ML Kit task failed without an exception")
                        )
                    }
                } catch (_: IllegalStateException) {
                    // Cancellation may race with the task callback; the operation is already terminal.
                }
            }
            addOnCompleteListener(listener)
        }
}
