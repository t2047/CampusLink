package com.campuslink.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campuslink.mobile.AppContainer
import com.campuslink.mobile.core.settings.AppLanguage
import com.campuslink.mobile.ui.facilities.FacilitiesHomeScreen
import com.campuslink.mobile.ui.facilities.BookingDetailsScreen
import com.campuslink.mobile.ui.facilities.BookingDetailsViewModel
import com.campuslink.mobile.ui.facilities.MyBookingsScreen
import com.campuslink.mobile.ui.facilities.MyBookingsViewModel
import com.campuslink.mobile.ui.facilities.SubmitMaintenanceScreen
import com.campuslink.mobile.ui.facilities.SubmitMaintenanceViewModel
import com.campuslink.mobile.ui.facilities.MyMaintenanceScreen
import com.campuslink.mobile.ui.facilities.MyMaintenanceViewModel
import com.campuslink.mobile.ui.facilities.MaintenanceDetailsScreen
import com.campuslink.mobile.ui.facilities.MaintenanceDetailsViewModel
import com.campuslink.mobile.ui.facilities.SpaceDetailsScreen
import com.campuslink.mobile.ui.facilities.SpaceDetailsViewModel
import com.campuslink.mobile.ui.facilities.SpaceSearchScreen
import com.campuslink.mobile.ui.facilities.SpaceSearchViewModel
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Conversations : Screen
    data class Chat(val id: String) : Screen
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
}

@Composable
fun CampusLinkApp(container: AppContainer) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle()
    val language by container.settings.language.collectAsStateWithLifecycle()
    val dark by container.settings.dark.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val palette = if (dark) darkColorScheme() else lightColorScheme()

    MaterialTheme(colorScheme = palette) {
        if (session == null) {
            AuthRoute(container, language)
        } else {
            var screen: Screen by remember(session!!.email) { mutableStateOf(Screen.Conversations) }
            var servicesReturnScreen: Screen by remember(session!!.email) { mutableStateOf(Screen.Conversations) }
            var bookingDetailsReturnScreen: Screen by remember(session!!.email) { mutableStateOf(Screen.MyBookings) }
            var maintenanceDetailsReturnScreen: Screen by remember(session!!.email) {
                mutableStateOf(Screen.MyMaintenance)
            }
            when (val active = screen) {
                Screen.Conversations -> {
                    val list: ConversationListViewModel = viewModel(
                        key = "conversations-${session!!.email}",
                        factory = ContainerViewModelFactory { ConversationListViewModel(container, session!!.email) },
                    )
                    ConversationListScreen(
                        viewModel = list,
                        text = strings(language),
                        onOpen = { screen = Screen.Chat(it) },
                        onSettings = { screen = Screen.Settings },
                        onServices = {
                            servicesReturnScreen = Screen.Conversations
                            screen = Screen.Services
                        },
                    )
                }
                is Screen.Chat -> {
                    val chat: ChatViewModel = viewModel(
                        key = "chat-${active.id}",
                        factory = ContainerViewModelFactory { ChatViewModel(container, active.id) },
                    )
                    ChatScreen(
                        chat,
                        strings(language),
                        onBack = { screen = Screen.Conversations },
                        onServices = {
                            servicesReturnScreen = active
                            screen = Screen.Services
                        },
                    )
                }
                Screen.Settings -> SettingsScreen(
                    container = container,
                    text = strings(language),
                    onBack = { screen = Screen.Conversations },
                    onClear = {
                        scope.launch { container.chatRepository.clearForUser(session!!.email) }
                    },
                )
                Screen.Services -> ServicesScreen(
                    onBack = { screen = servicesReturnScreen },
                    onFacilities = { screen = Screen.FacilitiesHome },
                )
                Screen.FacilitiesHome -> FacilitiesHomeScreen(
                    onBack = { screen = Screen.Services },
                    onSearchSpaces = { screen = Screen.FacilitiesSearch },
                    onMyBookings = { screen = Screen.MyBookings },
                    onReportMaintenance = { screen = Screen.SubmitMaintenance() },
                    onMyMaintenance = { screen = Screen.MyMaintenance },
                )
                Screen.FacilitiesSearch -> FacilitiesSearchRoute(container) { screen = it }
                is Screen.SpaceDetails -> SpaceDetailsRoute(container, active.spaceId, { screen = it }) {
                    bookingDetailsReturnScreen = Screen.MyBookings
                    screen = Screen.BookingDetails(it)
                }
                Screen.MyBookings -> MyBookingsRoute(container, { screen = it }) {
                    bookingDetailsReturnScreen = Screen.MyBookings
                    screen = Screen.BookingDetails(it)
                }
                is Screen.BookingDetails -> BookingDetailsRoute(container, active.bookingId) {
                    screen = bookingDetailsReturnScreen
                }
                is Screen.SubmitMaintenance -> SubmitMaintenanceRoute(
                    container,
                    active.preselectedSpaceId,
                    navigate = { screen = it },
                    onViewRequest = {
                        maintenanceDetailsReturnScreen = active
                        screen = Screen.MaintenanceDetails(it)
                    },
                )
                Screen.MyMaintenance -> MyMaintenanceRoute(container, navigate = { screen = it }) {
                    maintenanceDetailsReturnScreen = Screen.MyMaintenance
                    screen = Screen.MaintenanceDetails(it)
                }
                is Screen.MaintenanceDetails -> MaintenanceDetailsRoute(container, active.ticketId) {
                    screen = maintenanceDetailsReturnScreen
                }
            }
        }
    }
}

