package com.chatbuddy.presentation.translate

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.LanguageOption
import com.chatbuddy.domain.model.TranslationModelStatus
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.model.TranslationResult
import com.chatbuddy.domain.repository.TranslationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun translationWaitsForTheOfflineModelAndUsesDebounce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = RecordingTranslationRepository(modelReady = true)
        val viewModel = TranslationViewModel(repository)
        advanceUntilIdle()

        viewModel.setSourceText("hello")
        advanceTimeBy(299)
        runCurrent()
        assertEquals(0, repository.translateCalls)

        advanceTimeBy(1)
        advanceUntilIdle()
        assertEquals(1, repository.translateCalls)
        assertEquals("translated: hello", viewModel.state.value.result?.text)
    }

    @Test
    fun downloadActionMakesTheManagedModelAvailable() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = RecordingTranslationRepository(modelReady = false)
        val viewModel = TranslationViewModel(repository)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.modelReady)
        viewModel.downloadLanguageModels()
        advanceUntilIdle()

        assertEquals(1, repository.downloadCalls)
        assertTrue(viewModel.state.value.modelReady)
        assertFalse(viewModel.state.value.modelDownloading)
    }

    private class RecordingTranslationRepository(
        private var modelReady: Boolean
    ) : TranslationRepository {
        var translateCalls = 0
        var downloadCalls = 0

        override suspend fun availableLanguages(): AppResult<List<LanguageOption>> =
            AppResult.Success(
                listOf(
                    LanguageOption("en", "English"),
                    LanguageOption("id", "Indonesian")
                )
            )

        override suspend fun modelStatus(
            sourceLanguage: String,
            targetLanguage: String
        ): AppResult<TranslationModelStatus> = AppResult.Success(
            TranslationModelStatus(sourceLanguage, targetLanguage, modelReady)
        )

        override suspend fun downloadModels(sourceLanguage: String, targetLanguage: String): AppResult<Unit> {
            downloadCalls += 1
            modelReady = true
            return AppResult.Success(Unit)
        }

        override suspend fun translate(request: TranslationRequest): AppResult<TranslationResult> {
            translateCalls += 1
            return AppResult.Success(
                TranslationResult(
                    text = "translated: ${request.text}",
                    provider = TranslationProviderKind.ML_KIT_PLAY_SERVICES,
                    sourceLanguage = request.sourceLanguage,
                    targetLanguage = request.targetLanguage
                )
            )
        }
    }
}
