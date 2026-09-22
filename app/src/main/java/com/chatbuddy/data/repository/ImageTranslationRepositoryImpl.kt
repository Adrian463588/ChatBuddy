package com.chatbuddy.data.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ImageTranslationResult
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.TranslatedBlock
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.repository.ImageTranslationRepository
import com.chatbuddy.domain.repository.OcrRepository
import com.chatbuddy.domain.repository.TranslationRepository
import java.util.Collections
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Singleton
class ImageTranslationRepositoryImpl @Inject constructor(
    private val ocrRepository: OcrRepository,
    private val translationRepository: TranslationRepository
) : ImageTranslationRepository {
    private val translationCache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > 500
        }
    )
    override suspend fun translateImage(
        uri: String,
        sourceLanguage: String,
        targetLanguage: String
    ): AppResult<ImageTranslationResult> {
        return when (val ocr = ocrRepository.recognizeImage(uri, sourceLanguage)) {
            is AppResult.Success -> when (
                val blocks = translateBlocks(ocr.data, sourceLanguage, targetLanguage)
            ) {
                is AppResult.Success -> AppResult.Success(
                    ImageTranslationResult(uri, ocr.data, blocks.data)
                )
                is AppResult.Error -> blocks
                AppResult.Loading -> AppResult.Loading
            }
            is AppResult.Error -> ocr
            AppResult.Loading -> AppResult.Loading
        }
    }

    override suspend fun translateBlocks(
        ocr: OcrResult,
        sourceLanguage: String,
        targetLanguage: String
    ): AppResult<List<TranslatedBlock>> {
        if (sourceLanguage.isBlank() || targetLanguage.isBlank()) {
            return AppResult.Error("Select source and target languages before translating the image")
        }
        if (ocr.blocks.size > MAX_BLOCKS) {
            return AppResult.Error(
                "This image contains too many text regions. Crop it and try again."
            )
        }
        if (sourceLanguage.equals(targetLanguage, ignoreCase = true)) {
            return AppResult.Success(
                ocr.blocks.map { block ->
                    TranslatedBlock(block, block.text, com.chatbuddy.domain.model.TranslationProviderKind.ML_KIT_PLAY_SERVICES)
                }
            )
        }
        val validBlocks = ocr.blocks.filter { it.text.isNotBlank() }
        if (validBlocks.isEmpty()) {
            return AppResult.Success(emptyList())
        }

        return try {
            val results = coroutineScope {
                validBlocks.map { block ->
                    async {
                        val trimmedText = block.text.trim()
                        val cacheKey = "$sourceLanguage->$targetLanguage:$trimmedText"
                        val cached = translationCache[cacheKey]
                        if (!cached.isNullOrBlank()) {
                            return@async TranslatedBlock(
                                source = block,
                                translatedText = cached,
                                provider = TranslationProviderKind.ML_KIT_PLAY_SERVICES
                            )
                        }

                        val result = translationRepository.translate(
                            TranslationRequest(trimmedText, sourceLanguage, targetLanguage)
                        )
                        val text = if (result is AppResult.Success && result.data.text.isNotBlank()) {
                            val clean = result.data.text.trim()
                            if (clean != trimmedText) {
                                translationCache[cacheKey] = clean
                            }
                            clean
                        } else {
                            null
                        }
                        if (text != null && text.isNotBlank()) {
                            val provider = (result as AppResult.Success).data.provider
                            TranslatedBlock(source = block, translatedText = text, provider = provider)
                        } else {
                            null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            AppResult.Success(results)
        } catch (error: Exception) {
            AppResult.Error("Image translation failed: ${error.message}", error)
        }
    }

    override suspend fun translateText(
        text: String,
        sourceLanguage: String,
        targetLanguage: String
    ): AppResult<String> {
        val trimmed = text.trim()
        if (trimmed.isBlank() || sourceLanguage.equals(targetLanguage, ignoreCase = true)) {
            return AppResult.Success(trimmed)
        }
        val cacheKey = "$sourceLanguage->$targetLanguage:$trimmed"
        val cached = translationCache[cacheKey]
        if (!cached.isNullOrBlank()) {
            return AppResult.Success(cached)
        }
        return when (
            val res = translationRepository.translate(
                TranslationRequest(trimmed, sourceLanguage, targetLanguage)
            )
        ) {
            is AppResult.Success -> {
                val translated = res.data.text.trim()
                if (translated.isNotBlank() && translated != trimmed) {
                    translationCache[cacheKey] = translated
                }
                AppResult.Success(translated)
            }
            is AppResult.Error -> res
            AppResult.Loading -> AppResult.Loading
        }
    }


    companion object {
        private const val MAX_BLOCKS = 64
    }
}
