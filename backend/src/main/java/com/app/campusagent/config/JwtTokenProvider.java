package com.app.campusagent.config;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public final class JwtTokenProvider {

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs) {
        // Build a 256-bit key from the secret.  Using HS256 avoids the
        // JJWT auto-detection pitfall where a 376-bit key triggers HS512
        // (which requires ≥ 512 bits per RFC 7518 § 3.2).
        byte[] keyBytes = new byte[32];
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(secretBytes, 0, keyBytes, 0, Math.min(secretBytes.length, 32));
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    public String generateToken(String subject, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(subject)
                .claim("role", role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public String getSubjectFromToken(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * 返回 token 的签发时间（iat），可能为 {@code null}（未携带 iat 的旧 token）。
     * 供 {@link JwtAuthFilter} 与用户改密时间比较，拒绝改密前签发的旧 token。
     */
    public Date getIssuedAtFromToken(String token) {
        return parseClaims(token).getIssuedAt();
    }

    public String getRoleFromToken(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 解析 Token 并返回 Claims。
     * 供 {@link JwtAuthFilter} 使用；签名无效或过期时抛出 {@link JwtException}。
     */
    public Claims parseToken(String token) {
        return parseClaims(token);
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
