package com.campuslink.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.campuslink.mobile.AppContainer
import com.campuslink.mobile.core.model.AuthSession
import com.campuslink.mobile.core.model.PendingConfirmation
import com.campuslink.mobile.core.network.ApiException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class AuthUiState(
    val registering: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(private val container: AppContainer) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = mutableState.asStateFlow()

    fun toggleMode() {
        mutableState.value = mutableState.value.copy(registering = !mutableState.value.registering, error = null)
    }

    fun submit(email: String, password: String, confirm: String) {
        val current = mutableState.value
        if (email.isBlank() || !email.contains('@')) {
            mutableState.value = current.copy(error = "Please enter a valid email")
            return
        }
        if (password.length < 6) {
            mutableState.value = current.copy(error = "Password must contain at least 6 characters")
            return
        }
        if (current.registering && password != confirm) {
            mutableState.value = current.copy(error = "Passwords do not match")
            return
        }
        viewModelScope.launch {
            mutableState.value = current.copy(loading = true, error = null)
            runCatching {
                if (current.registering) container.authApi.register(email, password)
                else container.authApi.login(email, password)
            }.onSuccess {
                container.sessionStore.save(AuthSession(it.token, it.email, it.role))
                mutableState.value = current.copy(loading = false)
            }.onFailure {
                mutableState.value = current.copy(
                    loading = false,
                    error = if (it is ApiException) it.message else "Network unavailable",
                )
            }
        }
    }
}

class ConversationListViewModel(private val container: AppContainer, private val email: String) : ViewModel() {
    val conversations = container.chatRepository.conversations(email)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch { onCreated(container.chatRepository.createConversation(email)) }
    }

    fun delete(id: String) {
        viewModelScope.launch { container.chatRepository.deleteConversation(id, email) }
    }

    fun clear() {
        viewModelScope.launch { container.chatRepository.clearForUser(email) }
    }
}

data class ChatUiState(
    val streaming: Boolean = false,
    val error: String? = null,
    val pendingConfirmation: PendingConfirmation? = null,
    val resolvingConfirmation: Boolean = false,
)

class ChatViewModel(private val container: AppContainer, val conversationId: String) : ViewModel() {
    val messages = container.chatRepository.messages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    private var streamJob: Job? = null
    private var activeAssistantId: String? = null

    init {
        viewModelScope.launch {
            val pending = container.chatRepository.conversation(conversationId)?.pendingConfirmation
            mutableState.value = mutableState.value.copy(pendingConfirmation = pending)
        }
    }

    fun send(text: String) {
        val value = text.trim()
        if (value.isEmpty() || mutableState.value.streaming || mutableState.value.pendingConfirmation != null) return
        streamJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(streaming = true, error = null)
            val assistantId = container.chatRepository.beginTurn(conversationId, value)
            activeAssistantId = assistantId
            runStream(assistantId) {
                container.chatClient.stream(value, conversationId, UUID.randomUUID().toString())
            }
        }
    }

    fun resolveConfirmation(approved: Boolean) {
        if (mutableState.value.pendingConfirmation == null || mutableState.value.resolvingConfirmation) return
        streamJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(resolvingConfirmation = true, streaming = true, error = null)
            container.chatRepository.clearConfirmation(conversationId)
            val assistantId = container.chatRepository.beginAssistant(conversationId)
            activeAssistantId = assistantId
            mutableState.value = mutableState.value.copy(pendingConfirmation = null)
            runStream(assistantId) {
                container.chatClient.resume(conversationId, approved, UUID.randomUUID().toString())
            }
            mutableState.value = mutableState.value.copy(resolvingConfirmation = false)
        }
    }

    fun stop() {
        streamJob?.cancel()
        activeAssistantId?.let { id -> viewModelScope.launch { container.chatRepository.interrupt(id) } }
        mutableState.value = mutableState.value.copy(streaming = false)
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    private suspend fun runStream(
        assistantId: String,
        source: () -> kotlinx.coroutines.flow.Flow<com.campuslink.mobile.core.model.SseEvent>,
    ) {
        runCatching {
            source().collect { event ->
                val pending = container.chatRepository.applyEvent(conversationId, assistantId, event)
                if (pending != null) mutableState.value = mutableState.value.copy(pendingConfirmation = pending)
            }
        }.onFailure {
            if (it !is kotlinx.coroutines.CancellationException) {
                container.chatRepository.interrupt(assistantId)
                mutableState.value = mutableState.value.copy(error = it.message ?: "Connection interrupted")
            }
        }
        mutableState.value = mutableState.value.copy(streaming = false)
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

class ContainerViewModelFactory(
    private val create: () -> ViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
