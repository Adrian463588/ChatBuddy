package com.chatbuddy.data.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.LanguageOption
import com.chatbuddy.domain.model.TranslationModelStatus
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.model.TranslationResult
import com.chatbuddy.domain.repository.TranslationRepository
import com.chatbuddy.utils.awaitTask
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@Singleton
class MlKitTranslationRepository @Inject constructor() : TranslationRepository {
    override suspend fun availableLanguages(): AppResult<List<LanguageOption>> = withContext(Dispatchers.Default) {
        AppResult.Success(
            TranslateLanguage.getAllLanguages().map { tag ->
                LanguageOption(tag, Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()))
            }.sortedBy { it.displayName }
        )
    }

    override suspend fun modelStatus(
        sourceLanguage: String,
        targetLanguage: String
    ): AppResult<TranslationModelStatus> = withContext(Dispatchers.IO) {
        if (sourceLanguage.isBlank() || targetLanguage.isBlank()) {
            return@withContext AppResult.Error("Select source and target languages first.")
        }
        try {
            val manager = RemoteModelManager.getInstance()
            val sourceReady = manager.isModelDownloaded(modelFor(sourceLanguage)).awaitTask()
            val targetReady = sourceLanguage == targetLanguage ||
                manager.isModelDownloaded(modelFor(targetLanguage)).awaitTask()
            AppResult.Success(
                TranslationModelStatus(
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    ready = sourceReady && targetReady
                )
            )
        } catch (error: Exception) {
            AppResult.Error("Unable to check offline language packs.", error)
        }
    }

    override suspend fun downloadModels(
        sourceLanguage: String,
        targetLanguage: String
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        if (sourceLanguage.isBlank() || targetLanguage.isBlank()) {
            return@withContext AppResult.Error("Select source and target languages first.")
        }
        try {
            val manager = RemoteModelManager.getInstance()
            val conditions = DownloadConditions.Builder().requireWifi().build()
            manager.download(modelFor(sourceLanguage), conditions).awaitTask()
            if (sourceLanguage != targetLanguage) {
                manager.download(modelFor(targetLanguage), conditions).awaitTask()
            }
            AppResult.Success(Unit)
        } catch (error: Exception) {
            AppResult.Error(
                "Language pack download failed. Connect to Wi-Fi and try again.",
                error
            )
        }
    }

    override suspend fun translate(request: TranslationRequest): AppResult<TranslationResult> =
        withContext(Dispatchers.IO) {
            if (request.text.isBlank()) return@withContext AppResult.Success(
                TranslationResult("", TranslationProviderKind.ML_KIT_PLAY_SERVICES, request.sourceLanguage, request.targetLanguage)
            )
            if (request.sourceLanguage == request.targetLanguage) return@withContext AppResult.Success(
                TranslationResult(request.text, TranslationProviderKind.ML_KIT_PLAY_SERVICES, request.sourceLanguage, request.targetLanguage)
            )
            try {
                val manager = RemoteModelManager.getInstance()
                val sourceModel = TranslateRemoteModel.Builder(request.sourceLanguage).build()
                val targetModel = TranslateRemoteModel.Builder(request.targetLanguage).build()
                val downloaded = manager.getDownloadedModels(TranslateRemoteModel::class.java).awaitTask()
                if (sourceModel !in downloaded || targetModel !in downloaded) {
                    return@withContext AppResult.Error(
                        "Offline language model is not downloaded. Use model setup before translating."
                    )
                }
                val translator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(request.sourceLanguage)
                        .setTargetLanguage(request.targetLanguage)
                        .build()
                )
                translator.use {
                    val translated = it.translate(request.text).awaitTask()
                    AppResult.Success(
                        TranslationResult(
                            text = translated,
                            provider = TranslationProviderKind.ML_KIT_PLAY_SERVICES,
                            sourceLanguage = request.sourceLanguage,
                            targetLanguage = request.targetLanguage
                        )
                    )
                }
            } catch (error: Exception) {
                AppResult.Error("Offline ML Kit translation failed", error)
            }
        }

    private fun modelFor(languageTag: String): TranslateRemoteModel =
        TranslateRemoteModel.Builder(languageTag).build()
}
