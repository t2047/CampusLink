package com.app.campusagent.facilities.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record CreateBookingRequest(
        @NotNull Long spaceId,
        @NotNull LocalDateTime startDateTime,
        @NotNull LocalDateTime endDateTime
) {
}
