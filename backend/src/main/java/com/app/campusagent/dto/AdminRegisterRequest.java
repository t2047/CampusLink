package com.app.campusagent.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AdminRegisterRequest(
        @NotBlank @Email
        String email,

        @NotBlank @Size(min = 6)
        String password,

        @NotBlank
        @Pattern(regexp = "STUDENT|ADMIN", message = "Role must be STUDENT or ADMIN")
        String role
) {
}
