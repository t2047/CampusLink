package com.campuslink.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.campuslink.mobile.core.model.Conversation
import com.campuslink.mobile.core.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppShellUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val text = strings(AppLanguage.ENGLISH)

    @Test
    fun homeRendersHeroServicesAgentMailAndQuickAccess() {
        showHome()

        listOf(
            "CampusAgent",
            "Facilities",
            "Lost & Found",
            "Mail",
            "Managed by Agent",
            "Quick Access",
            "My Bookings",
            "My Maintenance",
            "My Claims",
        ).forEach { label ->
            rule.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(label))
            rule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun homeRoutesToFacilitiesLostFoundMailAndAgentCore() {
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
        rule.onNodeWithText("Lost & Found").performClick()
        assertEquals("lost-found", destination)
        rule.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText("Mail"))
        rule.onNodeWithText("Mail").performClick()
        assertEquals("agent", destination)
    }

    @Test
    fun homeQuickAccessUsesExistingCallbacks() {
        var destination = ""
        rule.setContent {
            CampusLinkTheme(darkTheme = false) {
                HomeScreen(
                    actions = HomeActions(
                        openAgentCore = {},
                        openFacilities = {},
                        openLostFound = {},
                        openMyBookings = { destination = "bookings" },
                        openMyMaintenance = { destination = "maintenance" },
                        openMyClaims = { destination = "claims" },
                    ),
                    text = text.home,
                )
            }
        }

        listOf(
            "My Bookings" to "bookings",
            "My Maintenance" to "maintenance",
            "My Claims" to "claims",
        ).forEach { (label, expected) ->
            rule.onNodeWithTag(HOME_LIST_TAG).performScrollToNode(hasText(label))
            rule.onNodeWithText(label).performClick()
            assertEquals(expected, destination)
        }
    }

    @Test
    fun agentCoreEmptyStateStartsNewChat() {
        var created = false
        rule.setContent {
            CampusLinkTheme(darkTheme = false) {
                AgentCoreContent(
                    conversations = emptyList(),
                    text = text.agentCore,
                    onCreate = { created = true },
                    onOpen = {},
                    onDelete = {},
                )
            }
        }

        rule.onNodeWithText("Agent Core").assertIsDisplayed()
        rule.onNodeWithText("Start a conversation").assertIsDisplayed()
        rule.onNodeWithText("Start New Chat").performClick()
        assertTrue(created)
    }

    @Test
    fun agentCoreListShowsNewChatAndOpensConversation() {
        var opened = ""
        val conversation = Conversation(
            id = "conversation-1",
            ownerEmail = "student@example.com",
            title = "Campus services question",
            createdAt = 1_000L,
            updatedAt = 2_000L,
        )
        rule.setContent {
            CampusLinkTheme(darkTheme = false) {
                AgentCoreContent(
                    conversations = listOf(conversation),
                    text = text.agentCore,
                    onCreate = {},
                    onOpen = { opened = it },
                    onDelete = {},
                )
            }
        }

        rule.onNodeWithText("New Chat").assertIsDisplayed()
        rule.onNodeWithText("Recent Conversations").assertIsDisplayed()
        rule.onNodeWithText("Campus services question").performClick()
        assertEquals("conversation-1", opened)
    }

    @Test
    fun bottomNavigationShowsThreeTabsAndSelectedBehavior() {
        rule.setContent {
            var selected by remember { mutableStateOf(AppTab.HOME) }
            CampusLinkTheme(darkTheme = false) {
                CampusLinkBottomNavigation(
                    selectedTab = selected,
                    text = text.shell,
                    onTabSelected = { selected = it },
                )
            }
        }

        rule.onNodeWithText("Home").assertIsDisplayed().assertIsSelected()
        rule.onNodeWithContentDescription("Home", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Agent Core").performClick().assertIsSelected()
        rule.onNodeWithContentDescription("Agent Core", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Profile").performClick().assertIsSelected()
        rule.onNodeWithContentDescription("Profile", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun profileShowsIdentityAndDispatchesPreferences() {
        var dark = false
        var language = AppLanguage.ENGLISH
        showProfile(
            onDark = { dark = it },
            onLanguage = { language = it },
        )

        rule.onNodeWithText("student@example.com").assertIsDisplayed()
        rule.onNodeWithText("Role · STUDENT").assertIsDisplayed()
        rule.onNodeWithText("Dark Mode").performClick()
        assertTrue(dark)
        rule.onNodeWithText("Language").performClick()
        assertEquals(AppLanguage.CHINESE, language)
    }

    @Test
    fun profileClearHistoryRequiresConfirmation() {
        var cleared = false
        showProfile(onClear = { cleared = true })

        rule.onNodeWithTag(PROFILE_LIST_TAG).performScrollToNode(hasText("Clear Chat History"))
        rule.onNodeWithText("Clear Chat History").performClick()
        rule.onNodeWithText("Clear chat history?").assertIsDisplayed()
        assertFalse(cleared)
        rule.onNodeWithText("Clear").performClick()
        assertTrue(cleared)
    }

    @Test
    fun profileLogoutRequiresConfirmation() {
        var loggedOut = false
        showProfile(onLogout = { loggedOut = true })

        rule.onNodeWithTag(PROFILE_LIST_TAG).performScrollToNode(hasText("Log Out"))
        rule.onNodeWithText("Log Out").performClick()
        rule.onNodeWithText("Log out?").assertIsDisplayed()
        assertFalse(loggedOut)
        rule.onNodeWithText("Log out").performClick()
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
                    actions = HomeActions(
                        openAgentCore = onAgentCore,
                        openFacilities = onFacilities,
                        openLostFound = onLostFound,
                        openMyBookings = {},
                        openMyMaintenance = {},
                        openMyClaims = {},
                        openMail = onAgentCore,
                    ),
                    text = text.home,
                )
            }
        }
    }

    private fun showProfile(
        onLanguage: (AppLanguage) -> Unit = {},
        onDark: (Boolean) -> Unit = {},
        onClear: () -> Unit = {},
        onLogout: () -> Unit = {},
    ) {
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
                        changeLanguage = onLanguage,
                        changeDark = onDark,
                        clearHistory = onClear,
                        logout = onLogout,
                    ),
                    text = text.profile,
                )
            }
        }
    }
}
