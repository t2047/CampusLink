package com.campuslink.mobile.ui.mail

import com.campuslink.mobile.core.model.CalendarEvent
import com.campuslink.mobile.core.model.CalendarEventRequest
import com.campuslink.mobile.core.model.CalendarEventUpdate
import com.campuslink.mobile.core.model.ExtractResponse
import com.campuslink.mobile.core.model.ImportRequest
import com.campuslink.mobile.core.model.ImportResponse
import com.campuslink.mobile.core.model.MailMessage
import com.campuslink.mobile.core.model.MailPageResponse
import com.campuslink.mobile.core.model.OAuthStatusResponse
import com.campuslink.mobile.core.model.OAuthUrlResponse
import com.campuslink.mobile.core.model.SendMailRequest
import com.campuslink.mobile.core.model.UpdateMailRequest
import com.campuslink.mobile.mail.MailDataSource
import com.campuslink.mobile.ui.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MailViewModelsTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `compose validates recipients and sends trimmed message`() = runTest {
        val repository = FakeMailDataSource()
        val viewModel = ComposeMailViewModel(repository)

        viewModel.send()
        assertTrue(viewModel.state.value is ComposeMailUiState.Error)

        viewModel.updateRecipients(" first@example.com,second@example.com ")
        viewModel.updateSubject(" Exam notice ")
        viewModel.updateBody(" The exam starts at 2 pm. ")
        viewModel.send()
        advanceUntilIdle()

        assertTrue(viewModel.state.value is ComposeMailUiState.Sent)
        assertEquals(
            SendMailRequest(
                listOf("first@example.com", "second@example.com"),
                "Exam notice",
                " The exam starts at 2 pm. ",
            ),
            repository.sentRequest,
        )
    }

    @Test
    fun `mail home loads status and sends folder filters`() = runTest {
        val repository = FakeMailDataSource().apply { connected = true }
        val viewModel = MailHomeViewModel(repository)
        advanceUntilIdle()
        viewModel.updateFolder("sent")
        viewModel.updateQuery("exam")
        viewModel.updateUnreadOnly(true)
        viewModel.search()
        advanceUntilIdle()

        assertEquals("sent", repository.lastFolder)
        assertEquals("exam", repository.lastQuery)
        assertEquals(true, repository.lastUnread)
        assertEquals("mail-1", viewModel.state.value.messages.single().id)
    }

    @Test
    fun `mail details toggles read through repository`() = runTest {
        val repository = FakeMailDataSource()
        val viewModel = MailDetailsViewModel("mail-1", repository)
        advanceUntilIdle()

        viewModel.toggleRead()
        advanceUntilIdle()

        assertEquals(UpdateMailRequest(read = true), repository.lastUpdate)
    }

    @Test
    fun `calendar rejects invalid range before saving`() = runTest {
        val repository = FakeMailDataSource()
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()
        val callsBefore = repository.createCalendarCalls

        viewModel.beginCreate()
        viewModel.updateTitle("Exam")
        viewModel.updateStartTime("2026-08-16T16:00:00")
        viewModel.updateEndTime("2026-08-16T15:00:00")
        viewModel.save()
        advanceUntilIdle()

        assertEquals(callsBefore, repository.createCalendarCalls)
        assertEquals("End time must be after start time.", viewModel.state.value.error)
    }

    @Test
    fun `calendar extraction uses selected mail window and bounded result size`() = runTest {
        val repository = FakeMailDataSource()
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()

        viewModel.updateExtractionDays(7)
        viewModel.extract()
        advanceUntilIdle()

        assertEquals(7, repository.lastExtractDays)
        assertEquals(50, repository.lastExtractMaxResults)
    }

    @Test
    fun `deleting the event being edited closes the editor and returns to calendar list`() = runTest {
        val repository = FakeMailDataSource().apply { calendarEvents = listOf(EVENT) }
        val viewModel = CalendarViewModel(repository)
        advanceUntilIdle()

        viewModel.beginEdit(EVENT)
        assertTrue(viewModel.state.value.editorVisible)
        viewModel.delete(EVENT)
        advanceUntilIdle()

        assertEquals(false, viewModel.state.value.editorVisible)
        assertEquals(null, viewModel.state.value.editingId)
        assertEquals("", viewModel.state.value.form.title)
        assertTrue(viewModel.state.value.events.isEmpty())
        assertEquals("Event deleted", viewModel.state.value.actionMessage)
    }

    private class FakeMailDataSource : MailDataSource {
        var connected = false
        var lastFolder = ""
        var lastQuery = ""
        var lastUnread: Boolean? = null
        var sentRequest: SendMailRequest? = null
        var lastUpdate: UpdateMailRequest? = null
        var createCalendarCalls = 0
        var calendarEvents: List<CalendarEvent> = emptyList()
        var lastExtractDays: Int? = null
        var lastExtractMaxResults: Int? = null

        override suspend fun oauthUrl() = OAuthUrlResponse("https://accounts.example/authorize", connected)
        override suspend fun oauthStatus() = OAuthStatusResponse(connected, if (connected) "student@example.com" else null)
        override suspend fun disconnectOAuth() = OAuthStatusResponse(false, null)

        override suspend fun listMessages(
            folder: String,
            query: String,
            unread: Boolean?,
            starred: Boolean?,
            page: Int,
        ): MailPageResponse {
            lastFolder = folder
            lastQuery = query
            lastUnread = unread
            return MailPageResponse(content = listOf(MESSAGE), page = page, last = true)
        }

        override suspend fun getMessage(messageId: String) = MESSAGE

        override suspend fun sendMessage(request: SendMailRequest): MailMessage {
            sentRequest = request
            return MESSAGE
        }

        override suspend fun updateMessage(messageId: String, request: UpdateMailRequest): MailMessage {
            lastUpdate = request
            return MESSAGE.copy(
                read = request.read ?: MESSAGE.read,
                starred = request.starred ?: MESSAGE.starred,
            )
        }
        override suspend fun archiveMessage(messageId: String) = MESSAGE
        override suspend fun deleteMessage(messageId: String) = MESSAGE
        override suspend fun listCalendarEvents(start: String?, end: String?) = calendarEvents

        override suspend fun createCalendarEvent(request: CalendarEventRequest): CalendarEvent {
            createCalendarCalls++
            return EVENT
        }

        override suspend fun updateCalendarEvent(eventId: String, request: CalendarEventUpdate) = EVENT
        override suspend fun deleteCalendarEvent(eventId: String) = Unit
        override suspend fun extractCalendarSchedules(days: Int, maxResults: Int): ExtractResponse {
            lastExtractDays = days
            lastExtractMaxResults = maxResults
            return ExtractResponse(days, 0, events = emptyList())
        }
        override suspend fun importCalendarSchedules(request: ImportRequest) = ImportResponse(0, request.events.size)
    }

    companion object {
        private val MESSAGE = MailMessage(
            id = "mail-1",
            subject = "Exam notice",
            sender = "lecturer@example.com",
            recipients = listOf("student@example.com"),
            preview = "The exam is next week.",
            body = "The exam is next week.",
            folder = "inbox",
            created_at = "2026-08-16T08:00:00+00:00",
            updated_at = "2026-08-16T08:00:00+00:00",
        )
        private val EVENT = CalendarEvent(
            id = "event-1",
            title = "Exam",
            start_time = "2026-08-16T14:00:00",
            end_time = "2026-08-16T16:00:00",
        )
    }
}
