package com.app.campusagent.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.dto.AdminRegisterRequest;
import com.app.campusagent.dto.AuthResponse;
import com.app.campusagent.dto.UpdateRoleRequest;
import com.app.campusagent.dto.UserInfoResponse;
import com.app.campusagent.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AuthService authService;

    @GetMapping("/users")
    public ResponseEntity<List<UserInfoResponse>> getAllUsers() {
        return ResponseEntity.ok(authService.getAllUsers());
    }

    @PostMapping("/users")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<AuthResponse> registerUser(
            @Valid @RequestBody AdminRegisterRequest request,
            @AuthenticationPrincipal User requester) {
        return ResponseEntity.ok(authService.registerWithRole(request, requester));
    }

    @PutMapping("/users/{id}/role")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<UserInfoResponse> updateUserRole(
            @PathVariable Long id,
            @Valid @RequestBody UpdateRoleRequest request,
            @AuthenticationPrincipal User requester) {
        return ResponseEntity.ok(authService.updateUserRole(id, request.role(), requester));
    }
}
