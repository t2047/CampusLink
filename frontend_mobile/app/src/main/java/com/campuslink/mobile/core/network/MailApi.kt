@file:Suppress("LongParameterList")

package com.campuslink.mobile.core.network

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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class MailApi(
    private val client: AuthenticatedHttpClient,
    private val json: Json,
) {
    suspend fun oauthUrl(): OAuthUrlResponse = json.decodeFromString(
        OAuthUrlResponse.serializer(),
        client.get(OAUTH_URL_PATH),
    )

    suspend fun oauthStatus(): OAuthStatusResponse = json.decodeFromString(
        OAuthStatusResponse.serializer(),
        client.get(OAUTH_STATUS_PATH),
    )

    suspend fun disconnectOAuth(): OAuthStatusResponse = json.decodeFromString(
        OAuthStatusResponse.serializer(),
        client.post(OAUTH_DISCONNECT_PATH, "{}"),
    )

    suspend fun listMessages(
        folder: String,
        query: String = "",
        unread: Boolean? = null,
        starred: Boolean? = null,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
    ): MailPageResponse {
        val params = buildList {
            add("folder" to folder)
            query.trim().takeIf(String::isNotEmpty)?.let { add("q" to it) }
            unread?.let { add("unread" to it.toString()) }
            starred?.let { add("starred" to it.toString()) }
            add("page" to page.toString())
            add("size" to size.toString())
        }
        return json.decodeFromString(MailPageResponse.serializer(), client.get(MESSAGES_PATH, params))
    }

    suspend fun getMessage(messageId: String): MailMessage = json.decodeFromString(
        MailMessage.serializer(),
        client.get("$MESSAGES_PATH/${messageId.pathSegment()}")
    )

    suspend fun sendMessage(request: SendMailRequest): MailMessage = json.decodeFromString(
        MailMessage.serializer(),
        client.post(MESSAGES_PATH, json.encodeToString(request)),
    )

    suspend fun updateMessage(messageId: String, request: UpdateMailRequest): MailMessage = json.decodeFromString(
        MailMessage.serializer(),
        client.patch("$MESSAGES_PATH/${messageId.pathSegment()}", json.encodeToString(request)),
    )

    suspend fun archiveMessage(messageId: String): MailMessage = json.decodeFromString(
        MailMessage.serializer(),
        client.post("$MESSAGES_PATH/${messageId.pathSegment()}/archive", "{}"),
    )

    suspend fun deleteMessage(messageId: String): MailMessage = json.decodeFromString(
        MailMessage.serializer(),
        client.post("$MESSAGES_PATH/${messageId.pathSegment()}/delete", "{}"),
    )

    suspend fun listCalendarEvents(start: String? = null, end: String? = null): List<CalendarEvent> {
        val query = buildList {
            start?.takeIf(String::isNotBlank)?.let { add("start" to it) }
            end?.takeIf(String::isNotBlank)?.let { add("end" to it) }
        }
        return json.decodeFromString(ListSerializer(CalendarEvent.serializer()), client.get(CALENDAR_EVENTS_PATH, query))
    }

    suspend fun createCalendarEvent(request: CalendarEventRequest): CalendarEvent = json.decodeFromString(
        CalendarEvent.serializer(),
        client.post(CALENDAR_EVENTS_PATH, json.encodeToString(request)),
    )

    suspend fun updateCalendarEvent(eventId: String, request: CalendarEventUpdate): CalendarEvent = json.decodeFromString(
        CalendarEvent.serializer(),
        client.patch("$CALENDAR_EVENTS_PATH/${eventId.pathSegment()}", json.encodeToString(request)),
    )

    suspend fun deleteCalendarEvent(eventId: String) {
        client.delete("$CALENDAR_EVENTS_PATH/${eventId.pathSegment()}")
    }

    suspend fun extractCalendarSchedules(days: Int = 0, maxResults: Int = 20): ExtractResponse = json.decodeFromString(
        ExtractResponse.serializer(),
        client.post(
            CALENDAR_EXTRACT_PATH,
            listOf("days" to days.toString(), "max_results" to maxResults.toString()),
            "{}",
            timeoutSeconds = 180,
        ),
    )

    suspend fun importCalendarSchedules(request: ImportRequest): ImportResponse = json.decodeFromString(
        ImportResponse.serializer(),
        client.post(
            CALENDAR_IMPORT_PATH,
            json.encodeToString(request),
            timeoutSeconds = 60,
        ),
    )

    private fun String.pathSegment(): String = replace("%", "%25").replace("/", "%2F")

    companion object {
        private const val DEFAULT_PAGE_SIZE = 20
        private const val OAUTH_URL_PATH = "api/mail/oauth/url"
        private const val OAUTH_STATUS_PATH = "api/mail/oauth/status"
        private const val OAUTH_DISCONNECT_PATH = "api/mail/oauth/disconnect"
        private const val MESSAGES_PATH = "api/mail/messages"
        private const val CALENDAR_EVENTS_PATH = "api/mail/calendar/events"
        private const val CALENDAR_EXTRACT_PATH = "api/mail/calendar/extract"
        private const val CALENDAR_IMPORT_PATH = "api/mail/calendar/import"
    }
}
