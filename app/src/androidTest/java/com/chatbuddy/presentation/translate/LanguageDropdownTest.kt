package com.chatbuddy.presentation.translate

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.chatbuddy.domain.model.LanguageOption
import com.chatbuddy.ui.theme.ChatBuddyTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LanguageDropdownTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inputFiltersLanguagesAndSelectingOptionUpdatesCallback() {
        var selectedTag by mutableStateOf("en")
        composeRule.setContent {
            ChatBuddyTheme {
                LanguageDropdown(
                    label = "From",
                    selected = selectedTag,
                    languages = listOf(
                        LanguageOption("en", "English"),
                        LanguageOption("id", "Indonesian"),
                        LanguageOption("ja", "Japanese")
                    ),
                    onSelected = { selectedTag = it },
                    modifier = Modifier
                )
            }
        }

        val languageField = composeRule.onNode(hasSetTextAction())
        languageField.performTextClearance()
        languageField.performTextInput("indo")
        assertEquals(
            1,
            composeRule.onAllNodesWithText("Indonesian").fetchSemanticsNodes().size
        )
        composeRule.onAllNodesWithText("Indonesian")[0]
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle { assertEquals("id", selectedTag) }
    }

    @Test
    fun emptyLanguageListDisablesInput() {
        composeRule.setContent {
            ChatBuddyTheme {
                LanguageDropdown(
                    label = "To",
                    selected = "",
                    languages = emptyList(),
                    onSelected = {},
                    modifier = Modifier
                )
                Text("Ready")
            }
        }

        composeRule.onNodeWithText("To").assertIsDisplayed()
        composeRule.onNodeWithText("Ready").assertIsDisplayed()
    }
}
