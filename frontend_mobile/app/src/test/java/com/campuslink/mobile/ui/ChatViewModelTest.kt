package com.campuslink.mobile.ui

import com.campuslink.mobile.core.model.ChatMessage
import com.campuslink.mobile.core.model.Conversation
import com.campuslink.mobile.core.model.MessageRole
import com.campuslink.mobile.core.model.MessageStatus
import com.campuslink.mobile.core.model.PendingConfirmation
import com.campuslink.mobile.core.model.SseEvent
import com.campuslink.mobile.core.network.ApiException
import com.campuslink.mobile.core.network.ChatStreamClient
import com.campuslink.mobile.core.storage.ChatPersistence
import com.campuslink.mobile.core.storage.RetryTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `confirm clears persisted confirmation only after explicit success`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val persistence = FakeChatPersistence(pending = confirmation())
        val client = FakeChatStreamClient().apply {
            resumeSource = flow {
                emit(token("accepted"))
                gate.await()
                emit(done())
            }
        }
        val viewModel = createViewModel(persistence, client)
        advanceUntilIdle()

        viewModel.resolveConfirmation(true)
        runCurrent()

        assertEquals(ChatOperationState.SUBMITTING_HITL, viewModel.state.value.operation)
        assertEquals(0, persistence.clearCalls)
        assertNotNull(persistence.pending)

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(true), client.approvals)
        assertEquals(1, persistence.clearCalls)
        assertNull(persistence.pending)
        assertNull(viewModel.state.value.pendingConfirmation)
        assertEquals(ChatOperationState.COMPLETED, viewModel.state.value.operation)
    }

    @Test
    fun `cancel success clears persisted confirmation`() = runTest {
        val persistence = FakeChatPersistence(pending = confirmation())
        val client = FakeChatStreamClient().apply { resumeSource = flowOf(done()) }
        val viewModel = createViewModel(persistence, client)
        advanceUntilIdle()

        viewModel.resolveConfirmation(false)
        advanceUntilIdle()

        assertEquals(listOf(false), client.approvals)
        assertEquals(1, persistence.clearCalls)
        assertNull(persistence.pending)
    }

    @Test
    fun `network failure retains confirmation and restores retryable state`() = runTest {
        val persistence = FakeChatPersistence(pending = confirmation())
        val client = FakeChatStreamClient().apply {
            resumeSource = flow { throw IOException("offline") }
        }
        val viewModel = createViewModel(persistence, client)
        advanceUntilIdle()

        viewModel.resolveConfirmation(true)
        advanceUntilIdle()

        assertEquals(0, persistence.clearCalls)
        assertNotNull(persistence.pending)
        assertNotNull(viewModel.state.value.pendingConfirmation)
        assertFalse(viewModel.state.value.resolvingConfirmation)
        assertEquals(ChatOperationState.HITL_RETRYABLE_FAILURE, viewModel.state.value.operation)
    }

    @Test
    fun `timeout retains confirmation`() = runTest {
        val persistence = FakeChatPersistence(pending = confirmation())
        val client = FakeChatStreamClient().apply {
            resumeSource = flow { throw SocketTimeoutException("timeout") }
        }
        val viewModel = createViewModel(persistence, client)
        advanceUntilIdle()

        viewModel.resolveConfirmation(true)
        advanceUntilIdle()

        assertEquals(0, persistence.clearCalls)
        assertNotNull(persistence.pending)
        assertEquals(ChatOperationState.HITL_RETRYABLE_FAILURE, viewModel.state.value.operation)
    }

    @Test
    fun `restart recovers confirmation retained after failure`() = runTest {
        val persistence = FakeChatPersistence(pending = confirmation())
        val failingClient = FakeChatStreamClient().apply {
            resumeSource = flow { throw IOException("offline") }
        }
        val first = createViewModel(persistence, failingClient)
        advanceUntilIdle()
        first.resolveConfirmation(true)
        advanceUntilIdle()

        val restored = createViewModel(persistence, FakeChatStreamClient())
        advanceUntilIdle()

        assertNotNull(restored.state.value.pendingConfirmation)
        assertEquals(ChatOperationState.PENDING_HITL, restored.state.value.operation)
    }

    @Test
    fun `duplicate confirmation tap starts only one resume`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val persistence = FakeChatPersistence(pending = confirmation())
        val client = FakeChatStreamClient().apply {
            resumeSource = flow {
                gate.await()
                emit(done())
            }
        }
        val viewModel = createViewModel(persistence, client)
        advanceUntilIdle()

        viewModel.resolveConfirmation(true)
        viewModel.resolveConfirmation(true)
        runCurrent()

        assertEquals(1, client.approvals.size)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, persistence.clearCalls)
    }

    @Test
    fun `401 invalidates operation but retains pending confirmation`() = runTest {
        val persistence = FakeChatPersistence(pending = confirmation())
        val client = FakeChatStreamClient().apply {
            resumeSource = flow { throw ApiException(401, "Not authenticated") }
        }
        val viewModel = createViewModel(persistence, client)
        advanceUntilIdle()

        viewModel.resolveConfirmation(true)
        advanceUntilIdle()

        assertEquals(ChatOperationState.AUTHENTICATION_INVALIDATED, viewModel.state.value.operation)
        assertNotNull(persistence.pending)
        assertEquals(0, persistence.clearCalls)
    }

    @Test
    fun `retry streams original turn without beginning another user turn`() = runTest {
        val persistence = FakeChatPersistence(retryMessage = "hello")
        val client = FakeChatStreamClient().apply {
            streamSource = flowOf(token("retry response"), done())
        }
        val viewModel = createViewModel(persistence, client)
        advanceUntilIdle()

        viewModel.retry("failed-assistant")
        advanceUntilIdle()

        assertEquals(0, persistence.beginTurnCalls)
        assertEquals(1, persistence.beginRetryCalls)
        assertEquals(listOf("hello"), client.streamMessages)
        assertEquals(ChatOperationState.COMPLETED, viewModel.state.value.operation)
    }

    @Test
    fun `stop cancels active stream without marking it complete`() = runTest {
        val persistence = FakeChatPersistence()
        val client = FakeChatStreamClient().apply {
            streamSource = flow { awaitCancellation() }
        }
        val viewModel = createViewModel(persistence, client)
        advanceUntilIdle()

        viewModel.send("hello")
        runCurrent()
        viewModel.stop()
        advanceUntilIdle()

        assertEquals(ChatOperationState.STREAM_INTERRUPTED, viewModel.state.value.operation)
        assertEquals(1, persistence.interruptCalls)
        assertTrue(persistence.completedAssistantIds.isEmpty())
    }

    private fun createViewModel(
        persistence: FakeChatPersistence,
        client: FakeChatStreamClient,
    ) = ChatViewModel(persistence, client, CONVERSATION_ID)

    private fun confirmation() = PendingConfirmation("facilities", JsonObject(emptyMap()), "Confirm booking?")

    private fun token(content: String) = SseEvent("token", buildJsonObject { put("content", content) })

    private fun done() = SseEvent("done", buildJsonObject {})

    companion object {
        private const val CONVERSATION_ID = "conversation"
    }
}

