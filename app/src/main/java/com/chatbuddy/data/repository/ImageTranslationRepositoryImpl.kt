package com.chatbuddy.data.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ImageTranslationResult
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.TranslatedBlock
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.repository.ImageTranslationRepository
import com.chatbuddy.domain.repository.OcrRepository
import com.chatbuddy.domain.repository.TranslationRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageTranslationRepositoryImpl @Inject constructor(
    private val ocrRepository: OcrRepository,
    private val translationRepository: TranslationRepository
) : ImageTranslationRepository {
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
        val translated = ArrayList<TranslatedBlock>(ocr.blocks.size)
        for (block in ocr.blocks) {
            if (block.text.isBlank()) continue
            when (
                val result = translationRepository.translate(
                    TranslationRequest(block.text, sourceLanguage, targetLanguage)
                )
            ) {
                is AppResult.Success -> {
                    if (result.data.text.isBlank()) {
                        return AppResult.Error("Translation provider returned an empty block")
                    }
                    translated += TranslatedBlock(block, result.data.text, result.data.provider)
                }
                is AppResult.Error -> return result
                AppResult.Loading -> return AppResult.Loading
            }
        }
        return AppResult.Success(translated)
    }

    companion object {
        private const val MAX_BLOCKS = 64
    }
}
