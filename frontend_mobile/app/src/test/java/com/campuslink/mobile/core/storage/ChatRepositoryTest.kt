package com.campuslink.mobile.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.campuslink.mobile.core.model.SseEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// Robolectric 的 API 36 运行时要求 JDK 21；项目构建基线为 JDK 17，因此单元测试固定使用 API 35。
@Config(application = android.app.Application::class, sdk = [35])
class ChatRepositoryTest {
    private lateinit var database: CampusDatabase
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CampusDatabase::class.java).allowMainThreadQueries().build()
        repository = ChatRepository(database.chatDao(), Json { ignoreUnknownKeys = true })
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `persists streamed content and completion`() = runTest {
        val conversationId = repository.createConversation("student@nus.edu.sg")
        val assistantId = repository.beginTurn(conversationId, "Hello")

        repository.applyEvent(conversationId, assistantId, SseEvent("token", buildJsonObject { put("content", "Hi") }))
        repository.applyEvent(conversationId, assistantId, SseEvent("done", buildJsonObject {}))

        val messages = repository.messages(conversationId).first()
        assertEquals(2, messages.size)
        assertEquals("Hi", messages.last().content)
        assertEquals("COMPLETE", messages.last().status.name)
    }

    @Test
    fun `retry reuses original turn without inserting another user message`() = runTest {
        val conversationId = repository.createConversation("student@nus.edu.sg")
        val failedAssistantId = repository.beginTurn(conversationId, "Hello")
        repository.applyEvent(
            conversationId,
            failedAssistantId,
            SseEvent("error", buildJsonObject { put("message", "Disconnected") }),
        )

        val retry = repository.beginRetry(conversationId, failedAssistantId)
        repository.applyEvent(
            conversationId,
            retry.assistantId,
            SseEvent("token", buildJsonObject { put("content", "Recovered") }),
        )
        repository.applyEvent(conversationId, retry.assistantId, SseEvent("done", buildJsonObject {}))

        val messages = repository.messages(conversationId).first()
        assertEquals("Hello", retry.message)
        assertEquals(1, messages.count { it.role.name == "USER" })
        assertEquals(3, messages.size)
        assertEquals("Recovered", messages.last().content)
        assertEquals("COMPLETE", messages.last().status.name)
    }
}