private class FakeChatStreamClient : ChatStreamClient {
    var streamSource: Flow<SseEvent> = flowOf(doneEvent())
    var resumeSource: Flow<SseEvent> = flowOf(doneEvent())
    val approvals = mutableListOf<Boolean>()
    val streamMessages = mutableListOf<String>()

    override fun stream(message: String, sessionId: String, traceId: String): Flow<SseEvent> {
        streamMessages += message
        return streamSource
    }

    override fun resume(sessionId: String, approved: Boolean, traceId: String): Flow<SseEvent> {
        approvals += approved
        return resumeSource
    }

    companion object {
        private fun doneEvent() = SseEvent("done", JsonObject(emptyMap()))
    }
}

private class FakeChatPersistence(
    var pending: PendingConfirmation? = null,
    private val retryMessage: String = "hello",
) : ChatPersistence {
    private val messageFlow = MutableStateFlow<List<ChatMessage>>(emptyList())
    var clearCalls = 0
    var beginTurnCalls = 0
    var beginRetryCalls = 0
    var interruptCalls = 0
    val completedAssistantIds = mutableSetOf<String>()
    private var nextAssistant = 0

    override fun messages(conversationId: String): Flow<List<ChatMessage>> = messageFlow

    override suspend fun conversation(id: String) = Conversation(
        id = id,
        ownerEmail = "student@nus.edu.sg",
        title = "Test",
        createdAt = 1,
        updatedAt = 1,
        pendingConfirmation = pending,
    )

    override suspend fun beginTurn(conversationId: String, text: String): String {
        beginTurnCalls += 1
        return addAssistant(conversationId)
    }

    override suspend fun beginAssistant(conversationId: String): String = addAssistant(conversationId)

    override suspend fun beginRetry(conversationId: String, failedAssistantId: String): RetryTurn {
        beginRetryCalls += 1
        return RetryTurn(retryMessage, addAssistant(conversationId))
    }

    override suspend fun applyEvent(
        conversationId: String,
        assistantId: String,
        event: SseEvent,
    ): PendingConfirmation? {
        val messages = messageFlow.value.toMutableList()
        val index = messages.indexOfFirst { it.id == assistantId }
        if (index >= 0) {
            val current = messages[index]
            messages[index] = when (event.type) {
                "token" -> current.copy(content = current.content + event.data["content"].toString().trim('"'))
                "done" -> current.copy(status = MessageStatus.COMPLETE).also { completedAssistantIds += assistantId }
                "error" -> current.copy(status = MessageStatus.FAILED)
                else -> current
            }
            messageFlow.value = messages
        }
        return null
    }

    override suspend fun clearConfirmation(conversationId: String) {
        clearCalls += 1
        pending = null
    }

    override suspend fun interrupt(assistantId: String) {
        interruptCalls += 1
    }

    private fun addAssistant(conversationId: String): String {
        val id = "assistant-${++nextAssistant}"
        messageFlow.value += ChatMessage(
            id = id,
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.STREAMING,
            timestamp = nextAssistant.toLong(),
        )
        return id
    }
}
