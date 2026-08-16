package com.campuslink.mobile.ui

import com.campuslink.mobile.core.model.ReportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationStateTest {
    @Test
    fun `home delegates system back while other tab roots return home`() {
        assertNull(NavigationState(Screen.Home).goBack())
        assertBack(Screen.Conversations, Screen.Home)
        assertBack(Screen.Profile, Screen.Home)
        assertBack(Screen.Chat("conversation-7"), Screen.Conversations)
        assertBack(Screen.Settings, Screen.Profile)
        assertBack(Screen.Services, Screen.Home)
        assertEquals(
            Screen.Chat("conversation-7"),
            NavigationState().openServices(Screen.Chat("conversation-7")).backTarget(),
        )
    }

    @Test
    fun `facilities back tree matches top app bar behavior`() {
        assertBack(Screen.FacilitiesHome, Screen.Home)
        assertBack(Screen.FacilitiesSearch, Screen.FacilitiesHome)
        assertBack(Screen.SpaceDetails(42), Screen.FacilitiesSearch)
        assertBack(Screen.MyBookings, Screen.FacilitiesHome)
        assertEquals(
            Screen.MyBookings,
            NavigationState().openBookingDetails(72, Screen.MyBookings).backTarget(),
        )
        assertBack(Screen.SubmitMaintenance(), Screen.FacilitiesHome)
        assertBack(Screen.SubmitMaintenance(42), Screen.SpaceDetails(42))
        assertBack(Screen.MyMaintenance, Screen.FacilitiesHome)
    }

    @Test
    fun `maintenance details preserve their dynamic return target`() {
        assertEquals(
            Screen.MyMaintenance,
            NavigationState().openMaintenanceDetails(91, Screen.MyMaintenance).backTarget(),
        )
        assertEquals(
            Screen.SubmitMaintenance(42),
            NavigationState()
                .openMaintenanceDetails(91, Screen.SubmitMaintenance(42))
                .backTarget(),
        )
    }

    @Test
    fun `lost found details preserve browse and claims origins`() {
        assertBack(Screen.LostFoundHome, Screen.Home)
        assertBack(Screen.LostFoundBrowse, Screen.LostFoundHome)
        assertBack(Screen.LostFoundDetails(8), Screen.LostFoundBrowse)
        assertBack(Screen.LostFoundDetails(8, returnToClaims = true), Screen.LostFoundClaims)
        assertBack(Screen.LostFoundClaims, Screen.LostFoundHome)
        assertBack(Screen.CreateLostFoundReport(ReportType.LOST), Screen.LostFoundHome)
    }

    @Test
    fun `every screen round trips through its stable saved route`() {
        val screens = listOf(
            Screen.Home,
            Screen.Conversations,
            Screen.Chat("conversation-7"),
            Screen.Profile,
            Screen.Settings,
            Screen.Services,
            Screen.FacilitiesHome,
            Screen.FacilitiesSearch,
            Screen.SpaceDetails(42),
            Screen.MyBookings,
            Screen.BookingDetails(72),
            Screen.SubmitMaintenance(),
            Screen.SubmitMaintenance(42),
            Screen.MyMaintenance,
            Screen.MaintenanceDetails(91),
            Screen.LostFoundHome,
            Screen.LostFoundBrowse,
            Screen.LostFoundDetails(8),
            Screen.LostFoundDetails(8, returnToClaims = true),
            Screen.CreateLostFoundReport(ReportType.FOUND),
            Screen.LostFoundClaims,
        )

        screens.forEach { screen -> assertEquals(screen, screenFromRouteKey(screen.routeKey())) }
        assertEquals(Screen.Home, screenFromRouteKey("space-details|not-a-number"))
        assertEquals(Screen.Home, screenFromRouteKey("unknown"))
    }

    private fun assertBack(from: Screen, expected: Screen) {
        assertEquals(expected, NavigationState(screen = from).backTarget())
    }
}
