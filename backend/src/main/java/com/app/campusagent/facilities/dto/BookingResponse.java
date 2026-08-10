package com.app.campusagent.facilities.dto;

import java.time.LocalDateTime;

public record BookingResponse(
        boolean success,
        Long bookingId,
        SpaceResponse space,
        LocalDateTime startDateTime,
        LocalDateTime endDateTime,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
