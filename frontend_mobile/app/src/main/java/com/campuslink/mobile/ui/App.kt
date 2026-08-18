@file:Suppress("LongParameterList", "LongMethod")

package com.campuslink.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.campuslink.mobile.AppContainer
import com.campuslink.mobile.BuildConfig
import com.campuslink.mobile.core.model.AuthSession
import com.campuslink.mobile.core.model.ReportType
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
import com.campuslink.mobile.ui.lostfound.CreateLostFoundReportScreen
import com.campuslink.mobile.ui.lostfound.CreateLostFoundReportViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundBrowseScreen
import com.campuslink.mobile.ui.lostfound.LostFoundBrowseViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundClaimsScreen
import com.campuslink.mobile.ui.lostfound.LostFoundClaimsViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundDetailsScreen
import com.campuslink.mobile.ui.lostfound.LostFoundDetailsViewModel
import com.campuslink.mobile.ui.lostfound.LostFoundHomeScreen
import com.campuslink.mobile.ui.mail.CalendarScreen
import com.campuslink.mobile.ui.mail.CalendarViewModel
import com.campuslink.mobile.ui.mail.ComposeMailScreen
import com.campuslink.mobile.ui.mail.ComposeMailViewModel
import com.campuslink.mobile.ui.mail.MailDetailsScreen
import com.campuslink.mobile.ui.mail.MailDetailsViewModel
import com.campuslink.mobile.ui.mail.MailHomeScreen
import com.campuslink.mobile.ui.mail.MailHomeViewModel
import kotlinx.coroutines.launch

@Composable
fun CampusLinkApp(container: AppContainer) {
    val session by container.sessionStore.session.collectAsStateWithLifecycle()
    val language by container.settings.language.collectAsStateWithLifecycle()
    val dark by container.settings.dark.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    CampusLinkTheme(darkTheme = dark) {
        if (session == null) {
            AuthRoute(container, language)
        } else {
            var navigation by rememberSaveable(
                session!!.email,
                stateSaver = NavigationStateSaver,
            ) {
                mutableStateOf(NavigationState())
            }
            val navigate: (Screen) -> Unit = { navigation = navigation.navigate(it) }
            val goBack: () -> Unit = { navigation.goBack()?.let { navigation = it } }
            NavigationBackHandler(navigation) { navigation = it }
            when (val active = navigation.screen) {
                Screen.Home, Screen.Conversations, Screen.Profile -> RootTabRoute(
                    active = active,
                    container = container,
                    state = RootTabUiState(session!!, language, dark),
                    actions = RootTabActions(
                        navigate = navigate,
                        clearHistory = {
                            scope.launch { container.chatRepository.clearForUser(session!!.email) }
                        },
                    ),
                )
                is Screen.Chat ->
                    ChatRoute(
                        container = container,
                        conversationId = active.id,
                        accountKey = session!!.email,
                        text = strings(language),
                        onBack = goBack,
                        onOpenMatch = { navigate(Screen.LostFoundDetails(it, returnToChatId = active.id)) },
                    )
                Screen.Settings -> SettingsScreen(
                    container = container,
                    text = strings(language),
                    onBack = goBack,
                    onClear = {
                        scope.launch { container.chatRepository.clearForUser(session!!.email) }
                    },
                )
                Screen.Services -> ServicesRoute(goBack, navigate)
                Screen.MailHome -> MailHomeRoute(container, session!!.email, strings(language).mail, goBack, navigate)
                is Screen.MailDetails -> MailDetailsRoute(
                    container,
                    active.messageId,
                    session!!.email,
                    strings(language).mail,
                    goBack,
                    navigate,
                )
                Screen.ComposeMail -> ComposeMailRoute(container, session!!.email, strings(language).mail, goBack)
                Screen.Calendar -> CalendarRoute(container, session!!.email, strings(language).calendar, goBack, navigate)
                Screen.FacilitiesHome -> FacilitiesHomeRoute(goBack, navigate)
                Screen.FacilitiesSearch -> FacilitiesSearchRoute(container, session!!.email, goBack, navigate)
                is Screen.SpaceDetails -> SpaceDetailsRoute(container, active.spaceId, session!!.email, goBack, navigate) {
                    navigation = navigation.openBookingDetails(it, Screen.MyBookings)
                }
                Screen.MyBookings -> MyBookingsRoute(container, session!!.email, goBack) {
                    navigation = navigation.openBookingDetails(it, Screen.MyBookings)
                }
                is Screen.BookingDetails -> BookingDetailsRoute(container, active.bookingId, session!!.email, goBack)
                is Screen.SubmitMaintenance -> SubmitMaintenanceRoute(
                    container,
                    active.preselectedSpaceId,
                    session!!.email,
                    onBack = goBack,
                    navigate = navigate,
                    onViewRequest = {
                        navigation = navigation.openMaintenanceDetails(it, active)
                    },
                )
                Screen.MyMaintenance -> MyMaintenanceRoute(container, session!!.email, goBack) {
                    navigation = navigation.openMaintenanceDetails(it, Screen.MyMaintenance)
                }
                is Screen.MaintenanceDetails -> MaintenanceDetailsRoute(container, active.ticketId, session!!.email, goBack)
                Screen.LostFoundHome -> LostFoundHomeScreen(
                    onBack = goBack,
                    onBrowse = { navigate(Screen.LostFoundBrowse) },
                    onCreate = { navigate(Screen.CreateLostFoundReport(it)) },
                    onClaims = { navigate(Screen.LostFoundClaims) },
                )
                Screen.LostFoundBrowse -> LostFoundBrowseRoute(container, session!!.email, goBack, navigate)
                is Screen.LostFoundDetails -> LostFoundDetailsRoute(container, active.reportId, session!!.email, goBack)
                is Screen.CreateLostFoundReport -> CreateLostFoundReportRoute(
                    container = container,
                    reportType = active.reportType,
                    accountKey = session!!.email,
                    onBack = goBack,
                    onCreated = { navigate(Screen.LostFoundDetails(it)) },
                )
                Screen.LostFoundClaims -> LostFoundClaimsRoute(container, session!!.email, goBack, navigate)
            }
        }
    }
}

