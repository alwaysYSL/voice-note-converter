package com.aistudio.voicenote.cvtr.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppNavigationIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testBottomNavigationRendersAllThreeTabs() {
        composeTestRule.setContent {
            AppNavigation()
        }

        // Verify bottom nav items exist
        composeTestRule.onNodeWithContentDescription("Converter").assertExists()
        composeTestRule.onNodeWithContentDescription("Editor").assertExists()
        composeTestRule.onNodeWithContentDescription("Riwayat").assertExists()

        // Navigate to Editor tab
        composeTestRule.onNodeWithContentDescription("Editor").performClick()
        composeTestRule.onNodeWithText("Audio Editor & Mixer").assertExists()

        // Navigate to Riwayat tab
        composeTestRule.onNodeWithContentDescription("Riwayat").performClick()
        composeTestRule.onNodeWithText("Belum ada konversi").assertExists()

        // Navigate back to Converter tab
        composeTestRule.onNodeWithContentDescription("Converter").performClick()
        composeTestRule.onNodeWithText("Voice note studio").assertExists()
    }
}