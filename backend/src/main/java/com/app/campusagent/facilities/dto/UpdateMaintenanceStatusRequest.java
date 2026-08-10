package com.app.campusagent.facilities.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateMaintenanceStatusRequest(
        @NotBlank String status
) {
}