private data class RootTabUiState(
    val session: AuthSession,
    val language: AppLanguage,
    val dark: Boolean,
)

private data class RootTabActions(
    val navigate: (Screen) -> Unit,
    val clearHistory: () -> Unit,
)

@Composable
private fun RootTabRoute(
    active: Screen,
    container: AppContainer,
    state: RootTabUiState,
    actions: RootTabActions,
) {
    val tab = when (active) {
        Screen.Home -> AppTab.HOME
        Screen.Conversations -> AppTab.AGENT_CORE
        Screen.Profile -> AppTab.PROFILE
        else -> return
    }
    val text = strings(state.language)
    CampusLinkShell(
        selectedTab = tab,
        text = text.shell,
        onTabSelected = { actions.navigate(it.screen()) },
    ) {
        when (active) {
            Screen.Home -> HomeScreen(
                actions = HomeActions(
                    openAgentCore = { actions.navigate(Screen.Conversations) },
                    openFacilities = { actions.navigate(Screen.FacilitiesHome) },
                    openLostFound = { actions.navigate(Screen.LostFoundHome) },
                    openMyBookings = { actions.navigate(Screen.MyBookings) },
                        openMyMaintenance = { actions.navigate(Screen.MyMaintenance) },
                        openMyClaims = { actions.navigate(Screen.LostFoundClaims) },
                        openMail = { actions.navigate(Screen.MailHome) },
                ),
                text = text.home,
            )
            Screen.Conversations -> ConversationListRoute(
                container = container,
                email = state.session.email,
                text = text,
                navigate = actions.navigate,
            )
            Screen.Profile -> ProfileScreen(
                state = ProfileUiState(
                    state.session.email,
                    state.session.role,
                    BuildConfig.VERSION_NAME,
                    state.language,
                    state.dark,
                ),
                actions = ProfileActions(
                    changeLanguage = container.settings::setLanguage,
                    changeDark = container.settings::setDark,
                    clearHistory = actions.clearHistory,
                    logout = container.sessionStore::clear,
                ),
                text = text.profile,
            )
            else -> Unit
        }
    }
}

