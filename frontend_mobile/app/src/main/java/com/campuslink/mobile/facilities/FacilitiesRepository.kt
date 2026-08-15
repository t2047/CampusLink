package com.campuslink.mobile.facilities

import com.campuslink.mobile.core.model.AvailabilityResponse
import com.campuslink.mobile.core.model.Space
import com.campuslink.mobile.core.model.SpaceSearchFilters
import com.campuslink.mobile.core.network.FacilitiesApi

interface FacilitiesDataSource {
    suspend fun searchSpaces(filters: SpaceSearchFilters): List<Space>
    suspend fun getSpace(spaceId: Long): Space
    suspend fun checkAvailability(spaceId: Long, startDateTime: String, endDateTime: String): AvailabilityResponse
}

class FacilitiesRepository(private val api: FacilitiesApi) : FacilitiesDataSource {
    override suspend fun searchSpaces(filters: SpaceSearchFilters): List<Space> = api.searchSpaces(filters)

    override suspend fun getSpace(spaceId: Long): Space = api.getSpace(spaceId)

    override suspend fun checkAvailability(
        spaceId: Long,
        startDateTime: String,
        endDateTime: String,
    ): AvailabilityResponse = api.checkAvailability(spaceId, startDateTime, endDateTime)
}
