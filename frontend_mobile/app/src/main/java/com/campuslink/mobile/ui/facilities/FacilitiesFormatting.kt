package com.campuslink.mobile.ui.facilities

import com.campuslink.mobile.core.model.BookingResponse
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal fun parseDateTime(value: String): LocalDateTime? = runCatching {
    LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}.getOrNull()

internal fun formatBookingDate(value: String): String = parseDateTime(value)?.format(DATE_FORMAT) ?: value

internal fun formatBookingTime(value: String): String = parseDateTime(value)?.format(TIME_FORMAT) ?: value

internal fun formatBookingRange(start: String, end: String): String =
    "${formatBookingTime(start)} – ${formatBookingTime(end)}"

internal fun formatBookingRange(booking: BookingResponse): String =
    formatBookingRange(booking.startDateTime, booking.endDateTime)

internal fun formatMaintenanceDateTime(value: String): String = parseDateTime(value)?.format(DATE_TIME_FORMAT) ?: value

private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy, h:mm a")
