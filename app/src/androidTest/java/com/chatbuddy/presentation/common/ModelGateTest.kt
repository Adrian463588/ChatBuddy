package com.chatbuddy.presentation.common

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.chatbuddy.domain.model.ModelStatus
import com.chatbuddy.domain.model.ModelStorageKind
import com.chatbuddy.ui.theme.ChatBuddyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModelGateTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unavailableModelShowsActionableSetupAndInvokesDownload() {
        var downloadClicks = 0
        composeRule.setContent {
            ChatBuddyTheme {
                ModelGate(
                    status = ModelStatus.NotInstalled,
                    onDownload = { downloadClicks++ }
                ) {
                    Text("Chat content")
                }
            }
        }

        composeRule.onNodeWithText("Local AI setup required").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Download local AI model")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals(1, downloadClicks) }
        assertEquals(0, composeRule.onAllNodesWithText("Chat content").fetchSemanticsNodes().size)
    }

    @Test
    fun readyModelRendersFeatureContentWithoutSetupPrompt() {
        composeRule.setContent {
            ChatBuddyTheme {
                ModelGate(
                    status = ModelStatus.Ready(ModelStorageKind.SAF_PERSISTENT),
                    onDownload = {}
                ) {
                    Text("Chat content")
                }
            }
        }

        composeRule.onNodeWithText("Chat content").assertIsDisplayed()
        assertEquals(0, composeRule.onAllNodesWithText("Local AI setup required").fetchSemanticsNodes().size)
    }
}
