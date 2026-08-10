package com.app.campusagent.facilities.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SubmitMaintenanceRequest(
        Long spaceId,
        @Size(max = 255) String building,
        @Size(max = 255) String roomNumber,
        @NotBlank @Size(max = 255) String facilityType,
        @NotBlank @Size(max = 2000) String description,
        String priority
) {
}
