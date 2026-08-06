package com.app.campusagent.service;

import com.app.campusagent.config.JwtTokenProvider;
import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.dto.*;
import com.app.campusagent.repository.UserRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       JwtTokenProvider jwtTokenProvider) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    /**
     * Public registration — always creates a STUDENT.
     */
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered");
        }

        User user = new User(
                request.getEmail(),
                passwordEncoder.encode(request.getPassword())
        );
        // role defaults to STUDENT via User entity

        userRepository.save(user);

        String token = jwtTokenProvider.generateToken(user.getEmail(), user.getRole().name());
        return new AuthResponse(token, user.getEmail(), user.getRole().name());
    }

    /**
     * SUPER_ADMIN registration — can choose STUDENT or ADMIN (not SUPER_ADMIN).
     */
    public AuthResponse registerWithRole(AdminRegisterRequest request, User requester) {
        if (requester.getRole() != Role.SUPER_ADMIN) {
            throw new AccessDeniedException("Only SUPER_ADMIN can create users with a specific role");
        }

        if (userRepository.existsByEmail(request.email())) {
            throw new RuntimeException("Email already registered");
        }

        // SUPER_ADMIN is not allowed through this endpoint; only STUDENT or ADMIN
        Role targetRole = Role.valueOf(request.role());

        User user = new User(
                request.email(),
                passwordEncoder.encode(request.password())
        );
        user.setRole(targetRole);

        userRepository.save(user);

        String token = jwtTokenProvider.generateToken(user.getEmail(), user.getRole().name());
        return new AuthResponse(token, user.getEmail(), user.getRole().name());
    }

    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid email or password"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid email or password");
        }

        String token = jwtTokenProvider.generateToken(user.getEmail(), user.getRole().name());
        return new AuthResponse(token, user.getEmail(), user.getRole().name());
    }

    @Transactional(readOnly = true)
    public List<UserInfoResponse> getAllUsers() {
        return userRepository.findAll().stream()
                .map(u -> new UserInfoResponse(u.getId(), u.getEmail(), u.getRole().name()))
                .toList();
    }

    @Transactional
    public UserInfoResponse updateUserRole(Long userId, String newRole, User requester) {
        if (requester.getRole() != Role.SUPER_ADMIN) {
            throw new AccessDeniedException("Only SUPER_ADMIN can change user roles");
        }

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (target.getId().equals(requester.getId())) {
            throw new RuntimeException("Cannot change your own role");
        }

        target.setRole(Role.valueOf(newRole));
        userRepository.save(target);

        return new UserInfoResponse(target.getId(), target.getEmail(), target.getRole().name());
    }
}
