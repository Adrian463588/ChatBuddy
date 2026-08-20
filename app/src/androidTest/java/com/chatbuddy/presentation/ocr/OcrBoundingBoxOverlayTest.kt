package com.chatbuddy.presentation.ocr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import com.chatbuddy.domain.model.OcrResult
import com.chatbuddy.domain.model.OcrTextBlock
import com.chatbuddy.ui.theme.ChatBuddyTheme
import org.junit.Rule
import org.junit.Test

class OcrBoundingBoxOverlayTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun detectedTextRegionsAreExposedToAccessibility() {
        composeRule.setContent {
            ChatBuddyTheme {
                Box(Modifier.size(320.dp)) {
                    OcrBoundingBoxOverlay(
                        result = OcrResult(
                            text = "ChatBuddy",
                            blocks = listOf(OcrTextBlock("ChatBuddy", 10f, 10f, 120f, 50f)),
                            languageTag = "en",
                            imageWidth = 200,
                            imageHeight = 100
                        ),
                        modifier = Modifier.size(320.dp)
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("OCR detected text regions")
            .assertIsDisplayed()
    }
}
