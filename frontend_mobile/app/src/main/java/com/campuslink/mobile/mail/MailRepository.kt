@file:Suppress("TooManyFunctions")

package com.campuslink.mobile.mail

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
import com.campuslink.mobile.core.network.MailApi

interface MailDataSource {
    suspend fun oauthUrl(): OAuthUrlResponse
    suspend fun oauthStatus(): OAuthStatusResponse
    suspend fun disconnectOAuth(): OAuthStatusResponse
    suspend fun listMessages(
        folder: String,
        query: String = "",
        unread: Boolean? = null,
        starred: Boolean? = null,
        page: Int = 0,
    ): MailPageResponse
    suspend fun getMessage(messageId: String): MailMessage
    suspend fun sendMessage(request: SendMailRequest): MailMessage
    suspend fun updateMessage(messageId: String, request: UpdateMailRequest): MailMessage
    suspend fun archiveMessage(messageId: String): MailMessage
    suspend fun deleteMessage(messageId: String): MailMessage
    suspend fun listCalendarEvents(start: String? = null, end: String? = null): List<CalendarEvent>
    suspend fun createCalendarEvent(request: CalendarEventRequest): CalendarEvent
    suspend fun updateCalendarEvent(eventId: String, request: CalendarEventUpdate): CalendarEvent
    suspend fun deleteCalendarEvent(eventId: String)
    suspend fun extractCalendarSchedules(days: Int = 0, maxResults: Int = 20): ExtractResponse
    suspend fun importCalendarSchedules(request: ImportRequest): ImportResponse
}

class MailRepository(private val api: MailApi) : MailDataSource {
    override suspend fun oauthUrl() = api.oauthUrl()
    override suspend fun oauthStatus() = api.oauthStatus()
    override suspend fun disconnectOAuth() = api.disconnectOAuth()
    override suspend fun listMessages(folder: String, query: String, unread: Boolean?, starred: Boolean?, page: Int) =
        api.listMessages(folder, query, unread, starred, page)
    override suspend fun getMessage(messageId: String) = api.getMessage(messageId)
    override suspend fun sendMessage(request: SendMailRequest) = api.sendMessage(request)
    override suspend fun updateMessage(messageId: String, request: UpdateMailRequest) =
        api.updateMessage(messageId, request)
    override suspend fun archiveMessage(messageId: String) = api.archiveMessage(messageId)
    override suspend fun deleteMessage(messageId: String) = api.deleteMessage(messageId)
    override suspend fun listCalendarEvents(start: String?, end: String?) = api.listCalendarEvents(start, end)
    override suspend fun createCalendarEvent(request: CalendarEventRequest) = api.createCalendarEvent(request)
    override suspend fun updateCalendarEvent(eventId: String, request: CalendarEventUpdate) =
        api.updateCalendarEvent(eventId, request)
    override suspend fun deleteCalendarEvent(eventId: String) = api.deleteCalendarEvent(eventId)
    override suspend fun extractCalendarSchedules(days: Int, maxResults: Int) =
        api.extractCalendarSchedules(days, maxResults)
    override suspend fun importCalendarSchedules(request: ImportRequest) = api.importCalendarSchedules(request)
}
