package com.campuslink.mobile.ui.mail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.campuslink.mobile.core.settings.AppLanguage
import com.campuslink.mobile.ui.CampusLinkTheme
import com.campuslink.mobile.ui.strings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MailFolderTabsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun allFoldersRemainSingleRowAndArchiveCanBeSelectedAt360Dp() {
        var selectedFolder by mutableStateOf("inbox")
        val text = strings(AppLanguage.ENGLISH).mail

        rule.setContent {
            CampusLinkTheme(darkTheme = false) {
                Box(Modifier.width(360.dp)) {
                    MailFolderTabs(
                        selectedFolder = selectedFolder,
                        text = text,
                        onFolderSelected = { selectedFolder = it },
                    )
                }
            }
        }

        listOf(text.inbox, text.sent, text.archive, text.trash, text.spam).forEach { label ->
            rule.onNodeWithText(label, substring = false).assertExists()
        }

        rule.onNodeWithTag("mail-folder-archived")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
            .assertIsSelected()
        assertEquals("archived", selectedFolder)

        rule.onNodeWithTag("mail-folder-spam")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
