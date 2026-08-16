package com.campuslink.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceComponentsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun statusAndEmptyStatesExposeReadableContent() {
        rule.setContent {
            CampusLinkTheme(darkTheme = false) {
                Column {
                    CampusStatusChip("CONFIRMED", CampusStatusTone.SUCCESS)
                    CampusEmptyState("Nothing here yet", "New records will appear here.")
                }
            }
        }

        rule.onNodeWithText("CONFIRMED").assertIsDisplayed()
        rule.onNodeWithText("Nothing here yet").assertIsDisplayed()
        rule.onNodeWithText("New records will appear here.").assertIsDisplayed()
    }

    @Test
    fun errorStateRetryIsARealButton() {
        var retried = false
        rule.setContent {
            CampusLinkTheme(darkTheme = true) {
                CampusErrorState(
                    title = "Unable to load",
                    message = "Check your connection.",
                    retryLabel = "Retry",
                    onRetry = { retried = true },
                )
            }
        }

        rule.onNodeWithText("Retry").assertHasClickAction().performClick()
        rule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun sharedTopBarProvidesBackDescription() {
        var returned = false
        rule.setContent {
            CampusLinkTheme(darkTheme = false) {
                CampusTopAppBar("Space Details", { returned = true }, "Back to search")
            }
        }

        rule.onNodeWithContentDescription("Back to search").performClick()
        rule.runOnIdle { assertTrue(returned) }
    }
}
