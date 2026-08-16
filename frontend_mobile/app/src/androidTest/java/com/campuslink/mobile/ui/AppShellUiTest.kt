package com.campuslink.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.campuslink.mobile.core.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun homeRendersServicesAndQuickAccess() {
        showHome()

        listOf(
            "Facilities",
            "Lost & Found",
            "Mail",
            "My Bookings",
            "My Maintenance",
            "My Claims",
        ).forEach { label ->
            rule.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(label))
            rule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun homeRoutesToFacilitiesLostFoundAndAgentCore() {
        var destination = ""
        showHome(
            onAgentCore = { destination = "agent" },
            onFacilities = { destination = "facilities" },
            onLostFound = { destination = "lost-found" },
        )

        rule.onNodeWithText("Ask Agent").performClick()
        assertEquals("agent", destination)
        rule.onNodeWithText("Facilities").performClick()
        assertEquals("facilities", destination)
        rule.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText("Lost & Found"))
        rule.onNodeWithText("Lost & Found").performClick()
        assertEquals("lost-found", destination)
    }

    @Test
    fun bottomNavigationShowsThreeTabsAndDispatchesSelection() {
        var selected = AppTab.HOME
        rule.setContent {
            CampusLinkTheme(darkTheme = false) {
                CampusLinkBottomNavigation(selectedTab = selected) { selected = it }
            }
        }

        rule.onNodeWithText("Home").assertIsDisplayed()
        rule.onNodeWithContentDescription("Home", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Agent Core").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("Agent Core", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(AppTab.AGENT_CORE, selected)
        rule.onNodeWithText("Profile").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription("Profile", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(AppTab.PROFILE, selected)
    }

    @Test
    fun profileLogoutUsesProvidedCallback() {
        var loggedOut = false
        rule.setContent {
            CampusLinkTheme(darkTheme = false) {
                ProfileScreen(
                    state = ProfileUiState(
                        email = "student@example.com",
                        role = "STUDENT",
                        versionName = "0.1.0-test",
                        language = AppLanguage.ENGLISH,
                        dark = false,
                    ),
                    actions = ProfileActions(
                        changeLanguage = {},
                        changeDark = {},
                        clearHistory = {},
                        logout = { loggedOut = true },
                    ),
                )
            }
        }

        rule.onNodeWithTag(PROFILE_LIST_TAG).performScrollToNode(hasText("Log Out"))
        rule.onNodeWithText("Log Out").performClick()
        assertTrue(loggedOut)
    }

    private fun showHome(
        onAgentCore: () -> Unit = {},
        onFacilities: () -> Unit = {},
        onLostFound: () -> Unit = {},
    ) {
        rule.setContent {
            CampusLinkTheme(darkTheme = false) {
                HomeScreen(
                    HomeActions(
                        openAgentCore = onAgentCore,
                        openFacilities = onFacilities,
                        openLostFound = onLostFound,
                        openMyBookings = {},
                        openMyMaintenance = {},
                        openMyClaims = {},
                    ),
                )
            }
        }
    }
}
