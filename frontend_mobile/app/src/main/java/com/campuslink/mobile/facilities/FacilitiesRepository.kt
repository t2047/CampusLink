package com.campuslink.mobile.facilities

import com.campuslink.mobile.core.model.AvailabilityResponse
import com.campuslink.mobile.core.model.BookingResponse
import com.campuslink.mobile.core.model.CreateBookingRequest
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.core.model.SpaceSearchFilters
import com.campuslink.mobile.core.network.FacilitiesApi

interface FacilitiesDataSource {
    suspend fun searchSpaces(filters: SpaceSearchFilters): List<Space>
    suspend fun getSpace(spaceId: Long): Space
    suspend fun checkAvailability(spaceId: Long, startDateTime: String, endDateTime: String): AvailabilityResponse
    suspend fun createBooking(request: CreateBookingRequest): BookingResponse
    suspend fun listBookings(): List<BookingResponse>
    suspend fun getBookingDetails(bookingId: Long): BookingResponse
    suspend fun cancelBooking(bookingId: Long): BookingResponse
}

class FacilitiesRepository(private val api: FacilitiesApi) : FacilitiesDataSource {
    override suspend fun searchSpaces(filters: SpaceSearchFilters): List<Space> = api.searchSpaces(filters)

    override suspend fun getSpace(spaceId: Long): Space = api.getSpace(spaceId)

    override suspend fun checkAvailability(
        spaceId: Long,
        startDateTime: String,
        endDateTime: String,
    ): AvailabilityResponse = api.checkAvailability(spaceId, startDateTime, endDateTime)

    override suspend fun createBooking(request: CreateBookingRequest): BookingResponse = api.createBooking(request)

    override suspend fun listBookings(): List<BookingResponse> = api.listBookings()

    override suspend fun getBookingDetails(bookingId: Long): BookingResponse = api.getBookingDetails(bookingId)

    override suspend fun cancelBooking(bookingId: Long): BookingResponse = api.cancelBooking(bookingId)
}