@Composable
private fun ConversationListRoute(
    container: AppContainer,
    email: String,
    text: UiStrings,
    navigate: (Screen) -> Unit,
) {
    val viewModel: ConversationListViewModel = viewModel(
        key = "conversations-$email",
        factory = ContainerViewModelFactory { ConversationListViewModel(container, email) },
    )
    ConversationListScreen(
        viewModel,
        text,
        onOpen = { navigate(Screen.Chat(it)) },
    )
}

@Composable
private fun ChatRoute(
    container: AppContainer,
    conversationId: String,
    accountKey: String,
    text: UiStrings,
    onBack: () -> Unit,
    onOpenMatch: (Long) -> Unit,
) {
    val viewModel: ChatViewModel = viewModel(
        key = sessionViewModelKey("chat-$conversationId", accountKey),
        factory = ContainerViewModelFactory { ChatViewModel(container, conversationId) },
    )
    ChatScreen(viewModel, text, onBack, onOpenMatch)
}

@Composable
private fun ServicesRoute(onBack: () -> Unit, navigate: (Screen) -> Unit) {
    ServicesScreen(
        onBack = onBack,
        onFacilities = { navigate(Screen.FacilitiesHome) },
        onLostFound = { navigate(Screen.LostFoundHome) },
        onMail = { navigate(Screen.MailHome) },
    )
}

@Composable
private fun MailHomeRoute(
    container: AppContainer,
    accountKey: String,
    text: MailStrings,
    onBack: () -> Unit,
    navigate: (Screen) -> Unit,
) {
    val context = LocalContext.current
    val viewModel: MailHomeViewModel = viewModel(
        key = sessionViewModelKey("mail-home", accountKey),
        factory = ContainerViewModelFactory { MailHomeViewModel(container.mailRepository) },
    )
    MailHomeScreen(
        viewModel = viewModel,
        text = text,
        onBack = onBack,
        onOpenMessage = { navigate(Screen.MailDetails(it)) },
        onCompose = { navigate(Screen.ComposeMail) },
        onCalendar = { navigate(Screen.Calendar) },
        openAuthorization = { url ->
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        },
    )
}

@Composable
private fun MailDetailsRoute(
    container: AppContainer,
    messageId: String,
    accountKey: String,
    text: MailStrings,
    onBack: () -> Unit,
    navigate: (Screen) -> Unit,
) {
    val viewModel: MailDetailsViewModel = viewModel(
        key = sessionViewModelKey("mail-details-$messageId", accountKey),
        factory = ContainerViewModelFactory { MailDetailsViewModel(messageId, container.mailRepository) },
    )
    MailDetailsScreen(viewModel, text, onBack, onOpenCalendar = { navigate(Screen.Calendar) })
}

@Composable
private fun ComposeMailRoute(container: AppContainer, accountKey: String, text: MailStrings, onBack: () -> Unit) {
    val viewModel: ComposeMailViewModel = viewModel(
        key = sessionViewModelKey("compose-mail", accountKey),
        factory = ContainerViewModelFactory { ComposeMailViewModel(container.mailRepository) },
    )
    ComposeMailScreen(viewModel, text, onBack)
}

