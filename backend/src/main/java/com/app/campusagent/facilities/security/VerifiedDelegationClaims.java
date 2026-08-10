package com.app.campusagent.facilities.security;

import com.app.campusagent.domain.Role;

import java.time.Instant;

/** Validated, security-safe subset of a Facilities delegation token. */
public record VerifiedDelegationClaims(
        Long userId,
        Role role,
        String jti,
        Instant issuedAt,
        Instant expiresAt) {
}
