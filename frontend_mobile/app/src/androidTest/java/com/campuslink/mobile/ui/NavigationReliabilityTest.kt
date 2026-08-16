package com.campuslink.mobile.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationReliabilityTest {
    @get:Rule
    val rule = createAndroidComposeRule<NavigationTestActivity>()

    @Test
    fun facilitiesNestedSystemBackFollowsExistingParentChain() {
        navigateTo(NavigationState(screen = Screen.SpaceDetails(42)))

        pressBackAndAssert(Screen.FacilitiesSearch)
        pressBackAndAssert(Screen.FacilitiesHome)
        pressBackAndAssert(Screen.Services)
        pressBackAndAssert(Screen.Conversations)

        assertFalse(rule.activity.isFinishing)
    }

    @Test
    fun lostFoundSystemBackPreservesBrowseAndClaimsOrigins() {
        navigateTo(NavigationState(screen = Screen.LostFoundDetails(8)))
        pressBackAndAssert(Screen.LostFoundBrowse)

        navigateTo(NavigationState(screen = Screen.LostFoundDetails(9, returnToClaims = true)))
        pressBackAndAssert(Screen.LostFoundClaims)
        pressBackAndAssert(Screen.LostFoundHome)
    }

    @Test
    fun spaceAndBookingDetailsSurviveRecreationWithIdsAndBackTargets() {
        navigateTo(NavigationState(screen = Screen.SpaceDetails(42)))
        recreateAndAssert(Screen.SpaceDetails(42))
        pressBackAndAssert(Screen.FacilitiesSearch)

        navigateTo(NavigationState().openBookingDetails(72, Screen.MyBookings))
        recreateAndAssert(Screen.BookingDetails(72))
        pressBackAndAssert(Screen.MyBookings)
    }

    @Test
    fun maintenanceDetailsSurviveRecreationWithDynamicReturnTarget() {
        navigateTo(
            NavigationState().openMaintenanceDetails(
                ticketId = 91,
                returnTo = Screen.SubmitMaintenance(42),
            ),
        )

        recreateAndAssert(Screen.MaintenanceDetails(91))
        pressBackAndAssert(Screen.SubmitMaintenance(42))
    }

    @Test
    fun lostFoundDetailsSurviveRecreationWithReportAndClaimsOrigin() {
        navigateTo(NavigationState(screen = Screen.LostFoundDetails(8, returnToClaims = true)))

        recreateAndAssert(Screen.LostFoundDetails(8, returnToClaims = true))
        pressBackAndAssert(Screen.LostFoundClaims)
    }

    @Test
    fun chatSurvivesRecreationWithConversationId() {
        navigateTo(NavigationState(screen = Screen.Chat("conversation-7")))

        recreateAndAssert(Screen.Chat("conversation-7"))
        pressBackAndAssert(Screen.Conversations)
    }

    private fun navigateTo(state: NavigationState) {
        rule.runOnUiThread { rule.activity.navigateTo(state) }
        assertRoute(state.screen)
    }

    private fun recreateAndAssert(screen: Screen) {
        rule.activityRule.scenario.recreate()
        assertRoute(screen)
    }

    private fun pressBackAndAssert(screen: Screen) {
        Espresso.pressBack()
        assertRoute(screen)
    }

    private fun assertRoute(screen: Screen) {
        rule.onNodeWithText(screen.routeKey()).assertTextEquals(screen.routeKey())
    }
}
