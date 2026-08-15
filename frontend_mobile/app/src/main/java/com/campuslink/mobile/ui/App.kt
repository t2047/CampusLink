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
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object Conversations : Screen
    data class Chat(val id: String) : Screen
    data object Settings : Screen
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
        } else {
            var screen: Screen by remember(session!!.email) { mutableStateOf(Screen.Conversations) }
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
                    )
                }
                is Screen.Chat -> {
                    val chat: ChatViewModel = viewModel(
                        key = "chat-${active.id}",
                        factory = ContainerViewModelFactory { ChatViewModel(container, active.id) },
                    )
                    ChatScreen(chat, strings(language), onBack = { screen = Screen.Conversations })
                }
                Screen.Settings -> SettingsScreen(
                    container = container,
                    text = strings(language),
                    onBack = { screen = Screen.Conversations },
                    onClear = {
                        scope.launch { container.chatRepository.clearForUser(session!!.email) }
                    },
                )
            }
        }
    }
}