@Composable
private fun CalendarRoute(
    container: AppContainer,
    accountKey: String,
    text: CalendarStrings,
    onBack: () -> Unit,
    navigate: (Screen) -> Unit,
) {
    val viewModel: CalendarViewModel = viewModel(
        key = sessionViewModelKey("calendar", accountKey),
        factory = ContainerViewModelFactory { CalendarViewModel(container.mailRepository) },
    )
    CalendarScreen(viewModel, text, onBack, onOpenSourceEmail = { navigate(Screen.MailDetails(it)) })
}

@Composable
private fun FacilitiesHomeRoute(onBack: () -> Unit, navigate: (Screen) -> Unit) {
    FacilitiesHomeScreen(
        onBack = onBack,
        onSearchSpaces = { navigate(Screen.FacilitiesSearch) },
        onMyBookings = { navigate(Screen.MyBookings) },
        onReportMaintenance = { navigate(Screen.SubmitMaintenance()) },
        onMyMaintenance = { navigate(Screen.MyMaintenance) },
    )
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
private fun LostFoundBrowseRoute(
    container: AppContainer,
    accountKey: String,
    onBack: () -> Unit,
    navigate: (Screen) -> Unit,
) {
    val viewModel: LostFoundBrowseViewModel = viewModel(
        key = sessionViewModelKey("lost-found-browse", accountKey),
        factory = ContainerViewModelFactory { LostFoundBrowseViewModel(container.lostFoundRepository) },
    )
    LostFoundBrowseScreen(
        viewModel = viewModel,
        onBack = onBack,
        onOpenReport = { navigate(Screen.LostFoundDetails(it)) },
    )
}

@Composable
private fun LostFoundDetailsRoute(container: AppContainer, reportId: Long, accountKey: String, onBack: () -> Unit) {
    val viewModel: LostFoundDetailsViewModel = viewModel(
        key = sessionViewModelKey("lost-found-details-$reportId", accountKey),
        factory = ContainerViewModelFactory { LostFoundDetailsViewModel(reportId, container.lostFoundRepository) },
    )
    LostFoundDetailsScreen(viewModel = viewModel, onBack = onBack)
}

@Composable
private fun CreateLostFoundReportRoute(
    container: AppContainer,
    reportType: ReportType,
    accountKey: String,
    onBack: () -> Unit,
    onCreated: (Long) -> Unit,
) {
    val viewModel: CreateLostFoundReportViewModel = viewModel(
        key = sessionViewModelKey("create-lost-found-${reportType.name}", accountKey),
        factory = ContainerViewModelFactory {
            CreateLostFoundReportViewModel(reportType, container.lostFoundRepository)
        },
    )
    CreateLostFoundReportScreen(reportType, viewModel, onBack, onCreated)
}

@Composable
private fun LostFoundClaimsRoute(
    container: AppContainer,
    accountKey: String,
    onBack: () -> Unit,
    navigate: (Screen) -> Unit,
) {
    val viewModel: LostFoundClaimsViewModel = viewModel(
        key = sessionViewModelKey("lost-found-claims", accountKey),
        factory = ContainerViewModelFactory { LostFoundClaimsViewModel(container.lostFoundRepository) },
    )
    LostFoundClaimsScreen(
        viewModel = viewModel,
        onBack = onBack,
        onOpenReport = { navigate(Screen.LostFoundDetails(it, returnToClaims = true)) },
    )
}

@Composable
private fun FacilitiesSearchRoute(
    container: AppContainer,
    accountKey: String,
    onBack: () -> Unit,
    navigate: (Screen) -> Unit,
) {
    val viewModel: SpaceSearchViewModel = viewModel(
        key = sessionViewModelKey("facilities-search", accountKey),
        factory = ContainerViewModelFactory { SpaceSearchViewModel(container.facilitiesRepository) },
    )
    SpaceSearchScreen(
        viewModel = viewModel,
        onBack = onBack,
        onOpenSpace = { navigate(Screen.SpaceDetails(it)) },
    )
}

@Composable
@Suppress("LongParameterList")
private fun SpaceDetailsRoute(
    container: AppContainer,
    spaceId: Long,
    accountKey: String,
    onBack: () -> Unit,
    navigate: (Screen) -> Unit,
    onViewBooking: (Long) -> Unit,
) {
    val viewModel: SpaceDetailsViewModel = viewModel(
        key = sessionViewModelKey("space-details-$spaceId", accountKey),
        factory = ContainerViewModelFactory { SpaceDetailsViewModel(spaceId, container.facilitiesRepository) },
    )
    SpaceDetailsScreen(
        viewModel = viewModel,
        onBack = onBack,
        onViewBooking = onViewBooking,
        onMyBookings = { navigate(Screen.MyBookings) },
        onReportIssue = { navigate(Screen.SubmitMaintenance(it)) },
    )
}

@Composable
private fun MyBookingsRoute(
    container: AppContainer,
    accountKey: String,
    onBack: () -> Unit,
    onOpenBooking: (Long) -> Unit,
) {
    val viewModel: MyBookingsViewModel = viewModel(
        key = sessionViewModelKey("my-bookings", accountKey),
        factory = ContainerViewModelFactory { MyBookingsViewModel(container.facilitiesRepository) },
    )
    MyBookingsScreen(
        viewModel = viewModel,
        onBack = onBack,
        onOpenBooking = onOpenBooking,
    )
}

@Composable
private fun BookingDetailsRoute(container: AppContainer, bookingId: Long, accountKey: String, onBack: () -> Unit) {
    val viewModel: BookingDetailsViewModel = viewModel(
        key = sessionViewModelKey("booking-details-$bookingId", accountKey),
        factory = ContainerViewModelFactory { BookingDetailsViewModel(bookingId, container.facilitiesRepository) },
    )
    BookingDetailsScreen(viewModel = viewModel, onBack = onBack)
}

@Composable
@Suppress("LongParameterList")
private fun SubmitMaintenanceRoute(
    container: AppContainer,
    preselectedSpaceId: Long?,
    accountKey: String,
    onBack: () -> Unit,
    navigate: (Screen) -> Unit,
    onViewRequest: (Long) -> Unit,
) {
    val viewModel: SubmitMaintenanceViewModel = viewModel(
        key = sessionViewModelKey("submit-maintenance-${preselectedSpaceId ?: "none"}", accountKey),
        factory = ContainerViewModelFactory {
            SubmitMaintenanceViewModel(container.facilitiesRepository, preselectedSpaceId)
        },
    )
    SubmitMaintenanceScreen(
        viewModel = viewModel,
        onBack = onBack,
        onViewRequest = onViewRequest,
        onMyMaintenance = { navigate(Screen.MyMaintenance) },
    )
}

@Composable
private fun MyMaintenanceRoute(
    container: AppContainer,
    accountKey: String,
    onBack: () -> Unit,
    onOpenRequest: (Long) -> Unit,
) {
    val viewModel: MyMaintenanceViewModel = viewModel(
        key = sessionViewModelKey("my-maintenance", accountKey),
        factory = ContainerViewModelFactory { MyMaintenanceViewModel(container.facilitiesRepository) },
    )
    MyMaintenanceScreen(
        viewModel = viewModel,
        onBack = onBack,
        onOpenRequest = onOpenRequest,
    )
}

@Composable
private fun MaintenanceDetailsRoute(container: AppContainer, ticketId: Long, accountKey: String, onBack: () -> Unit) {
    val viewModel: MaintenanceDetailsViewModel = viewModel(
        key = sessionViewModelKey("maintenance-details-$ticketId", accountKey),
        factory = ContainerViewModelFactory {
            MaintenanceDetailsViewModel(ticketId, container.facilitiesRepository)
        },
    )
    MaintenanceDetailsScreen(viewModel = viewModel, onBack = onBack)
}

internal fun sessionViewModelKey(routeKey: String, accountKey: String): String = "$routeKey-$accountKey"
