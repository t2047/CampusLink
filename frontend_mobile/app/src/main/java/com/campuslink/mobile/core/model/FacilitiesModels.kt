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

@Serializable
data class CreateBookingRequest(
    val spaceId: Long,
    val startDateTime: String,
    val endDateTime: String,
)

@Serializable
enum class BookingStatus {
    CONFIRMED,
    CANCELLED,
    COMPLETED,
}

@Serializable
data class BookingResponse(
    val success: Boolean,
    val bookingId: Long,
    val space: Space,
    val startDateTime: String,
    val endDateTime: String,
    val status: BookingStatus,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
enum class MaintenancePriority {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
enum class MaintenanceStatus {
    SUBMITTED,
    IN_PROGRESS,
    RESOLVED,
    CANCELLED,
}

@Serializable
data class SubmitMaintenanceRequest(
    val spaceId: Long,
    val facilityType: String,
    val description: String,
    val priority: MaintenancePriority = MaintenancePriority.MEDIUM,
)

@Serializable
data class MaintenanceResponse(
    val success: Boolean,
    val ticketId: Long,
    val spaceId: Long? = null,
    val spaceName: String? = null,
    val building: String,
    val roomNumber: String,
    val facilityType: String,
    val description: String,
    val priority: MaintenancePriority,
    val status: MaintenanceStatus,
    val createdAt: String,
    val updatedAt: String,
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
