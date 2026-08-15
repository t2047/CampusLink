package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.repository.UserRepository;
import com.app.campusagent.lostfound.repository.LfChatSessionRepository;
import com.app.campusagent.lostfound.repository.LfUserMemoryFactRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LostFoundMemoryInternalSecurityIntegrationTest {

    private static final String SECRET =
            "test-agent-backend-shared-secret-at-least-thirty-two-characters";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LfChatSessionRepository sessionRepository;

    @Autowired
    private LfUserMemoryFactRepository factRepository;

    private final Set<User> createdUsers = new LinkedHashSet<>();

    private User user;

    @BeforeEach
    void setUp() {
        user = user("agent-memory-user@campuslink.test");
    }

    @AfterEach
    void cleanUp() {
        for (User u : createdUsers) {
            sessionRepository.findByUserIdOrderByLastActiveAtDesc(u.getId()).forEach(
                    session -> sessionRepository.delete(session));
            factRepository.findByUserIdOrderByUpdatedAtDesc(u.getId()).forEach(factRepository::delete);
        }
        createdUsers.clear();
    }

    private User user(String email) {
        User u = userRepository.findByEmail(email)
                .orElseGet(() -> userRepository.save(new User(email, "not-used-by-agent")));
        createdUsers.add(u);
        return u;
    }

    // ─────────────────────────── 鉴权 ───────────────────────────

    @Test
    void rejectsMissingDelegationToken() throws Exception {
        mockMvc.perform(get("/api/internal/lost-found/memory/users/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsActionEscalationOnMemoryEndpoints() throws Exception {
        mockMvc.perform(get("/api/internal/lost-found/memory/users/me")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "search_found_items", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsSessionIdTooLong() throws Exception {
        String sessionId = "s".repeat(201);
        mockMvc.perform(post("/api/internal/lost-found/memory/sessions")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "memory_upsert_session", 0, 30,
                                UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    // ─────────────────────────── 会话读写 ───────────────────────────

    @Test
    void upsertSessionIsIdempotent() throws Exception {
        String sessionId = "smoke-s1";
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/internal/lost-found/memory/sessions")
                            .header("Authorization", "Bearer " + token(
                                    user.getId(), "campus-api", "memory_upsert_session", 0, 30,
                                    UUID.randomUUID().toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"sessionId":"%s","summary":"t-%d","pendingConfirmation":{"confirmation_id":"abc"}}
                                    """.formatted(sessionId, i)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessionId").value(sessionId));
        }
        assertThat(sessionRepository.findByUserIdAndSessionId(user.getId(), sessionId))
                .isPresent();
        assertThat(sessionRepository.findAll().stream()
                .filter(s -> s.getUser().getId().equals(user.getId())
                        && s.getSessionId().equals(sessionId))
                .count()).isEqualTo(1);
    }

    @Test
    void appendAndReadMessagesPreserveExtractedFieldsAndOrder() throws Exception {
        String sessionId = "smoke-messages";
        createSession(sessionId);
        appendMessage(sessionId, "USER", "I lost my red umbrella",
                """
                        {"item_name":"red umbrella","category":"UMBRELLA","location":"UHC"}
                        """);
        appendMessage(sessionId, "AGENT", "May I know the time?", null);

        MvcResult result = mockMvc.perform(get("/api/internal/lost-found/memory/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "memory_read", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId))
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andExpect(jsonPath("$.messages[0].role").value("USER"))
                .andExpect(jsonPath("$.messages[1].role").value("AGENT"))
                .andExpect(jsonPath("$.messages[0].extractedFields.category").value("UMBRELLA"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("red umbrella");
    }

    @Test
    void ownershipIsScopedByUserId() throws Exception {
        String sessionId = "smoke-ownership";
        createSession(sessionId);

        User other = user("agent-memory-other@campuslink.test");
        mockMvc.perform(get("/api/internal/lost-found/memory/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + token(
                                other.getId(), "campus-api", "memory_read", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/internal/lost-found/memory/users/me")
                        .header("Authorization", "Bearer " + token(
                                other.getId(), "campus-api", "memory_read", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.length()").value(0));
    }

    // ─────────────────────────── 消息裁剪 ───────────────────────────

    @Test
    void pruneKeepsLatestMessages() throws Exception {
        String sessionId = "smoke-prune";
        createSession(sessionId);
        for (int i = 0; i < 15; i++) {
            appendMessage(sessionId, i % 2 == 0 ? "USER" : "AGENT",
                    "message " + i, null);
        }
        mockMvc.perform(post("/api/internal/lost-found/memory/sessions/" + sessionId + "/messages/prune")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "memory_prune_messages", 0, 30,
                                UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keepLatest\":12}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kept").value(12))
                .andExpect(jsonPath("$.deleted").value(3));

        mockMvc.perform(get("/api/internal/lost-found/memory/sessions/" + sessionId)
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "memory_read", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(12));
    }

    // ─────────────────────────── 用户事实 ───────────────────────────

    @Test
    void upsertFactDedupesOnTypeCategoryAndLocation() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/internal/lost-found/memory/users/me/facts")
                            .header("Authorization", "Bearer " + token(
                                    user.getId(), "campus-api", "memory_upsert_fact", 0, 30,
                                    UUID.randomUUID().toString()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"factType":"LOST_ITEM","itemName":"umbrella","category":"UMBRELLA",
                                     "location":"UHC","eventDate":"2026-08-0%d","status":"OPEN"}
                                    """.formatted(i + 1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.factType").value("LOST_ITEM"));
        }
        assertThat(factRepository.findByUserIdAndFactTypeOrderByUpdatedAtDesc(
                user.getId(), com.app.campusagent.lostfound.domain.LfFactType.LOST_ITEM)).hasSize(1);

        mockMvc.perform(get("/api/internal/lost-found/memory/users/me")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "memory_read", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.length()").value(1))
                .andExpect(jsonPath("$.facts[0].eventDate").value("2026-08-02"));
    }

    @Test
    void deleteClearsOnlyCurrentUserMemory() throws Exception {
        String sessionId = "smoke-delete";
        createSession(sessionId);
        upsertFact("LOST_ITEM", "wallet", "WALLET_PURSE", "Library");

        User other = user("agent-memory-delete-other@campuslink.test");
        createSession(other, "other-session");

        mockMvc.perform(delete("/api/internal/lost-found/memory/users/me")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "memory_delete", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedFacts").value(1))
                .andExpect(jsonPath("$.deletedSessions").value(1));

        mockMvc.perform(get("/api/internal/lost-found/memory/users/me")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "memory_read", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facts.length()").value(0));

        mockMvc.perform(get("/api/internal/lost-found/memory/sessions/other-session")
                        .header("Authorization", "Bearer " + token(
                                other.getId(), "campus-api", "memory_read", 0, 30,
                                UUID.randomUUID().toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("other-session"));
    }

    // ─────────────────────────── helpers ───────────────────────────

    private void createSession(String sessionId) throws Exception {
        createSession(user, sessionId);
    }

    private void createSession(User owner, String sessionId) throws Exception {
        mockMvc.perform(post("/api/internal/lost-found/memory/sessions")
                        .header("Authorization", "Bearer " + token(
                                owner.getId(), "campus-api", "memory_upsert_session", 0, 30,
                                UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"" + sessionId + "\"}"))
                .andExpect(status().isOk());
    }

    private void appendMessage(String sessionId, String role, String text, String extractedFields)
            throws Exception {
        String fieldsJson = extractedFields == null ? "null" : extractedFields;
        mockMvc.perform(post("/api/internal/lost-found/memory/sessions/" + sessionId + "/messages")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "memory_append", 0, 30,
                                UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role":"%s","messageText":"%s","extractedFields":%s}
                                """.formatted(role, text, fieldsJson)))
                .andExpect(status().isOk());
    }

    private void upsertFact(String factType, String itemName, String category, String location)
            throws Exception {
        mockMvc.perform(post("/api/internal/lost-found/memory/users/me/facts")
                        .header("Authorization", "Bearer " + token(
                                user.getId(), "campus-api", "memory_upsert_fact", 0, 30,
                                UUID.randomUUID().toString()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"factType":"%s","itemName":"%s","category":"%s","location":"%s"}
                                """.formatted(factType, itemName, category, location)))
                .andExpect(status().isOk());
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
