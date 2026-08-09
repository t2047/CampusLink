package com.app.campusagent.facilities.dto;

import java.time.LocalDateTime;

public record MaintenanceResponse(
        boolean success,
        Long ticketId,
        Long spaceId,
        String spaceName,
        String building,
        String roomNumber,
        String facilityType,
        String description,
        String priority,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
