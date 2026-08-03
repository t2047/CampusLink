package com.app.campusagent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpdateRoleRequest(
        @NotBlank
        @Pattern(regexp = "STUDENT|ADMIN|SUPER_ADMIN", message = "Role must be STUDENT, ADMIN, or SUPER_ADMIN")
        String role
) {
}
