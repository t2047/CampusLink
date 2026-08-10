package com.app.campusagent.facilities.dto;

import java.time.LocalDateTime;

public record AvailabilityResponse(
        boolean available,
        String reasonCode,
        SpaceResponse space,
        LocalDateTime startDateTime,
        LocalDateTime endDateTime
) {
}
