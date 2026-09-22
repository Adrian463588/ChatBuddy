package com.chatbuddy.data.repository

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.LanguageOption
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.OcrTextBlock
import com.chatbuddy.domain.model.TranslationModelStatus
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.model.TranslationResult
import com.chatbuddy.domain.repository.OcrRepository
import com.chatbuddy.domain.repository.TranslationRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageTranslationRepositoryImplTest {

    @Test
    fun translateBlocksUsesCacheForSubsequentCalls() = runTest {
        val ocrRepo = FakeOcrRepository()
        val translationRepo = CountingTranslationRepository()
        val repository = ImageTranslationRepositoryImpl(ocrRepo, translationRepo)

        val block1 = OcrTextBlock("Hello", 0f, 0f, 10f, 10f)
        val ocrResult1 = OcrResult("Hello", listOf(block1), "en", 100, 100)

        // First call: should hit translationRepo
        val res1 = repository.translateBlocks(ocrResult1, "en", "id")
        assertEquals(1, translationRepo.callCount)
        val data1 = (res1 as AppResult.Success).data
        assertEquals("Halo", data1[0].translatedText)

        // Second call with same text: should resolve from cache (callCount remains 1)
        val block2 = OcrTextBlock("Hello", 5f, 5f, 15f, 15f)
        val ocrResult2 = OcrResult("Hello", listOf(block2), "en", 100, 100)
        val res2 = repository.translateBlocks(ocrResult2, "en", "id")
        assertEquals(1, translationRepo.callCount)
        val data2 = (res2 as AppResult.Success).data
        assertEquals("Halo", data2[0].translatedText)
    }

    @Test
    fun translateBlocksFiltersOutFailedTranslationsWithoutPollutingResult() = runTest {
        val ocrRepo = FakeOcrRepository()
        val translationRepo = PartialFailingTranslationRepository()
        val repository = ImageTranslationRepositoryImpl(ocrRepo, translationRepo)

        val blockGood = OcrTextBlock("Good", 0f, 0f, 10f, 10f)
        val blockBad = OcrTextBlock("Bad", 10f, 10f, 20f, 20f)
        val ocrResult = OcrResult("Good Bad", listOf(blockGood, blockBad), "en", 100, 100)

        val res = repository.translateBlocks(ocrResult, "en", "id")
        val data = (res as AppResult.Success).data
        assertEquals(1, data.size)
        assertEquals("Bagus", data[0].translatedText)
    }

    @Test
    fun translateTextTranslatesAndCachesFullText() = runTest {
        val ocrRepo = FakeOcrRepository()
        val translationRepo = CountingTranslationRepository()
        val repository = ImageTranslationRepositoryImpl(ocrRepo, translationRepo)

        val res1 = repository.translateText("Hello", "en", "id")
        assertEquals(1, translationRepo.callCount)
        assertEquals("Halo", (res1 as AppResult.Success).data)

        val res2 = repository.translateText("Hello", "en", "id")
        assertEquals(1, translationRepo.callCount)
        assertEquals("Halo", (res2 as AppResult.Success).data)
    }


    private class FakeOcrRepository : OcrRepository {
        override suspend fun recognizeImage(uri: String, languageTag: String): AppResult<OcrResult> =
            AppResult.Success(OcrResult("", emptyList(), languageTag))
    }

    private class CountingTranslationRepository : TranslationRepository {
        var callCount = 0

        override suspend fun availableLanguages(): AppResult<List<LanguageOption>> = AppResult.Success(emptyList())
        override suspend fun modelStatus(sourceLanguage: String, targetLanguage: String): AppResult<TranslationModelStatus> =
            AppResult.Success(TranslationModelStatus(sourceLanguage, targetLanguage, ready = true))
        override suspend fun downloadModels(sourceLanguage: String, targetLanguage: String): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun translate(request: TranslationRequest): AppResult<TranslationResult> {
            callCount++
            val tr = if (request.text == "Hello") "Halo" else "Trans: ${request.text}"
            return AppResult.Success(TranslationResult(tr, TranslationProviderKind.ML_KIT_PLAY_SERVICES, request.sourceLanguage, request.targetLanguage))
        }
    }

    private class PartialFailingTranslationRepository : TranslationRepository {
        override suspend fun availableLanguages(): AppResult<List<LanguageOption>> = AppResult.Success(emptyList())
        override suspend fun modelStatus(sourceLanguage: String, targetLanguage: String): AppResult<TranslationModelStatus> =
            AppResult.Success(TranslationModelStatus(sourceLanguage, targetLanguage, ready = true))
        override suspend fun downloadModels(sourceLanguage: String, targetLanguage: String): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun translate(request: TranslationRequest): AppResult<TranslationResult> {
            return if (request.text == "Good") {
                AppResult.Success(TranslationResult("Bagus", TranslationProviderKind.ML_KIT_PLAY_SERVICES, request.sourceLanguage, request.targetLanguage))
            } else {
                AppResult.Error("Simulated failure")
            }
        }
    }
}