@Composable
private fun FacilitiesSearchRoute(container: AppContainer, navigate: (Screen) -> Unit) {
    val viewModel: SpaceSearchViewModel = viewModel(
        key = "facilities-search",
        factory = ContainerViewModelFactory { SpaceSearchViewModel(container.facilitiesRepository) },
    )
    SpaceSearchScreen(
        viewModel = viewModel,
        onBack = { navigate(Screen.FacilitiesHome) },
        onOpenSpace = { navigate(Screen.SpaceDetails(it)) },
    )
}

@Composable
private fun SpaceDetailsRoute(
    container: AppContainer,
    spaceId: Long,
    navigate: (Screen) -> Unit,
    onViewBooking: (Long) -> Unit,
) {
    val viewModel: SpaceDetailsViewModel = viewModel(
        key = "space-details-$spaceId",
        factory = ContainerViewModelFactory { SpaceDetailsViewModel(spaceId, container.facilitiesRepository) },
    )
    SpaceDetailsScreen(
        viewModel = viewModel,
        onBack = { navigate(Screen.FacilitiesSearch) },
        onViewBooking = onViewBooking,
        onMyBookings = { navigate(Screen.MyBookings) },
        onReportIssue = { navigate(Screen.SubmitMaintenance(it)) },
    )
}

@Composable
private fun MyBookingsRoute(
    container: AppContainer,
    navigate: (Screen) -> Unit,
    onOpenBooking: (Long) -> Unit,
) {
    val viewModel: MyBookingsViewModel = viewModel(
        key = "my-bookings",
        factory = ContainerViewModelFactory { MyBookingsViewModel(container.facilitiesRepository) },
    )
    MyBookingsScreen(
        viewModel = viewModel,
        onBack = { navigate(Screen.FacilitiesHome) },
        onOpenBooking = onOpenBooking,
    )
}

@Composable
private fun BookingDetailsRoute(container: AppContainer, bookingId: Long, onBack: () -> Unit) {
    val viewModel: BookingDetailsViewModel = viewModel(
        key = "booking-details-$bookingId",
        factory = ContainerViewModelFactory { BookingDetailsViewModel(bookingId, container.facilitiesRepository) },
    )
    BookingDetailsScreen(viewModel = viewModel, onBack = onBack)
}

@Composable
private fun AuthRoute(container: AppContainer, language: AppLanguage) {
    val auth: AuthViewModel = viewModel(factory = ContainerViewModelFactory { AuthViewModel(container) })
    AuthScreen(
        viewModel = auth,
        text = strings(language),
        onToggleLanguage = {
            container.settings.setLanguage(
                if (language == AppLanguage.ENGLISH) AppLanguage.CHINESE else AppLanguage.ENGLISH,
            )
        },
    )
}

@Composable
private fun SubmitMaintenanceRoute(
    container: AppContainer,
    preselectedSpaceId: Long?,
    navigate: (Screen) -> Unit,
    onViewRequest: (Long) -> Unit,
) {
    val viewModel: SubmitMaintenanceViewModel = viewModel(
        key = "submit-maintenance-${preselectedSpaceId ?: "none"}",
        factory = ContainerViewModelFactory {
            SubmitMaintenanceViewModel(container.facilitiesRepository, preselectedSpaceId)
        },
    )
    SubmitMaintenanceScreen(
        viewModel = viewModel,
        onBack = {
            navigate(preselectedSpaceId?.let(Screen::SpaceDetails) ?: Screen.FacilitiesHome)
        },
        onViewRequest = onViewRequest,
        onMyMaintenance = { navigate(Screen.MyMaintenance) },
    )
}

@Composable
private fun MyMaintenanceRoute(
    container: AppContainer,
    navigate: (Screen) -> Unit,
    onOpenRequest: (Long) -> Unit,
) {
    val viewModel: MyMaintenanceViewModel = viewModel(
        key = "my-maintenance",
        factory = ContainerViewModelFactory { MyMaintenanceViewModel(container.facilitiesRepository) },
    )
    MyMaintenanceScreen(
        viewModel = viewModel,
        onBack = { navigate(Screen.FacilitiesHome) },
        onOpenRequest = onOpenRequest,
    )
}

@Composable
private fun MaintenanceDetailsRoute(container: AppContainer, ticketId: Long, onBack: () -> Unit) {
    val viewModel: MaintenanceDetailsViewModel = viewModel(
        key = "maintenance-details-$ticketId",
        factory = ContainerViewModelFactory {
            MaintenanceDetailsViewModel(ticketId, container.facilitiesRepository)
        },
    )
    MaintenanceDetailsScreen(viewModel = viewModel, onBack = onBack)
}
