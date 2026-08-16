package com.campuslink.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.campuslink.mobile.AppContainer
import com.campuslink.mobile.core.model.AuthSession
import com.campuslink.mobile.core.model.PendingConfirmation
import com.campuslink.mobile.core.model.SseEvent
import com.campuslink.mobile.core.network.ApiException
import com.campuslink.mobile.core.network.ChatStreamClient
import com.campuslink.mobile.core.storage.ChatPersistence
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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
    val operation: ChatOperationState = ChatOperationState.IDLE,
    val error: String? = null,
    val pendingConfirmation: PendingConfirmation? = null,
) {
    val streaming: Boolean
        get() = operation == ChatOperationState.STREAMING ||
            operation == ChatOperationState.RETRYING ||
            operation == ChatOperationState.SUBMITTING_HITL

    val resolvingConfirmation: Boolean
        get() = operation == ChatOperationState.SUBMITTING_HITL
}

enum class ChatOperationState {
    IDLE,
    PENDING_HITL,
    SUBMITTING_HITL,
    HITL_RETRYABLE_FAILURE,
    STREAMING,
    STREAM_INTERRUPTED,
    RETRYING,
    COMPLETED,
    AUTHENTICATION_INVALIDATED,
}

class ChatViewModel(
    private val chatRepository: ChatPersistence,
    private val chatClient: ChatStreamClient,
    val conversationId: String,
) : ViewModel() {
    constructor(container: AppContainer, conversationId: String) : this(
        container.chatRepository,
        container.chatClient,
        conversationId,
    )

    val messages = chatRepository.messages(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()
    private var streamJob: Job? = null
    private var activeAssistantId: String? = null

    init {
        viewModelScope.launch {
            val pending = chatRepository.conversation(conversationId)?.pendingConfirmation
            if (pending != null && mutableState.value.operation == ChatOperationState.IDLE) {
                mutableState.value = mutableState.value.copy(
                    operation = ChatOperationState.PENDING_HITL,
                    pendingConfirmation = pending,
                )
            }
        }
    }

    fun send(text: String) {
        val value = text.trim()
        if (value.isEmpty() || mutableState.value.streaming || mutableState.value.pendingConfirmation != null) return
        mutableState.value = mutableState.value.copy(operation = ChatOperationState.STREAMING, error = null)
        streamJob = viewModelScope.launch {
            try {
                val failure = runCatching {
                    val assistantId = chatRepository.beginTurn(conversationId, value)
                    activeAssistantId = assistantId
                    finishRegularOperation(runStream(assistantId) {
                        chatClient.stream(value, conversationId, UUID.randomUUID().toString())
                    })
                }.exceptionOrNull()
                if (failure is CancellationException) throw failure
                if (failure != null) {
                    mutableState.value = mutableState.value.copy(
                        operation = ChatOperationState.STREAM_INTERRUPTED,
                        error = failure.message ?: "Unable to start chat",
                    )
                }
            } finally {
                activeAssistantId = null
            }
        }
    }

    fun resolveConfirmation(approved: Boolean) {
        if (mutableState.value.pendingConfirmation == null || mutableState.value.resolvingConfirmation) return
        val originalPending = requireNotNull(mutableState.value.pendingConfirmation)
        mutableState.value = mutableState.value.copy(
            operation = ChatOperationState.SUBMITTING_HITL,
            error = null,
        )
        streamJob = viewModelScope.launch {
            try {
                val failure = runCatching {
                    val assistantId = chatRepository.beginAssistant(conversationId)
                    activeAssistantId = assistantId
                    val outcome = runStream(assistantId) {
                        chatClient.resume(conversationId, approved, UUID.randomUUID().toString())
                    }
                    finishConfirmationOperation(outcome, originalPending)
                }.exceptionOrNull()
                if (failure is CancellationException) throw failure
                if (failure != null) {
                    mutableState.value = mutableState.value.copy(
                        operation = ChatOperationState.HITL_RETRYABLE_FAILURE,
                        error = failure.message ?: "Confirmation failed. Please retry.",
                        pendingConfirmation = mutableState.value.pendingConfirmation ?: originalPending,
                    )
                }
            } finally {
                activeAssistantId = null
            }
        }
    }

    fun retry(failedAssistantId: String) {
        if (mutableState.value.streaming || mutableState.value.pendingConfirmation != null) return
        mutableState.value = mutableState.value.copy(operation = ChatOperationState.RETRYING, error = null)
        streamJob = viewModelScope.launch {
            try {
                val failure = runCatching {
                    val retry = chatRepository.beginRetry(conversationId, failedAssistantId)
                    activeAssistantId = retry.assistantId
                    finishRegularOperation(runStream(retry.assistantId) {
                        chatClient.stream(retry.message, conversationId, UUID.randomUUID().toString())
                    })
                }.exceptionOrNull()
                if (failure is CancellationException) throw failure
                if (failure != null) {
                    mutableState.value = mutableState.value.copy(
                        operation = ChatOperationState.STREAM_INTERRUPTED,
                        error = failure.message ?: "Unable to retry chat",
                    )
                }
            } finally {
                activeAssistantId = null
            }
        }
    }

    fun stop() {
        val assistantId = activeAssistantId
        streamJob?.cancel()
        assistantId?.let { id -> viewModelScope.launch { chatRepository.interrupt(id) } }
        mutableState.value = mutableState.value.copy(
            operation = if (mutableState.value.pendingConfirmation != null) {
                ChatOperationState.HITL_RETRYABLE_FAILURE
            } else {
                ChatOperationState.STREAM_INTERRUPTED
            },
        )
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    private suspend fun runStream(
        assistantId: String,
        source: () -> kotlinx.coroutines.flow.Flow<SseEvent>,
    ): StreamOutcome {
        var terminal = StreamTerminal.INTERRUPTED
        var errorMessage: String? = null
        var latestPending: PendingConfirmation? = null
        val failure = runCatching {
            source().collect { event ->
                val pending = chatRepository.applyEvent(conversationId, assistantId, event)
                if (pending != null) {
                    latestPending = pending
                    mutableState.value = mutableState.value.copy(pendingConfirmation = pending)
                }
                when (event.type) {
                    "done" -> if (terminal == StreamTerminal.INTERRUPTED) {
                        terminal = StreamTerminal.COMPLETED
                    }
                    "error" -> {
                        terminal = if (event.data["status"]?.jsonPrimitive?.intOrNull == 401) {
                            StreamTerminal.AUTHENTICATION_INVALIDATED
                        } else {
                            StreamTerminal.FAILED
                        }
                        errorMessage = event.data["message"]?.jsonPrimitive?.contentOrNull
                    }
                }
            }
        }.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure != null) {
            chatRepository.interrupt(assistantId)
            terminal = if (failure is ApiException && failure.statusCode == 401) {
                StreamTerminal.AUTHENTICATION_INVALIDATED
            } else {
                StreamTerminal.INTERRUPTED
            }
            errorMessage = failure.message ?: "Connection interrupted"
        }
        if (terminal == StreamTerminal.INTERRUPTED && errorMessage == null) {
            chatRepository.interrupt(assistantId)
            errorMessage = "Connection interrupted"
        }
        return StreamOutcome(terminal, errorMessage, latestPending)
    }

    private fun finishRegularOperation(outcome: StreamOutcome) {
        val pending = outcome.pendingConfirmation ?: mutableState.value.pendingConfirmation
        mutableState.value = mutableState.value.copy(
            operation = when {
                outcome.terminal == StreamTerminal.AUTHENTICATION_INVALIDATED -> {
                    ChatOperationState.AUTHENTICATION_INVALIDATED
                }
                pending != null && outcome.terminal != StreamTerminal.COMPLETED -> {
                    ChatOperationState.HITL_RETRYABLE_FAILURE
                }
                pending != null -> ChatOperationState.PENDING_HITL
                outcome.terminal == StreamTerminal.COMPLETED -> ChatOperationState.COMPLETED
                else -> ChatOperationState.STREAM_INTERRUPTED
            },
            error = outcome.errorMessage,
            pendingConfirmation = pending,
        )
    }

    private suspend fun finishConfirmationOperation(outcome: StreamOutcome, original: PendingConfirmation) {
        val nextPending = outcome.pendingConfirmation
        if (outcome.terminal == StreamTerminal.COMPLETED && nextPending == null) {
            chatRepository.clearConfirmation(conversationId)
            mutableState.value = mutableState.value.copy(
                operation = ChatOperationState.COMPLETED,
                error = null,
                pendingConfirmation = null,
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            operation = if (outcome.terminal == StreamTerminal.AUTHENTICATION_INVALIDATED) {
                ChatOperationState.AUTHENTICATION_INVALIDATED
            } else if (nextPending != null && outcome.terminal == StreamTerminal.COMPLETED) {
                ChatOperationState.PENDING_HITL
            } else {
                ChatOperationState.HITL_RETRYABLE_FAILURE
            },
            error = outcome.errorMessage,
            pendingConfirmation = nextPending ?: original,
        )
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

private data class StreamOutcome(
    val terminal: StreamTerminal,
    val errorMessage: String?,
    val pendingConfirmation: PendingConfirmation?,
)

private enum class StreamTerminal { COMPLETED, FAILED, INTERRUPTED, AUTHENTICATION_INVALIDATED }

class ContainerViewModelFactory(
    private val create: () -> ViewModel,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
