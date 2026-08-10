package com.app.campusagent.facilities.security;

import com.app.campusagent.chat.service.DelegationTokenProvider;
import com.app.campusagent.domain.User;
import com.app.campusagent.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** Authenticates RS256 Chat Core delegation tokens only for the Facilities MCP endpoint. */
@Component
public class FacilityMcpDelegationAuthFilter extends OncePerRequestFilter {

    private static final String MCP_PATH = "/mcp";
    private static final int MAX_JOSE_HEADER_LENGTH = 4096;

    private final DelegationTokenVerifier tokenVerifier;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Autowired
    public FacilityMcpDelegationAuthFilter(
            DelegationTokenProvider tokenProvider,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this(new DelegationTokenVerifier(tokenProvider), userRepository, objectMapper);
    }

    FacilityMcpDelegationAuthFilter(
            DelegationTokenVerifier tokenVerifier,
            UserRepository userRepository,
            ObjectMapper objectMapper) {
        this.tokenVerifier = tokenVerifier;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = StringUtils.hasText(contextPath) && requestUri.startsWith(contextPath)
                ? requestUri.substring(contextPath.length())
                : requestUri;
        return !(MCP_PATH.equals(path) || path.startsWith(MCP_PATH + "/"));
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = bearerToken(request);
        if (!StringUtils.hasText(token)) {
            filterChain.doFilter(request, response);
            return;
        }

        String algorithm = joseAlgorithm(token);
        if ("HS256".equals(algorithm)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!"RS256".equals(algorithm)) {
            reject(response);
            return;
        }

        try {
            VerifiedDelegationClaims claims = tokenVerifier.verify(token);
            User user = userRepository.findById(claims.userId()).orElse(null);
            if (user == null || user.getRole() == null || user.getRole() != claims.role()) {
                reject(response);
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (DelegationTokenVerificationException exception) {
            SecurityContextHolder.clearContext();
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return null;
        }
        String token = authorization.substring(7).trim();
        return StringUtils.hasText(token) ? token : null;
    }

    private String joseAlgorithm(String token) {
        try {
            String[] segments = token.split("\\.", -1);
            if (segments.length != 3
                    || !StringUtils.hasText(segments[0])
                    || segments[0].length() > MAX_JOSE_HEADER_LENGTH) {
                return null;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(segments[0]);
            JsonNode header = objectMapper.readTree(new String(decoded, StandardCharsets.UTF_8));
            return header.path("alg").asText(null);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid Facilities delegation token");
    }
}
