package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.repository.UserRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LostFoundAgentInternalSecurityIntegrationTest {

    private static final String SECRET =
            "test-agent-backend-shared-secret-at-least-thirty-two-characters";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LostFoundReportRepository reportRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.findByEmail("agent-user@campuslink.test")
                .orElseGet(() -> userRepository.save(
                        new User("agent-user@campuslink.test", "not-used-by-agent")));
    }

    @AfterEach
    void cleanUp() {
        reportRepository.deleteAll(reportRepository.findAll().stream()
                .filter(report -> report.getCreatedBy().getId().equals(user.getId()))
                .toList());
    }

    @Test
    void rejectsMissingDelegationToken() throws Exception {
        mockMvc.perform(get("/api/internal/lost-found/candidates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsValidDelegationTokenAndUsesRealUser() throws Exception {
        mockMvc.perform(post("/api/internal/lost-found/reports/lost")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "report_lost", 0, 30, UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemName": "Black headphones",
                                  "category": "ELECTRONICS",
                                  "description": "Black wireless headphones in a fabric case",
                                  "colour": "Black",
                                  "location": "Central Library",
                                  "eventDate": "2026-08-08"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reportType").value("LOST"))
                .andExpect(jsonPath("$.createdByMe").value(true));
    }

    @Test
    void foundReportCanSearchOpenLostCandidatesWithDisplayFields() throws Exception {
        mockMvc.perform(post("/api/internal/lost-found/reports/lost")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "report_lost", 0, 30,
                                UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "itemName": "Red umbrella",
                                  "category": "UMBRELLA",
                                  "description": "Red folding umbrella with a white handle label",
                                  "colour": "Red",
                                  "location": "UHC",
                                  "eventDate": "2026-08-09"
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/internal/lost-found/lost-candidates")
                        .param("category", "UMBRELLA")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "search_lost_items", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reportType").value("LOST"))
                .andExpect(jsonPath("$.content[0].itemName").value("Red umbrella"))
                .andExpect(jsonPath("$.content[0].colour").value("Red"))
                .andExpect(jsonPath("$.content[0].location").value("UHC"))
                .andExpect(jsonPath("$.content[0].eventDate").value("2026-08-09"))
                .andExpect(jsonPath("$.content[0].imageUrls").isArray());
    }

    @Test
    void lostCandidateEndpointRejectsFoundSearchToken() throws Exception {
        mockMvc.perform(get("/api/internal/lost-found/lost-candidates")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "search_found_items", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        mockMvc.perform(get("/api/internal/lost-found/candidates")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "another-api", "search_found_items", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsActionEscalation() throws Exception {
        mockMvc.perform(post("/api/internal/lost-found/reports/999/claims")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "get_item_detail", 0, 30,
                                UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"proofDescription\":\"This is my unique item proof\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        mockMvc.perform(get("/api/internal/lost-found/candidates")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "search_found_items", -90, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTokenWithLifetimeOverSixtySeconds() throws Exception {
        mockMvc.perform(get("/api/internal/lost-found/candidates")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "search_found_items", 0, 61,
                                UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsReplayOfTokenId() throws Exception {
        String tokenId = UUID.randomUUID().toString();
        String delegation = token(
                user.getId(), "campus-api", "search_found_items", 0, 30, tokenId);

        mockMvc.perform(get("/api/internal/lost-found/candidates")
                        .header("Authorization", "Bearer " + delegation))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/internal/lost-found/candidates")
                        .header("Authorization", "Bearer " + delegation))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsUnknownDelegatedUser() throws Exception {
        mockMvc.perform(get("/api/internal/lost-found/candidates")
                        .header("Authorization", "Bearer " + token(
                                Long.MAX_VALUE, "campus-api", "search_found_items", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
    }

    private String token(
            Long userId,
            String audience,
            String action,
            long issuedOffsetSeconds,
            long lifetimeSeconds,
            String tokenId) {
        Instant issuedAt = Instant.now().plusSeconds(issuedOffsetSeconds);
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .issuer("lost-found-agent")
                .audience().add(audience).and()
                .subject(userId.toString())
                .id(tokenId)
                .claim("intended_action", action)
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(issuedAt.plusSeconds(lifetimeSeconds)))
                .signWith(key)
                .compact();
    }
}
