package com.app.campusagent.facilities.security;

import com.app.campusagent.chat.service.DelegationTokenProvider;
import com.app.campusagent.config.JwtTokenProvider;
import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Transactional
class FacilityMcpDualAuthIntegrationTest {

    private static final String INITIALIZE = """
            {
              "jsonrpc":"2.0",
              "id":1,
              "method":"initialize",
              "params":{
                "protocolVersion":"2025-11-25",
                "capabilities":{},
                "clientInfo":{"name":"facilities-dual-auth-test","version":"1.0.0"}
              }
            }
            """;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DelegationTokenProvider delegationTokenProvider;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void studentDelegationTokenCanInitializeMcp() throws Exception {
        assertDelegationRoleCanInitialize(Role.STUDENT);
    }

    @Test
    void adminDelegationTokenCanInitializeMcp() throws Exception {
        assertDelegationRoleCanInitialize(Role.ADMIN);
    }

    @Test
    void superAdminDelegationTokenCanInitializeMcp() throws Exception {
        assertDelegationRoleCanInitialize(Role.SUPER_ADMIN);
    }

    @Test
    void hs256LoginJwtCanStillInitializeMcp() throws Exception {
        User user = saveUser(Role.STUDENT);
        String token = jwtTokenProvider.generateToken(user.getEmail(), user.getRole().name());

        mockMvc.perform(mcpInitialize(token)).andExpect(status().isOk());
    }

    @Test
    void hs256LoginJwtStillAuthenticatesFacilitiesRest() throws Exception {
        User user = saveUser(Role.STUDENT);
        String token = jwtTokenProvider.generateToken(user.getEmail(), user.getRole().name());

        mockMvc.perform(get("/api/facilities/spaces")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    @Test
    void rs256DelegationTokenDoesNotAuthenticateFacilitiesRest() throws Exception {
        User user = saveUser(Role.STUDENT);
        String token = delegationToken(user, Role.STUDENT, "facility-agent", "invoke");

        mockMvc.perform(get("/api/facilities/spaces")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDelegationTokenForUnknownUser() throws Exception {
        String token = delegationTokenProvider.issueDelegationToken(
                "999999999", "STUDENT", "facility-agent", "invoke");

        mockMvc.perform(mcpInitialize(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDelegationTokenWhenRoleDoesNotMatchDatabase() throws Exception {
        User user = saveUser(Role.STUDENT);
        String token = delegationToken(user, Role.ADMIN, "facility-agent", "invoke");

        mockMvc.perform(mcpInitialize(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDelegationTokenWithWrongAudience() throws Exception {
        User user = saveUser(Role.STUDENT);
        String token = delegationToken(user, Role.STUDENT, "mail-agent", "invoke");

        mockMvc.perform(mcpInitialize(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDelegationTokenWithWrongIntendedAction() throws Exception {
        User user = saveUser(Role.STUDENT);
        String token = delegationToken(user, Role.STUDENT, "facility-agent", "create_booking");

        mockMvc.perform(mcpInitialize(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsDelegationTokenWithInvalidSignature() throws Exception {
        User user = saveUser(Role.STUDENT);
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        RSAPrivateKey wrongKey = (RSAPrivateKey) generator.generateKeyPair().getPrivate();
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(user.getId().toString())
                .issuer("token-service")
                .audience().add("facility-agent").and()
                .claim("role", user.getRole().name())
                .claim("intended_action", "invoke")
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(30)))
                .signWith(wrongKey, Jwts.SIG.RS256)
                .compact();

        mockMvc.perform(mcpInitialize(token)).andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMalformedBearerAtMcpEndpoint() throws Exception {
        mockMvc.perform(mcpInitialize("malformed-token"))
                .andExpect(status().isUnauthorized());
    }

    private void assertDelegationRoleCanInitialize(Role role) throws Exception {
        User user = saveUser(role);
        String token = delegationToken(user, role, "facility-agent", "invoke");

        mockMvc.perform(mcpInitialize(token)).andExpect(status().isOk());
    }

    private User saveUser(Role role) {
        User user = new User(
                "dual-auth-" + role.name().toLowerCase() + "-" + UUID.randomUUID() + "@test.edu",
                "encoded");
        user.setRole(role);
        return userRepository.saveAndFlush(user);
    }

    private String delegationToken(User user, Role role, String audience, String action) {
        return delegationTokenProvider.issueDelegationToken(
                user.getId().toString(), role.name(), audience, action);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder mcpInitialize(String token) {
        return post("/mcp")
                .header("Host", "localhost")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .content(INITIALIZE);
    }
}
