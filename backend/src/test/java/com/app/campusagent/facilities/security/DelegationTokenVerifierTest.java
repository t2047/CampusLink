package com.app.campusagent.facilities.security;

import com.app.campusagent.chat.service.DelegationTokenProvider;
import com.app.campusagent.domain.Role;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DelegationTokenVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-10T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Pattern IAT_PATTERN = Pattern.compile("\\\"iat\\\":(\\d+)");

    private static RSAPrivateKey privateKey;
    private static RSAPublicKey publicKey;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        publicKey = (RSAPublicKey) pair.getPublic();
    }

    @Test
    void acceptsValidRs256DelegationToken() {
        VerifiedDelegationClaims verified = verifier().verify(rs256(validClaims()));

        assertThat(verified.userId()).isEqualTo(42L);
        assertThat(verified.role()).isEqualTo(Role.STUDENT);
        assertThat(verified.jti()).isEqualTo("test-jti");
        assertThat(verified.issuedAt()).isEqualTo(NOW);
        assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(30));
    }

    @Test
    void rejectsHs256Token() {
        SecretKey key = Keys.hmacShaKeyFor("a-test-key-that-is-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder().claims(validClaims()).signWith(key, Jwts.SIG.HS256).compact();

        assertInvalid(token);
    }

    @Test
    void rejectsUnsecuredNoneAlgorithmToken() {
        String token = Jwts.builder().claims(validClaims()).compact();

        assertInvalid(token);
    }

    @Test
    void rejectsUnsupportedRs512Algorithm() {
        String token = Jwts.builder().claims(validClaims()).signWith(privateKey, Jwts.SIG.RS512).compact();

        assertInvalid(token);
    }

    @Test
    void rejectsSignatureFromWrongRsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPrivateKey wrongKey = (RSAPrivateKey) generator.generateKeyPair().getPrivate();

        assertInvalid(Jwts.builder().claims(validClaims()).signWith(wrongKey, Jwts.SIG.RS256).compact());
    }

    @Test
    void rejectsMissingIssuer() {
        assertInvalid(without("iss"));
    }

    @Test
    void rejectsWrongIssuer() {
        assertInvalid(with("iss", "other-service"));
    }

    @Test
    void rejectsMissingAudience() {
        assertInvalid(without("aud"));
    }

    @Test
    void rejectsWrongAudience() {
        assertInvalid(with("aud", List.of("mail-agent")));
    }

    @Test
    void acceptsAudienceListContainingFacilityAgent() {
        VerifiedDelegationClaims verified = verifier().verify(
                rs256(with("aud", List.of("mail-agent", "facility-agent"))));

        assertThat(verified.userId()).isEqualTo(42L);
    }

    @Test
    void rejectsMissingSubject() {
        assertInvalid(without("sub"));
    }

    @Test
    void rejectsNonNumericSubject() {
        assertInvalid(with("sub", "user-42"));
    }

    @Test
    void rejectsZeroSubject() {
        assertInvalid(with("sub", "0"));
    }

    @Test
    void rejectsNegativeSubject() {
        assertInvalid(with("sub", "-42"));
    }

    @Test
    void rejectsMissingExpiration() {
        assertInvalid(without("exp"));
    }

    @Test
    void rejectsExpiredTokenBeyondSkew() {
        Map<String, Object> claims = with("exp", Date.from(NOW.minusSeconds(6)));
        claims.put("iat", Date.from(NOW.minusSeconds(30)));

        assertInvalid(claims);
    }

    @Test
    void rejectsMissingIssuedAt() {
        assertInvalid(without("iat"));
    }

    @Test
    void rejectsIssuedAtTooFarInFuture() {
        Map<String, Object> claims = with("iat", Date.from(NOW.plusSeconds(6)));
        claims.put("exp", Date.from(NOW.plusSeconds(30)));

        assertInvalid(claims);
    }

    @Test
    void rejectsExpirationEqualToIssuedAt() {
        Map<String, Object> claims = with("iat", Date.from(NOW));
        claims.put("exp", Date.from(NOW));

        assertInvalid(claims);
    }

    @Test
    void rejectsLifetimeAboveSixtySeconds() {
        Map<String, Object> claims = with("iat", Date.from(NOW.minusSeconds(31)));
        claims.put("exp", Date.from(NOW.plusSeconds(30)));

        assertInvalid(claims);
    }

    @Test
    void acceptsExpirationWithinFiveSecondClockSkew() {
        Map<String, Object> claims = with("iat", Date.from(NOW.minusSeconds(30)));
        claims.put("exp", Date.from(NOW.minusSeconds(4)));

        assertThat(verifier().verify(rs256(claims)).expiresAt()).isEqualTo(NOW.minusSeconds(4));
    }

    @Test
    void acceptsIssuedAtWithinFiveSecondClockSkew() {
        Map<String, Object> claims = with("iat", Date.from(NOW.plusSeconds(4)));
        claims.put("exp", Date.from(NOW.plusSeconds(30)));

        assertThat(verifier().verify(rs256(claims)).issuedAt()).isEqualTo(NOW.plusSeconds(4));
    }

    @Test
    void rejectsMissingRole() {
        assertInvalid(without("role"));
    }

    @Test
    void rejectsInvalidRole() {
        assertInvalid(with("role", "STAFF"));
    }

    @Test
    void rejectsInvalidClaimTypeWithVerifierException() {
        assertThatThrownBy(() -> verifier().verify(rs256(with("role", 123))))
                .isExactlyInstanceOf(DelegationTokenVerificationException.class);
    }

    @Test
    void acceptsStudentRole() {
        assertThat(verifier().verify(rs256(with("role", "STUDENT"))).role()).isEqualTo(Role.STUDENT);
    }

    @Test
    void acceptsAdminRole() {
        assertThat(verifier().verify(rs256(with("role", "ADMIN"))).role()).isEqualTo(Role.ADMIN);
    }

    @Test
    void acceptsSuperAdminRole() {
        assertThat(verifier().verify(rs256(with("role", "SUPER_ADMIN"))).role())
                .isEqualTo(Role.SUPER_ADMIN);
    }

    @Test
    void rejectsMissingIntendedAction() {
        assertInvalid(without("intended_action"));
    }

    @Test
    void rejectsWrongIntendedAction() {
        assertInvalid(with("intended_action", "create_booking"));
    }

    @Test
    void rejectsMissingJti() {
        assertInvalid(without("jti"));
    }

    @Test
    void rejectsBlankJti() {
        assertInvalid(with("jti", "   "));
    }

    @Test
    void acceptsTokenIssuedByCurrentDelegationTokenProvider(@TempDir Path keyDirectory) {
        DelegationTokenProvider provider = new DelegationTokenProvider();
        ReflectionTestUtils.setField(provider, "keyDir", keyDirectory.toString());
        ReflectionTestUtils.setField(provider, "tokenTtlSeconds", 30L);
        provider.init();

        String token = provider.issueDelegationToken(
                "77", "ADMIN", "facility-agent", "invoke", "provider-jti");
        Instant issuedAt = tokenIssuedAt(token);
        DelegationTokenVerifier verifier = new DelegationTokenVerifier(
                provider, Clock.fixed(issuedAt, ZoneOffset.UTC));

        VerifiedDelegationClaims verified = verifier.verify(token);
        assertThat(verified.userId()).isEqualTo(77L);
        assertThat(verified.role()).isEqualTo(Role.ADMIN);
        assertThat(verified.jti()).isEqualTo("provider-jti");
        assertThat(verified.expiresAt()).isEqualTo(issuedAt.plusSeconds(30));
    }

    @Test
    void failureMessageDoesNotContainTokenOrKeyMaterial() {
        String token = rs256(with("iss", "wrong"));

        assertThatThrownBy(() -> verifier().verify(token))
                .isInstanceOf(DelegationTokenVerificationException.class)
                .hasMessageNotContaining(token)
                .hasMessageNotContaining(publicKey.getModulus().toString());
    }

    private DelegationTokenVerifier verifier() {
        return new DelegationTokenVerifier(publicKey, CLOCK);
    }

    private Map<String, Object> validClaims() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", "42");
        claims.put("iss", "token-service");
        claims.put("aud", List.of("facility-agent"));
        claims.put("iat", Date.from(NOW));
        claims.put("exp", Date.from(NOW.plusSeconds(30)));
        claims.put("role", "STUDENT");
        claims.put("intended_action", "invoke");
        claims.put("delegated_by", "chat-backend");
        claims.put("jti", "test-jti");
        return claims;
    }

    private Map<String, Object> with(String key, Object value) {
        Map<String, Object> claims = validClaims();
        claims.put(key, value);
        return claims;
    }

    private Map<String, Object> without(String key) {
        Map<String, Object> claims = validClaims();
        claims.remove(key);
        return claims;
    }

    private String rs256(Map<String, Object> claims) {
        return Jwts.builder().claims(claims).signWith(privateKey, Jwts.SIG.RS256).compact();
    }

    private void assertInvalid(Map<String, Object> claims) {
        assertInvalid(rs256(claims));
    }

    private void assertInvalid(String token) {
        assertThatThrownBy(() -> verifier().verify(token))
                .isInstanceOf(DelegationTokenVerificationException.class);
    }

    private Instant tokenIssuedAt(String token) {
        String payload = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]),
                StandardCharsets.UTF_8);
        Matcher matcher = IAT_PATTERN.matcher(payload);
        assertThat(matcher.find()).isTrue();
        return Instant.ofEpochSecond(Long.parseLong(matcher.group(1)));
    }
}
