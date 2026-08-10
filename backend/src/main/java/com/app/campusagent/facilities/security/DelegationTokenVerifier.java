package com.app.campusagent.facilities.security;

import com.app.campusagent.chat.service.DelegationTokenProvider;
import com.app.campusagent.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;

/**
 * Cryptographically verifies and validates RS256 Facilities delegation tokens.
 * This class intentionally has no HTTP, repository, authority, or SecurityContext duties.
 */
public final class DelegationTokenVerifier {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(5);
    static final Duration MAX_TOKEN_LIFETIME = Duration.ofSeconds(60);

    private static final String EXPECTED_ISSUER = "token-service";
    private static final String EXPECTED_AUDIENCE = "facility-agent";
    private static final String EXPECTED_ACTION = "invoke";
    private static final String EXPECTED_ALGORITHM = "RS256";

    private final RSAPublicKey publicKey;
    private final Clock clock;

    public DelegationTokenVerifier(DelegationTokenProvider tokenProvider) {
        this(tokenProvider.getPublicKey(), Clock.systemUTC());
    }

    public DelegationTokenVerifier(DelegationTokenProvider tokenProvider, Clock clock) {
        this(tokenProvider.getPublicKey(), clock);
    }

    public DelegationTokenVerifier(RSAPublicKey publicKey, Clock clock) {
        if (publicKey == null) {
            throw new IllegalArgumentException("Public verification key is required");
        }
        if (clock == null) {
            throw new IllegalArgumentException("Clock is required");
        }
        this.publicKey = publicKey;
        this.clock = clock;
    }

    public VerifiedDelegationClaims verify(String token) {
        if (token == null || token.isBlank()) {
            throw invalid("Delegation token is required");
        }

        Jws<Claims> parsed = parseAndVerify(token);
        if (!EXPECTED_ALGORITHM.equals(parsed.getHeader().getAlgorithm())) {
            throw invalid("Delegation token must use RS256");
        }

        try {
            Claims claims = parsed.getPayload();
            requireIssuer(claims);
            requireAudience(claims);
            Long userId = requireUserId(claims);
            Role role = requireRole(claims);
            requireAction(claims);
            String jti = requireJti(claims);
            Instant issuedAt = requireInstant(claims.getIssuedAt(), "issued-at time");
            Instant expiresAt = requireInstant(claims.getExpiration(), "expiration time");
            validateTimes(issuedAt, expiresAt);

            return new VerifiedDelegationClaims(userId, role, jti, issuedAt, expiresAt);
        } catch (DelegationTokenVerificationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("Delegation token claims are invalid");
        }
    }

    private Jws<Claims> parseAndVerify(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(publicKey)
                    .clock(() -> Date.from(clock.instant()))
                    .clockSkewSeconds(CLOCK_SKEW.toSeconds())
                    .build()
                    .parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException exception) {
            throw invalid("Delegation token signature or structure is invalid");
        }
    }

    private void requireIssuer(Claims claims) {
        if (!EXPECTED_ISSUER.equals(claims.getIssuer())) {
            throw invalid("Delegation token issuer is invalid");
        }
    }

    private void requireAudience(Claims claims) {
        Object audience = claims.get("aud");
        boolean matches = audience instanceof String value
                ? EXPECTED_AUDIENCE.equals(value)
                : audience instanceof Collection<?> values
                && values.stream().anyMatch(EXPECTED_AUDIENCE::equals);
        if (!matches) {
            throw invalid("Delegation token audience is invalid");
        }
    }

    private Long requireUserId(Claims claims) {
        String subject = claims.getSubject();
        if (subject == null || !subject.matches("[0-9]+")) {
            throw invalid("Delegation token subject is invalid");
        }
        try {
            long userId = Long.parseLong(subject);
            if (userId <= 0) {
                throw invalid("Delegation token subject is invalid");
            }
            return userId;
        } catch (NumberFormatException exception) {
            throw invalid("Delegation token subject is invalid");
        }
    }

    private Role requireRole(Claims claims) {
        String role = claims.get("role", String.class);
        if (role == null || role.isBlank()) {
            throw invalid("Delegation token role is invalid");
        }
        try {
            return Role.valueOf(role);
        } catch (IllegalArgumentException exception) {
            throw invalid("Delegation token role is invalid");
        }
    }

    private void requireAction(Claims claims) {
        if (!EXPECTED_ACTION.equals(claims.get("intended_action", String.class))) {
            throw invalid("Delegation token intended action is invalid");
        }
    }

    private String requireJti(Claims claims) {
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            throw invalid("Delegation token ID is invalid");
        }
        return jti;
    }

    private Instant requireInstant(Date value, String fieldName) {
        if (value == null) {
            throw invalid("Delegation token " + fieldName + " is required");
        }
        return value.toInstant();
    }

    private void validateTimes(Instant issuedAt, Instant expiresAt) {
        Instant now = clock.instant();
        if (issuedAt.isAfter(now.plus(CLOCK_SKEW))) {
            throw invalid("Delegation token issued-at time is invalid");
        }
        if (now.isAfter(expiresAt.plus(CLOCK_SKEW))) {
            throw invalid("Delegation token is expired");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw invalid("Delegation token time range is invalid");
        }
        if (Duration.between(issuedAt, expiresAt).compareTo(MAX_TOKEN_LIFETIME) > 0) {
            throw invalid("Delegation token lifetime is invalid");
        }
    }

    private DelegationTokenVerificationException invalid(String message) {
        return new DelegationTokenVerificationException(message);
    }
}
