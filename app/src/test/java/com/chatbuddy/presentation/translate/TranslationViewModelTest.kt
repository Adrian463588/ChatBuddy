package com.chatbuddy.presentation.translate

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.LanguageOption
import com.chatbuddy.domain.model.LiveTranslationPhase
import com.chatbuddy.domain.model.TranslationModelStatus
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.domain.model.TranslationRequest
import com.chatbuddy.domain.model.TranslationResult
import com.chatbuddy.domain.model.TranslationHistoryEntry
import com.chatbuddy.domain.model.VoiceCapabilities
import com.chatbuddy.domain.model.VoiceTranscript
import com.chatbuddy.domain.repository.TranslationHistoryRepository
import com.chatbuddy.domain.repository.TranslationRepository
import com.chatbuddy.domain.repository.VoiceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
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
        val viewModel = TranslationViewModel(repository, RecordingVoiceRepository(), EmptyHistoryRepository())
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
        val viewModel = TranslationViewModel(repository, RecordingVoiceRepository(), EmptyHistoryRepository())
        advanceUntilIdle()

        assertFalse(viewModel.state.value.modelReady)
        viewModel.downloadLanguageModels()
        advanceUntilIdle()

        assertEquals(1, repository.downloadCalls)
        assertTrue(viewModel.state.value.modelReady)
        assertFalse(viewModel.state.value.modelDownloading)
    }

    @Test
    fun liveConversationTranslatesFinalWhisperTurnAndReturnsToListening() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repository = RecordingTranslationRepository(modelReady = true)
        val viewModel = TranslationViewModel(repository, RecordingVoiceRepository(), EmptyHistoryRepository())
        advanceUntilIdle()

        viewModel.toggleLiveTranslation()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.liveEnabled)
        assertEquals("translated: hello", viewModel.state.value.liveTranslation?.text)
        assertEquals("hello", viewModel.state.value.liveTranscript)
        assertEquals(LiveTranslationPhase.Listening, viewModel.state.value.livePhase)
    }

    @Test
    fun cancelledLiveTurnCannotOverwriteTheNextSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val releaseFirstTurn = CompletableDeferred<Unit>()
        val repository = DelayedTranslationRepository(releaseFirstTurn)
        val viewModel = TranslationViewModel(repository, SequencedVoiceRepository(), EmptyHistoryRepository())
        advanceUntilIdle()

        viewModel.toggleLiveTranslation()
        runCurrent()
        assertEquals(1, repository.translateCalls)

        viewModel.stopLiveTranslation()
        viewModel.toggleLiveTranslation()
        advanceUntilIdle()

        assertEquals("translated: new", viewModel.state.value.liveTranslation?.text)

        releaseFirstTurn.complete(Unit)
        advanceUntilIdle()

        assertEquals("translated: new", viewModel.state.value.liveTranslation?.text)
        assertEquals(LiveTranslationPhase.Listening, viewModel.state.value.livePhase)
    }

    @Test
    fun voiceFailureIsShownAndDoesNotLeaveLiveSessionActive() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = TranslationViewModel(
            RecordingTranslationRepository(modelReady = true),
            FailingVoiceRepository(),
            EmptyHistoryRepository()
        )
        advanceUntilIdle()

        viewModel.toggleLiveTranslation()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.liveEnabled)
        assertEquals(LiveTranslationPhase.Error, viewModel.state.value.livePhase)
        assertEquals("Microphone capture stopped unexpectedly", viewModel.state.value.liveError)
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

    private class EmptyHistoryRepository : TranslationHistoryRepository {
        override fun observeRecent(limit: Int): Flow<List<TranslationHistoryEntry>> = emptyFlow()

        override suspend fun add(entry: TranslationHistoryEntry): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun clear(): AppResult<Unit> = AppResult.Success(Unit)
    }

    private class RecordingVoiceRepository : VoiceRepository {
        override suspend fun capabilities(languageTag: String): AppResult<VoiceCapabilities> =
            AppResult.Success(
                VoiceCapabilities(
                    whisperReady = true,
                    offlineTtsReady = true,
                    message = "Voice turn-taking is ready"
                )
            )

        override fun transcribe(languageTag: String): Flow<VoiceTranscript> = flow {
            emit(VoiceTranscript.Partial("hello"))
            emit(VoiceTranscript.Final("hello"))
            awaitCancellation()
        }

        override suspend fun speak(text: String, languageTag: String): AppResult<Unit> =
            AppResult.Success(Unit)
    }

    private class DelayedTranslationRepository(
        private val releaseFirstTurn: CompletableDeferred<Unit>
    ) : TranslationRepository {
        var translateCalls = 0

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
        ): AppResult<TranslationModelStatus> =
            AppResult.Success(TranslationModelStatus(sourceLanguage, targetLanguage, ready = true))

        override suspend fun downloadModels(sourceLanguage: String, targetLanguage: String): AppResult<Unit> =
            AppResult.Success(Unit)

        override suspend fun translate(request: TranslationRequest): AppResult<TranslationResult> {
            translateCalls += 1
            if (request.text == "old") {
                withContext(NonCancellable) { releaseFirstTurn.await() }
            }
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

    private class SequencedVoiceRepository : VoiceRepository {
        private var sessionCount = 0

        override suspend fun capabilities(languageTag: String): AppResult<VoiceCapabilities> =
            AppResult.Success(
                VoiceCapabilities(
                    whisperReady = true,
                    offlineTtsReady = false,
                    message = "Whisper is ready"
                )
            )

        override fun transcribe(languageTag: String): Flow<VoiceTranscript> = flow {
            sessionCount += 1
            emit(VoiceTranscript.Final(if (sessionCount == 1) "old" else "new"))
            awaitCancellation()
        }

        override suspend fun speak(text: String, languageTag: String): AppResult<Unit> =
            AppResult.Success(Unit)
    }

    private class FailingVoiceRepository : VoiceRepository {
        override suspend fun capabilities(languageTag: String): AppResult<VoiceCapabilities> =
            AppResult.Success(
                VoiceCapabilities(
                    whisperReady = true,
                    offlineTtsReady = false,
                    message = "Whisper is ready"
                )
            )

        override fun transcribe(languageTag: String): Flow<VoiceTranscript> = flow {
            emit(VoiceTranscript.Failed("Microphone capture stopped unexpectedly"))
        }

        override suspend fun speak(text: String, languageTag: String): AppResult<Unit> =
            AppResult.Success(Unit)
    }
}
