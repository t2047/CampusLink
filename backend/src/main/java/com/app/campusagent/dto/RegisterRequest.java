package com.app.campusagent.dto;

import com.app.campusagent.validation.ValidPassword;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class RegisterRequest {
    @NotBlank
    @Email
    private String email;

    @NotBlank
    @ValidPassword
    private String password;

}
