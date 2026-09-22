package com.chatbuddy.presentation.ocr

import com.chatbuddy.domain.model.AppResult
import com.chatbuddy.domain.model.ImageTranslationResult
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.OcrTextBlock
import com.chatbuddy.domain.model.TranslatedBlock
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.domain.repository.ImageTranslationRepository
import com.chatbuddy.domain.repository.OcrRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OcrViewModelTest {
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun consecutiveCameraFramesWithIdenticalTextPreserveTranslatedBlocks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakeOcrRepo = FakeOcrRepository()
        val fakeImageTranslationRepo = FakeImageTranslationRepository()
        val viewModel = OcrViewModel(fakeOcrRepo, fakeImageTranslationRepo)
        advanceUntilIdle()

        val block1 = OcrTextBlock("Hello World", 10f, 10f, 100f, 50f)
        val frame1 = OcrResult("Hello World", listOf(block1), "en", 1080, 1920)

        // Frame 1 arrives
        viewModel.onCameraResult(frame1)
        advanceUntilIdle()

        // Verify translation occurred and state is populated
        val state1 = viewModel.state.value
        assertEquals(1, state1.translatedBlocks.size)
        assertEquals("Halo Dunia", state1.translatedBlocks[0].translatedText)

        // Frame 2 arrives with identical text but slightly jittered bounding box (camera motion)
        val block2 = OcrTextBlock("Hello World", 12f, 11f, 102f, 51f)
        val frame2 = OcrResult("Hello World", listOf(block2), "en", 1080, 1920)
        viewModel.onCameraResult(frame2)
        advanceUntilIdle()

        // Verify that translatedBlocks are NOT wiped out or left empty!
        val state2 = viewModel.state.value
        assertEquals(1, state2.translatedBlocks.size)
        assertEquals("Halo Dunia", state2.translatedBlocks[0].translatedText)
        assertEquals(12f, state2.translatedBlocks[0].source.left, 0.01f)
    }

    @Test
    fun liveTranslateCanBeToggled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakeOcrRepo = FakeOcrRepository()
        val fakeImageTranslationRepo = FakeImageTranslationRepository()
        val viewModel = OcrViewModel(fakeOcrRepo, fakeImageTranslationRepo)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.isLiveTranslateEnabled)

        viewModel.toggleLiveTranslate()
        assertFalse(viewModel.state.value.isLiveTranslateEnabled)

        viewModel.toggleLiveTranslate()
        assertTrue(viewModel.state.value.isLiveTranslateEnabled)
    }

    @Test
    fun freezingFramePreventsNewFramesFromOverwriting() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakeOcrRepo = FakeOcrRepository()
        val fakeImageTranslationRepo = FakeImageTranslationRepository()
        val viewModel = OcrViewModel(fakeOcrRepo, fakeImageTranslationRepo)
        advanceUntilIdle()

        val block1 = OcrTextBlock("Hello World", 10f, 10f, 100f, 50f)
        val frame1 = OcrResult("Hello World", listOf(block1), "en", 1080, 1920)
        viewModel.onCameraResult(frame1)
        advanceUntilIdle()

        assertEquals("Halo Dunia", viewModel.state.value.translatedBlocks[0].translatedText)

        // Freeze frame
        viewModel.freezeFrame()
        assertTrue(viewModel.state.value.isFrozen)

        // New camera frame arrives while frozen
        val blockNew = OcrTextBlock("Different Text", 0f, 0f, 50f, 50f)
        val frameNew = OcrResult("Different Text", listOf(blockNew), "en", 1080, 1920)
        viewModel.onCameraResult(frameNew)
        advanceUntilIdle()

        // State MUST remain frozen on the previous result!
        assertEquals("Hello World", viewModel.state.value.result?.text)
        assertEquals("Halo Dunia", viewModel.state.value.translatedBlocks[0].translatedText)

        // Unfreeze restores live frame processing
        viewModel.unfreezeCamera()
        assertFalse(viewModel.state.value.isFrozen)
        viewModel.onCameraResult(frameNew)
        advanceUntilIdle()
        assertEquals("Different Text", viewModel.state.value.result?.text)
    }

    @Test
    fun fullTextIsTranslatedAlongsideBlocks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fakeOcrRepo = FakeOcrRepository()
        val fakeImageTranslationRepo = FakeImageTranslationRepository()
        val viewModel = OcrViewModel(fakeOcrRepo, fakeImageTranslationRepo)
        advanceUntilIdle()

        val block = OcrTextBlock("Hello World", 10f, 10f, 100f, 50f)
        val frame = OcrResult("Hello World", listOf(block), "en", 1080, 1920)
        viewModel.onCameraResult(frame)
        advanceUntilIdle()

        assertEquals("Halo Dunia", viewModel.state.value.fullTranslation)
    }

    private class FakeOcrRepository : OcrRepository {
        override suspend fun recognizeImage(uri: String, languageTag: String): AppResult<OcrResult> =
            AppResult.Success(OcrResult("Hello World", listOf(OcrTextBlock("Hello World", 0f, 0f, 10f, 10f)), languageTag))
    }

    private class FakeImageTranslationRepository : ImageTranslationRepository {
        var translateCount = 0

        override suspend fun translateImage(
            uri: String,
            sourceLanguage: String,
            targetLanguage: String
        ): AppResult<ImageTranslationResult> =
            AppResult.Success(ImageTranslationResult(uri, OcrResult("", emptyList(), sourceLanguage), emptyList()))

        override suspend fun translateBlocks(
            ocr: OcrResult,
            sourceLanguage: String,
            targetLanguage: String
        ): AppResult<List<TranslatedBlock>> {
            translateCount++
            return AppResult.Success(
                ocr.blocks.map { block ->
                    val tr = if (block.text == "Hello World") "Halo Dunia" else "Terjemahan: ${block.text}"
                    TranslatedBlock(block, tr, TranslationProviderKind.ML_KIT_PLAY_SERVICES)
                }
            )
        }

        override suspend fun translateText(
            text: String,
            sourceLanguage: String,
            targetLanguage: String
        ): AppResult<String> {
            val tr = if (text == "Hello World") "Halo Dunia" else "Terjemahan: $text"
            return AppResult.Success(tr)
        }
    }
}
