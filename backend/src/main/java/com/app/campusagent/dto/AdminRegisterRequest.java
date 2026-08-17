package com.app.campusagent.dto;

import com.app.campusagent.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AdminRegisterRequest(
        @NotBlank @Email
        String email,

        @NotBlank @ValidPassword
        String password,

        @NotBlank
        @Pattern(regexp = "STUDENT|ADMIN", message = "Role must be STUDENT or ADMIN")
        String role
) {
}
