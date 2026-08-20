package com.chatbuddy.presentation.ocr

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.TranslationProviderKind
import com.chatbuddy.domain.model.TranslationResult
import com.chatbuddy.presentation.translate.TranslationUiState
import com.chatbuddy.ui.theme.ChatBuddyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LiveOcrTranscriptTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyCameraResultShowsScanningState() {
        composeRule.setContent {
            ChatBuddyTheme {
                LiveOcrTranscript(
                    result = null,
                    translationState = TranslationUiState(),
                    translationProvider = null,
                    onDownloadTranslation = {},
                    onStopCamera = {}
                )
            }
        }

        composeRule.onNodeWithText("Live transcript").assertIsDisplayed()
        composeRule.onNodeWithText("Scanning").assertIsDisplayed()
        composeRule.onNodeWithText("Point the camera at text").assertIsDisplayed()
    }

    @Test
    fun currentOcrTextShowsBoundTranslation() {
        val result = OcrResult(
            text = "Hello world",
            blocks = emptyList(),
            languageTag = "en"
        )
        val translationState = TranslationUiState(
            sourceText = "Hello world",
            result = TranslationResult(
                text = "Halo dunia",
                provider = TranslationProviderKind.LOCAL_OPUS_ONNX,
                sourceLanguage = "en",
                targetLanguage = "id"
            ),
            modelReady = true,
            modelChecking = false
        )

        composeRule.setContent {
            ChatBuddyTheme {
                LiveOcrTranscript(
                    result = result,
                    translationState = translationState,
                    translationProvider = "Local model",
                    onDownloadTranslation = {},
                    onStopCamera = {}
                )
            }
        }

        composeRule.onNodeWithText("Hello world").assertIsDisplayed()
        composeRule.onNodeWithText("Halo dunia").assertIsDisplayed()
        composeRule.onNodeWithText("Local model").assertIsDisplayed()
    }

    @Test
    fun unavailableTranslationShowsAction() {
        var downloadClicks = 0
        var stopClicks = 0
        composeRule.setContent {
            ChatBuddyTheme {
                LiveOcrTranscript(
                    result = OcrResult("Hello", emptyList(), "en"),
                    translationState = TranslationUiState(
                        sourceText = "Hello",
                        modelReady = false,
                        modelChecking = false
                    ),
                    translationProvider = null,
                    onDownloadTranslation = { downloadClicks++ },
                    onStopCamera = { stopClicks++ }
                )
            }
        }

        composeRule.onNodeWithText("Stop camera")
            .assertIsDisplayed()
            .performClick()
        composeRule.onNodeWithText("Translation pack is not ready").assertIsDisplayed()
        composeRule.onNodeWithText("Download translation pack")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            assertEquals(1, downloadClicks)
            assertEquals(1, stopClicks)
        }
    }
}
