package com.campuslink.mobile.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Space(
    val spaceId: Long,
    val name: String,
    val building: String,
    val floor: String,
    val roomNumber: String,
    val spaceType: String,
    val capacity: Int,
    val equipment: Set<String> = emptySet(),
    val openingTime: String,
    val closingTime: String,
    val status: String,
)

@Serializable
data class AvailabilityResponse(
    val available: Boolean,
    val reasonCode: String? = null,
    val space: Space,
    val startDateTime: String,
    val endDateTime: String,
)

data class SpaceSearchFilters(
    val query: String = "",
    val building: String = "",
    val spaceType: String = "",
    val minimumCapacity: Int? = null,
    val equipment: List<String> = emptyList(),
    val startDateTime: String? = null,
    val endDateTime: String? = null,
)
