package com.campuslink.mobile

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.ExperimentalTestApi
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class MainActivitySmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun unauthenticatedUserSeesLoginScreen() {
        rule.waitUntilAtLeastOneExists(hasText("CampusLink"), timeoutMillis = 5_000)
        rule.onNodeWithText("CampusLink").assertIsDisplayed()
        rule.onNodeWithText("Login").assertIsDisplayed()
    }
}
