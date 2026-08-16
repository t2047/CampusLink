package com.campuslink.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.listSaver
import com.campuslink.mobile.core.model.ReportType

internal sealed interface Screen {
    data object Home : Screen
    data object Conversations : Screen
    data class Chat(val id: String) : Screen
    data object Profile : Screen
    data object Settings : Screen
    data object Services : Screen
    data object FacilitiesHome : Screen
    data object FacilitiesSearch : Screen
    data class SpaceDetails(val spaceId: Long) : Screen
    data object MyBookings : Screen
    data class BookingDetails(val bookingId: Long) : Screen
    data class SubmitMaintenance(val preselectedSpaceId: Long? = null) : Screen
    data object MyMaintenance : Screen
    data class MaintenanceDetails(val ticketId: Long) : Screen
    data object LostFoundHome : Screen
    data object LostFoundBrowse : Screen
    data class LostFoundDetails(val reportId: Long, val returnToClaims: Boolean = false) : Screen
    data class CreateLostFoundReport(val reportType: ReportType) : Screen
    data object LostFoundClaims : Screen
}

internal data class NavigationState(
    val screen: Screen = Screen.Home,
    val servicesReturnScreen: Screen = Screen.Home,
    val bookingDetailsReturnScreen: Screen = Screen.MyBookings,
    val maintenanceDetailsReturnScreen: Screen = Screen.MyMaintenance,
) {
    fun navigate(screen: Screen): NavigationState = copy(screen = screen)

    fun openServices(returnTo: Screen = screen): NavigationState = copy(
        screen = Screen.Services,
        servicesReturnScreen = returnTo,
    )

    fun openBookingDetails(bookingId: Long, returnTo: Screen): NavigationState = copy(
        screen = Screen.BookingDetails(bookingId),
        bookingDetailsReturnScreen = returnTo,
    )

    fun openMaintenanceDetails(ticketId: Long, returnTo: Screen): NavigationState = copy(
        screen = Screen.MaintenanceDetails(ticketId),
        maintenanceDetailsReturnScreen = returnTo,
    )

    fun backTarget(): Screen? = when (val active = screen) {
        Screen.Home -> null
        Screen.Conversations, Screen.Profile -> Screen.Home
        is Screen.Chat -> Screen.Conversations
        Screen.Settings -> Screen.Profile
        Screen.Services -> servicesReturnScreen
        Screen.FacilitiesHome -> Screen.Home
        Screen.FacilitiesSearch -> Screen.FacilitiesHome
        is Screen.SpaceDetails -> Screen.FacilitiesSearch
        Screen.MyBookings -> Screen.FacilitiesHome
        is Screen.BookingDetails -> bookingDetailsReturnScreen
        is Screen.SubmitMaintenance -> active.preselectedSpaceId
            ?.let(Screen::SpaceDetails)
            ?: Screen.FacilitiesHome
        Screen.MyMaintenance -> Screen.FacilitiesHome
        is Screen.MaintenanceDetails -> maintenanceDetailsReturnScreen
        Screen.LostFoundHome -> Screen.Home
        Screen.LostFoundBrowse -> Screen.LostFoundHome
        is Screen.LostFoundDetails -> if (active.returnToClaims) {
            Screen.LostFoundClaims
        } else {
            Screen.LostFoundBrowse
        }
        is Screen.CreateLostFoundReport -> Screen.LostFoundHome
        Screen.LostFoundClaims -> Screen.LostFoundHome
    }

    fun goBack(): NavigationState? = backTarget()?.let(::navigate)
}

internal val NavigationStateSaver = listSaver<NavigationState, String>(
    save = { state ->
        listOf(
            state.screen.routeKey(),
            state.servicesReturnScreen.routeKey(),
            state.bookingDetailsReturnScreen.routeKey(),
            state.maintenanceDetailsReturnScreen.routeKey(),
        )
    },
    restore = { saved ->
        NavigationState(
            screen = screenFromRouteKey(saved.getOrNull(0)),
            servicesReturnScreen = screenFromRouteKey(saved.getOrNull(1)),
            bookingDetailsReturnScreen = screenFromRouteKey(saved.getOrNull(2)),
            maintenanceDetailsReturnScreen = screenFromRouteKey(saved.getOrNull(3)),
        )
    },
)

@Composable
internal fun NavigationBackHandler(state: NavigationState, onBack: (NavigationState) -> Unit) {
    val target = state.backTarget()
    BackHandler(enabled = target != null) {
        target?.let { onBack(state.navigate(it)) }
    }
}

internal fun Screen.routeKey(): String = when (this) {
    Screen.Home -> "home"
    Screen.Conversations -> "conversations"
    is Screen.Chat -> "chat|$id"
    Screen.Profile -> "profile"
    Screen.Settings -> "settings"
    Screen.Services -> "services"
    Screen.FacilitiesHome -> "facilities-home"
    Screen.FacilitiesSearch -> "facilities-search"
    is Screen.SpaceDetails -> "space-details|$spaceId"
    Screen.MyBookings -> "my-bookings"
    is Screen.BookingDetails -> "booking-details|$bookingId"
    is Screen.SubmitMaintenance -> "submit-maintenance|${preselectedSpaceId.orEmpty()}"
    Screen.MyMaintenance -> "my-maintenance"
    is Screen.MaintenanceDetails -> "maintenance-details|$ticketId"
    Screen.LostFoundHome -> "lost-found-home"
    Screen.LostFoundBrowse -> "lost-found-browse"
    is Screen.LostFoundDetails -> "lost-found-details|$reportId|$returnToClaims"
    is Screen.CreateLostFoundReport -> "create-lost-found|${reportType.name}"
    Screen.LostFoundClaims -> "lost-found-claims"
}

internal fun screenFromRouteKey(routeKey: String?): Screen {
    val parts = routeKey.orEmpty().split('|')
    return when (parts.firstOrNull()) {
        "home" -> Screen.Home
        "conversations" -> Screen.Conversations
        "chat" -> parts.getOrNull(1)?.takeIf(String::isNotBlank)?.let(Screen::Chat)
        "profile" -> Screen.Profile
        "settings" -> Screen.Settings
        "services" -> Screen.Services
        "facilities-home" -> Screen.FacilitiesHome
        "facilities-search" -> Screen.FacilitiesSearch
        "space-details" -> parts.longArgument(1)?.let(Screen::SpaceDetails)
        "my-bookings" -> Screen.MyBookings
        "booking-details" -> parts.longArgument(1)?.let(Screen::BookingDetails)
        "submit-maintenance" -> Screen.SubmitMaintenance(parts.longArgument(1))
        "my-maintenance" -> Screen.MyMaintenance
        "maintenance-details" -> parts.longArgument(1)?.let(Screen::MaintenanceDetails)
        "lost-found-home" -> Screen.LostFoundHome
        "lost-found-browse" -> Screen.LostFoundBrowse
        "lost-found-details" -> parts.longArgument(1)?.let { reportId ->
            Screen.LostFoundDetails(reportId, parts.getOrNull(2).toBoolean())
        }
        "create-lost-found" -> parts.getOrNull(1)?.let { name ->
            runCatching { ReportType.valueOf(name) }.getOrNull()
        }?.let(Screen::CreateLostFoundReport)
        "lost-found-claims" -> Screen.LostFoundClaims
        else -> null
    } ?: Screen.Home
}

private fun Long?.orEmpty(): String = this?.toString().orEmpty()

private fun List<String>.longArgument(index: Int): Long? = getOrNull(index)?.toLongOrNull()
