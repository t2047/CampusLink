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
import com.campuslink.mobile.ui.facilities.SpaceDetailsScreen
import com.campuslink.mobile.ui.facilities.SpaceDetailsViewModel
import com.campuslink.mobile.ui.facilities.SpaceSearchScreen
import com.campuslink.mobile.ui.facilities.SpaceSearchViewModel
import com.campuslink.mobile.core.model.ReportType
import com.campuslink.mobile.ui.lostfound.CreateLostFoundReportScreen
import com.campuslink.mobile.ui.lostfound.CreateLostFoundReportViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundBrowseScreen
import com.campuslink.mobile.ui.lostfound.LostFoundBrowseViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundClaimsScreen
import com.campuslink.mobile.ui.lostfound.LostFoundClaimsViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundDetailsScreen
import com.campuslink.mobile.ui.lostfound.LostFoundDetailsViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundHomeScreen
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
    data object LostFoundHome : Screen
    data object LostFoundBrowse : Screen
    data class LostFoundDetails(val reportId: Long, val returnToClaims: Boolean = false) : Screen
    data class CreateLostFoundReport(val reportType: ReportType) : Screen
    data object LostFoundClaims : Screen
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
                    onLostFound = { screen = Screen.LostFoundHome },
                )
                Screen.FacilitiesHome -> FacilitiesHomeScreen(
                    onBack = { screen = Screen.Services },
                    onSearchSpaces = { screen = Screen.FacilitiesSearch },
                    onMyBookings = { screen = Screen.MyBookings },
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
                Screen.LostFoundHome -> LostFoundHomeScreen(
                    onBack = { screen = Screen.Services },
                    onBrowse = { screen = Screen.LostFoundBrowse },
                    onCreate = { screen = Screen.CreateLostFoundReport(it) },
                    onClaims = { screen = Screen.LostFoundClaims },
                )
                Screen.LostFoundBrowse -> LostFoundBrowseRoute(container) { screen = it }
                is Screen.LostFoundDetails -> LostFoundDetailsRoute(container, active.reportId) {
                    screen = if (active.returnToClaims) Screen.LostFoundClaims else Screen.LostFoundBrowse
                }
                is Screen.CreateLostFoundReport -> CreateLostFoundReportRoute(
                    container = container,
                    reportType = active.reportType,
                    onBack = { screen = Screen.LostFoundHome },
                    onCreated = { screen = Screen.LostFoundDetails(it) },
                )
                Screen.LostFoundClaims -> LostFoundClaimsRoute(container) { screen = it }
            }
        }
    }
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
private fun LostFoundBrowseRoute(container: AppContainer, navigate: (Screen) -> Unit) {
    val viewModel: LostFoundBrowseViewModel = viewModel(
        key = "lost-found-browse",
        factory = ContainerViewModelFactory { LostFoundBrowseViewModel(container.lostFoundRepository) },
    )
    LostFoundBrowseScreen(
        viewModel = viewModel,
        onBack = { navigate(Screen.LostFoundHome) },
        onOpenReport = { navigate(Screen.LostFoundDetails(it)) },
    )
}

@Composable
private fun LostFoundDetailsRoute(container: AppContainer, reportId: Long, onBack: () -> Unit) {
    val viewModel: LostFoundDetailsViewModel = viewModel(
        key = "lost-found-details-$reportId",
        factory = ContainerViewModelFactory { LostFoundDetailsViewModel(reportId, container.lostFoundRepository) },
    )
    LostFoundDetailsScreen(viewModel = viewModel, onBack = onBack)
}

@Composable
private fun CreateLostFoundReportRoute(
    container: AppContainer,
    reportType: ReportType,
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
) {
    val viewModel: CreateLostFoundReportViewModel = viewModel(
        key = "create-lost-found-${reportType.name}",
        factory = ContainerViewModelFactory {
            CreateLostFoundReportViewModel(reportType, container.lostFoundRepository)
        },
    )
    CreateLostFoundReportScreen(reportType, viewModel, onBack, onCreated)
}

@Composable
private fun LostFoundClaimsRoute(container: AppContainer, navigate: (Screen) -> Unit) {
    val viewModel: LostFoundClaimsViewModel = viewModel(
        key = "lost-found-claims",
        factory = ContainerViewModelFactory { LostFoundClaimsViewModel(container.lostFoundRepository) },
    )
    LostFoundClaimsScreen(
        viewModel = viewModel,
        onBack = { navigate(Screen.LostFoundHome) },
        onOpenReport = { navigate(Screen.LostFoundDetails(it, returnToClaims = true)) },
    )
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
