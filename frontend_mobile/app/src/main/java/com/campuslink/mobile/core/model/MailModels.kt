@file:Suppress("ConstructorParameterNaming")

package com.campuslink.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class MailMessage(
    val id: String,
    val subject: String,
    val sender: String,
    val recipients: List<String> = emptyList(),
    val preview: String = "",
    val body: String = "",
    val body_html: String? = null,
    val folder: String,
    val category: String = "other",
    val read: Boolean = false,
    val starred: Boolean = false,
    val created_at: String,
    val updated_at: String,
)

@Serializable
data class MailPageResponse(
    val content: List<MailMessage> = emptyList(),
    val page: Int = 0,
    val size: Int = 20,
    val total_elements: Int = 0,
    val total_pages: Int = 0,
    val first: Boolean = true,
    val last: Boolean = true,
)

@Serializable
data class SendMailRequest(
    val recipients: List<String>,
    val subject: String,
    val body: String,
)

@Serializable
data class UpdateMailRequest(
    val read: Boolean? = null,
    val starred: Boolean? = null,
    val folder: String? = null,
)

@Serializable
data class OAuthUrlResponse(
    val auth_url: String,
    val connected: Boolean = false,
)

@Serializable
data class OAuthStatusResponse(
    val connected: Boolean = false,
    val email: String? = null,
)

@Serializable
data class CalendarEvent(
    val id: String,
    val user_id: String = "",
    val title: String,
    val description: String = "",
    val location: String = "",
    val start_time: String,
    val end_time: String,
    val all_day: Boolean = false,
    val source: String = "manual",
    val source_email_id: String? = null,
    val created_at: String = "",
    val updated_at: String = "",
)

@Serializable
data class CalendarEventRequest(
    val title: String,
    val description: String = "",
    val location: String = "",
    val start_time: String,
    val end_time: String,
    val all_day: Boolean = false,
)

@Serializable
data class CalendarEventUpdate(
    val title: String? = null,
    val description: String? = null,
    val location: String? = null,
    val start_time: String? = null,
    val end_time: String? = null,
    val all_day: Boolean? = null,
)

@Serializable
data class ExtractedSchedule(
    val key: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    val start_time: String,
    val end_time: String,
    val all_day: Boolean = false,
    val source_email_id: String? = null,
    val email_subject: String = "",
)

@Serializable
data class ExtractResponse(
    val days: Int,
    val scanned: Int,
    val mode: String = "rules",
    val events: List<ExtractedSchedule> = emptyList(),
)

@Serializable
data class ImportRequest(val events: List<ExtractedSchedule>)

@Serializable
data class ImportResponse(
    val imported: Int,
    val skipped: Int,
    val events: List<CalendarEvent> = emptyList(),
)
