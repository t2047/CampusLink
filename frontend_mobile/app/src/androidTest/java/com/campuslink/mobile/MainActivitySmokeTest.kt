package com.campuslink.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainActivitySmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun unauthenticatedUserSeesLoginScreen() {
        rule.onNodeWithText("CampusLink").assertIsDisplayed()
        rule.onNodeWithText("Login").assertIsDisplayed()
    }
}
