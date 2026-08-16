package com.campuslink.mobile.core.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class ChatSseClientTest {
    private lateinit var server: MockWebServer
    private var unauthorized = false
    private lateinit var client: ChatSseClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = ChatSseClient(
            OkHttpClient(),
            server.url("/").toString(),
            Json { ignoreUnknownKeys = true },
            tokenProvider = { "test-jwt" },
            onUnauthorized = { unauthorized = true },
        )
    }

    @After
    fun tearDown() = runCatching { server.shutdown() }.let { Unit }

    @Test
    fun `explicit done completes stream`() = runTest {
        server.enqueue(MockResponse().setBody(
            "event: token\ndata: {\"content\":\"ok\"}\n\n" +
                "event: done\ndata: {}\n\n",
        ))

        val events = client.stream("hello", "session", "trace").toList()

        assertEquals(listOf("token", "done"), events.map { it.type })
        assertEquals("Bearer test-jwt", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `burst stream preserves every event including terminal done`() = runTest {
        val body = buildString {
            repeat(100) { index ->
                append("event: token\ndata: {\"content\":\"$index\"}\n\n")
            }
            append("event: done\ndata: {}\n\n")
        }
        server.enqueue(MockResponse().setBody(body))

        val events = client.stream("hello", "session", "trace").toList()

        assertEquals(101, events.size)
        assertEquals("done", events.last().type)
    }

    @Test
    fun `abrupt EOF fails instead of completing`() = runTest {
        server.enqueue(MockResponse().setBody("event: token\ndata: {\"content\":\"partial\"}\n\n"))

        val failure = runCatching { client.stream("hello", "session", "trace").toList() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure?.message?.contains("terminal event") == true)
    }

    @Test
    fun `malformed terminal event is not accepted as completion`() = runTest {
        server.enqueue(MockResponse().setBody("event: don\ndata: {}\n\n"))

        val failure = runCatching { client.stream("hello", "session", "trace").toList() }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun `network exception is propagated`() = runTest {
        server.shutdown()

        val failure = runCatching { client.stream("hello", "session", "trace").toList() }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun `401 invokes session invalidation policy and emits error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val events = client.resume("session", true, "trace").toList()

        assertTrue(unauthorized)
        assertEquals("error", events.single().type)
        assertEquals("401", events.single().data["status"].toString())
    }
}
