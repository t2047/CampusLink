package com.app.campusagent.facilities.dto.admin;

import java.time.LocalDateTime;

public record AdminFacilityBookingResponse(
        Long bookingId,
        Long userId,
        String userEmail,
        Long spaceId,
        String spaceName,
        String building,
        String floor,
        String roomNumber,
        String spaceType,
        LocalDateTime startDateTime,
        LocalDateTime endDateTime,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
