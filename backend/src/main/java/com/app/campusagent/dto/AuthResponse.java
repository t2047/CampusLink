package com.app.campusagent.dto;

public record AuthResponse(String token, String email, String role) {
}
