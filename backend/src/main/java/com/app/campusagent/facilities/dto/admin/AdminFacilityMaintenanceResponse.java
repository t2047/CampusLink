package com.app.campusagent.facilities.dto.admin;

import java.time.LocalDateTime;

public record AdminFacilityMaintenanceResponse(
        Long ticketId,
        Long userId,
        String userEmail,
        Long spaceId,
        String spaceName,
        String spaceType,
        String building,
        String floor,
        String roomNumber,
        String facilityType,
        String description,
        String priority,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
