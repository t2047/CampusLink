package com.campuslink.mobile.core.network

import com.campuslink.mobile.core.model.AvailabilityResponse
import com.campuslink.mobile.core.model.BookingResponse
import com.campuslink.mobile.core.model.CreateBookingRequest
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.core.model.SpaceSearchFilters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FacilitiesApi(
    private val client: AuthenticatedHttpClient,
    private val json: Json,
) {
    suspend fun searchSpaces(filters: SpaceSearchFilters = SpaceSearchFilters()): List<Space> {
        val query = buildList {
            filters.query.trim().takeIf(String::isNotEmpty)?.let { add("query" to it) }
            filters.building.trim().takeIf(String::isNotEmpty)?.let { add("building" to it) }
            filters.spaceType.trim().takeIf(String::isNotEmpty)?.let { add("spaceType" to it) }
            filters.minimumCapacity?.let { add("minimumCapacity" to it.toString()) }
            filters.equipment.map(String::trim).filter(String::isNotEmpty).forEach { add("equipment" to it) }
            filters.startDateTime?.takeIf(String::isNotBlank)?.let { add("startDateTime" to it) }
            filters.endDateTime?.takeIf(String::isNotBlank)?.let { add("endDateTime" to it) }
        }
        return json.decodeFromString(ListSerializer(Space.serializer()), client.get(SPACES_PATH, query))
    }

    suspend fun getSpace(spaceId: Long): Space =
        json.decodeFromString(Space.serializer(), client.get("$SPACES_PATH/$spaceId"))

    suspend fun checkAvailability(
        spaceId: Long,
        startDateTime: String,
        endDateTime: String,
    ): AvailabilityResponse = json.decodeFromString(
        AvailabilityResponse.serializer(),
        client.get(
            "$SPACES_PATH/$spaceId/availability",
            listOf("startDateTime" to startDateTime, "endDateTime" to endDateTime),
        ),
    )

    suspend fun createBooking(request: CreateBookingRequest): BookingResponse = json.decodeFromString(
        BookingResponse.serializer(),
        client.post(BOOKINGS_PATH, json.encodeToString(request)),
    )

    suspend fun listBookings(): List<BookingResponse> = json.decodeFromString(
        ListSerializer(BookingResponse.serializer()),
        client.get(BOOKINGS_PATH),
    )

    suspend fun getBookingDetails(bookingId: Long): BookingResponse = json.decodeFromString(
        BookingResponse.serializer(),
        client.get("$BOOKINGS_PATH/$bookingId"),
    )

    suspend fun cancelBooking(bookingId: Long): BookingResponse = json.decodeFromString(
        BookingResponse.serializer(),
        client.patch("$BOOKINGS_PATH/$bookingId/cancel"),
    )

    companion object {
        private const val SPACES_PATH = "api/facilities/spaces"
        private const val BOOKINGS_PATH = "api/facilities/bookings"
    }
}
