package com.app.campusagent.facilities.security;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.repository.UserRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FacilityMcpDelegationAuthFilterTest {

    private DelegationTokenVerifier verifier;
    private UserRepository userRepository;
    private FacilityMcpDelegationAuthFilter filter;

    @BeforeEach
    void setUp() {
        verifier = mock(DelegationTokenVerifier.class);
        userRepository = mock(UserRepository.class);
        filter = new FacilityMcpDelegationAuthFilter(verifier, userRepository, new ObjectMapper());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticatesStudentWithDatabaseAuthority() throws Exception {
        assertAuthenticatesRole(Role.STUDENT);
    }

    @Test
    void authenticatesAdminWithDatabaseAuthority() throws Exception {
        assertAuthenticatesRole(Role.ADMIN);
    }

    @Test
    void authenticatesSuperAdminWithDatabaseAuthority() throws Exception {
        assertAuthenticatesRole(Role.SUPER_ADMIN);
    }

    @Test
    void rejectsUnknownUser() throws Exception {
        String token = tokenWithAlgorithm("RS256");
        when(verifier.verify(token)).thenReturn(claims(Role.STUDENT));
        when(userRepository.findById(42L)).thenReturn(Optional.empty());

        FilterResult result = invoke("/mcp", "Bearer " + token);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectsTokenRoleThatDoesNotMatchDatabaseRole() throws Exception {
        String token = tokenWithAlgorithm("RS256");
        User user = user(Role.ADMIN);
        when(verifier.verify(token)).thenReturn(claims(Role.STUDENT));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        FilterResult result = invoke("/mcp", "Bearer " + token);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void rejectsInvalidRs256WithoutFallingThrough() throws Exception {
        String token = tokenWithAlgorithm("RS256");
        when(verifier.verify(token)).thenThrow(
                new DelegationTokenVerificationException("Delegation token is invalid"));

        FilterResult result = invoke("/mcp", "Bearer " + token);

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
        verify(userRepository, never()).findById(42L);
    }

    @Test
    void skipsMissingBearerForSpringSecurityToHandle() throws Exception {
        FilterResult result = invoke("/mcp", null);

        assertThat(result.chainCalled()).isTrue();
        verify(verifier, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void leavesHs256TokenForJwtAuthFilter() throws Exception {
        String token = tokenWithAlgorithm("HS256");

        FilterResult result = invoke("/mcp", "Bearer " + token);

        assertThat(result.chainCalled()).isTrue();
        verify(verifier, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void rejectsUnknownAlgorithm() throws Exception {
        FilterResult result = invoke("/mcp", "Bearer " + tokenWithAlgorithm("RS512"));

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    void rejectsMalformedToken() throws Exception {
        FilterResult result = invoke("/mcp", "Bearer malformed-token");

        assertThat(result.response().getStatus()).isEqualTo(401);
        assertThat(result.chainCalled()).isFalse();
    }

    @Test
    void rs256DoesNotAuthenticateFacilitiesRestRequest() throws Exception {
        String token = tokenWithAlgorithm("RS256");

        FilterResult result = invoke("/api/facilities/spaces", "Bearer " + token);

        assertThat(result.chainCalled()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(verifier, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void matchesExactMcpPath() throws Exception {
        assertPathIsFiltered("/mcp");
    }

    @Test
    void matchesMcpTrailingSlash() throws Exception {
        assertPathIsFiltered("/mcp/");
    }

    @Test
    void matchesMcpDescendantPath() throws Exception {
        assertPathIsFiltered("/mcp/session/123");
    }

    @Test
    void doesNotMatchSimilarMcpPrefix() throws Exception {
        String token = tokenWithAlgorithm("RS256");

        FilterResult result = invoke("/mcproxy", "Bearer " + token);

        assertThat(result.chainCalled()).isTrue();
        verify(verifier, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void preservesExistingAuthenticatedPrincipal() throws Exception {
        User existing = user(Role.ADMIN);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(existing, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        FilterResult result = invoke("/mcp", "Bearer " + tokenWithAlgorithm("RS256"));

        assertThat(result.chainCalled()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(authentication);
        verify(verifier, never()).verify(org.mockito.ArgumentMatchers.anyString());
    }

    private void assertAuthenticatesRole(Role role) throws Exception {
        String token = tokenWithAlgorithm("RS256");
        User user = user(role);
        when(verifier.verify(token)).thenReturn(claims(role));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        FilterResult result = invoke("/mcp", "Bearer " + token);

        assertThat(result.chainCalled()).isTrue();
        assertThat(result.response().getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isSameAs(user);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority")
                .containsExactly("ROLE_" + role.name());
    }

    private void assertPathIsFiltered(String path) throws Exception {
        String token = tokenWithAlgorithm("RS256");
        User user = user(Role.STUDENT);
        when(verifier.verify(token)).thenReturn(claims(Role.STUDENT));
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        FilterResult result = invoke(path, "Bearer " + token);

        assertThat(result.chainCalled()).isTrue();
        verify(verifier).verify(token);
        SecurityContextHolder.clearContext();
    }

    private FilterResult invoke(String path, String authorization) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        FilterChain chain = (ignoredRequest, ignoredResponse) -> chainCalled.set(true);

        filter.doFilter(request, response, chain);
        return new FilterResult(response, chainCalled.get());
    }

    private VerifiedDelegationClaims claims(Role role) {
        return new VerifiedDelegationClaims(
                42L,
                role,
                "test-jti",
                Instant.parse("2026-08-10T10:00:00Z"),
                Instant.parse("2026-08-10T10:00:30Z"));
    }

    private User user(Role role) {
        User user = new User("filter-test@example.edu", "encoded");
        user.setRole(role);
        return user;
    }

    private String tokenWithAlgorithm(String algorithm) {
        String header = "{\"alg\":\"" + algorithm + "\",\"typ\":\"JWT\"}";
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(header.getBytes(StandardCharsets.UTF_8));
        return encoded + ".payload.signature";
    }

    private record FilterResult(MockHttpServletResponse response, boolean chainCalled) {
    }
}
