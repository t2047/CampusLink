@file:Suppress("MaxLineLength")

package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.MailMessage
import com.campuslink.mobile.core.model.MailPageResponse
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

class MailApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MailApi
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = AuthenticatedHttpClient(
            OkHttpClient(),
            server.url("/").toString(),
            json,
            tokenProvider = { "mail-jwt" },
            onUnauthorized = {},
        )
        api = MailApi(client, json)
    }

    @After
    fun tearDown() = runCatching { server.shutdown() }.let { Unit }

    @Test
    fun `lists mail with filters and pagination`() = runTest {
        server.enqueue(MockResponse().setBody(pageJson()))

        val page = api.listMessages("inbox", query = "exam notice", unread = true, page = 2, size = 10)

        val request = server.takeRequest()
        assertEquals("Bearer mail-jwt", request.getHeader("Authorization"))
        assertEquals(
            "/api/mail/messages?folder=inbox&q=exam%20notice&unread=true&page=2&size=10",
            request.path,
        )
        assertEquals(1, page.content.size)
        assertEquals("mail-1", page.content.single().id)
        assertTrue(page.last)
    }

    @Test
    fun `calendar delete accepts empty 204 response`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        api.deleteCalendarEvent("event/1")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/mail/calendar/events/event%2F1", request.path)
    }

    @Test
    fun `not connected error preserves authorization url`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"code":"GMAIL_NOT_CONNECTED","error":"Authorize Gmail first","auth_url":"https://accounts.google.com/o/oauth2/auth?state=abc"}""",
            ),
        )

        val failure = runCatching { api.listMessages("inbox") }.exceptionOrNull()

        val exception = failure as ApiException
        assertEquals(409, exception.statusCode)
        assertEquals("GMAIL_NOT_CONNECTED", exception.errorCode)
        assertEquals("https://accounts.google.com/o/oauth2/auth?state=abc", exception.authUrl)
    }

    private fun pageJson(): String = json.encodeToString(
        MailPageResponse.serializer(),
        MailPageResponse(
            content = listOf(
                MailMessage(
                    id = "mail-1",
                    subject = "Exam notice",
                    sender = "lecturer@example.com",
                    recipients = listOf("student@example.com"),
                    preview = "The exam is next week.",
                    body = "The exam is next week.",
                    folder = "inbox",
                    created_at = "2026-08-16T08:00:00+00:00",
                    updated_at = "2026-08-16T08:00:00+00:00",
                ),
            ),
            page = 2,
            size = 10,
            total_elements = 21,
            total_pages = 3,
            first = false,
            last = true,
        ),
    )
}
