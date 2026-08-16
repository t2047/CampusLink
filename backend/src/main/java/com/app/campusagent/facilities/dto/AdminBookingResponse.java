package com.app.campusagent.facilities.dto;

import java.time.LocalDateTime;

/** Booking view for administrators, including the account that created it. */
public record AdminBookingResponse(
        boolean success,
        Long bookingId,
        Long userId,
        String userEmail,
        SpaceResponse space,
        LocalDateTime startDateTime,
        LocalDateTime endDateTime,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
