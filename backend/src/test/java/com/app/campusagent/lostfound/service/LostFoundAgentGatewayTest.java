package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.Role;
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.agent.AgentClassifyResponse;
import com.app.campusagent.lostfound.dto.agent.AgentClassifyWebRequest;
import com.app.campusagent.lostfound.dto.agent.AgentWebInvokeRequest;
import com.app.campusagent.lostfound.dto.agent.AgentWebSearchRequest;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LostFoundAgentGatewayTest {

    private static final String SECRET = "web-to-agent-test-secret-at-least-thirty-two-characters";
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void signsAndForwardsTheAuthenticatedUserRequest() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<Map<String, java.util.List<String>>> capturedHeaders = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/invoke", exchange -> {
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            capturedHeaders.set(exchange.getRequestHeaders());
            byte[] response = ("{\"response\":\"请补充地点\",\"status\":\"needs_more_info\","
                    + "\"match_results\":[],\"confirmation_required\":null,"
                    + "\"shared_context\":{\"intent\":\"report_lost\"},"
                    + "\"actions_taken\":[],\"request_id\":\"trace-1\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        LostFoundAgentGateway gateway = new LostFoundAgentGateway(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/agent/invoke"),
                SECRET);
        User user = user(42L, Role.STUDENT);
        AgentWebInvokeRequest request = new AgentWebInvokeRequest(
                "我丢了耳机",
                new AgentWebInvokeRequest.AgentConversationContext("session-1", Map.of()),
                false,
                null,
                List.of());

        Map<String, Object> response = gateway.invoke(request, user);

        assertEquals("needs_more_info", response.get("status"));
        assertNotNull(capturedBody.get());
        String nonce = firstHeader(capturedHeaders.get(), "X-nonce");
        String timestamp = firstHeader(capturedHeaders.get(), "X-timestamp");
        String signature = firstHeader(capturedHeaders.get(), "X-signature");
        assertEquals(expectedSignature(capturedBody.get(), nonce, timestamp), signature);

        String authorization = firstHeader(capturedHeaders.get(), "Authorization");
        String encodedHeader = authorization.substring("Bearer ".length()).split("\\.")[0];
        String jwtHeader = new String(
                Base64.getUrlDecoder().decode(encodedHeader),
                StandardCharsets.UTF_8);
        assertTrue(jwtHeader.contains("\"alg\":\"HS256\""));
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .requireIssuer("chat-core")
                .requireAudience("lost-found-agent")
                .build()
                .parseSignedClaims(authorization.substring("Bearer ".length()))
                .getPayload();
        assertEquals("42", claims.getSubject());
        assertEquals("STUDENT", claims.get("role", String.class));
        assertEquals("invoke", claims.get("intended_action", String.class));
        assertEquals(nonce, claims.getId());
        assertTrue(claims.getExpiration().getTime() - claims.getIssuedAt().getTime() <= 30_000);
    }

    @Test
    void rejectsInvocationWhenTheSharedSecretIsMissing() {
        LostFoundAgentGateway gateway = new LostFoundAgentGateway(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:1/agent/invoke"),
                "");
        AgentWebInvokeRequest request = new AgentWebInvokeRequest(
                "find headphones",
                new AgentWebInvokeRequest.AgentConversationContext("session-1", Map.of()),
                false,
                null,
                List.of());

        LostFoundApiException exception = assertThrows(
                LostFoundApiException.class,
                () -> gateway.invoke(request, user(42L, Role.STUDENT)));

        assertEquals("AGENT_NOT_CONFIGURED", exception.getCode());
    }

    @Test
    void treatsMissingConfirmationFlagAsFalse() {
        AgentWebInvokeRequest request = new AgentWebInvokeRequest(
                "我丢了耳机",
                new AgentWebInvokeRequest.AgentConversationContext("session-1", Map.of()),
                null,
                null,
                List.of());

        assertEquals(false, request.toAgentPayload("trace-1").get("confirmed"));
    }

    @Test
    void classifiesItemAndSignsWithClassifyAction() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<Map<String, java.util.List<String>>> capturedHeaders = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/classify", exchange -> {
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            capturedHeaders.set(exchange.getRequestHeaders());
            byte[] response = "{\"category\":\"ELECTRONICS\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        LostFoundAgentGateway gateway = new LostFoundAgentGateway(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/agent/invoke"),
                SECRET);
        User user = user(42L, Role.STUDENT);

        AgentClassifyResponse response = gateway.classify(
                new AgentClassifyWebRequest("黑色耳机"), user);

        assertEquals("ELECTRONICS", response.category());
        assertNotNull(capturedBody.get());
        assertTrue(new String(capturedBody.get(), StandardCharsets.UTF_8)
                .contains("\"item_name\":\"黑色耳机\""));

        String nonce = firstHeader(capturedHeaders.get(), "X-nonce");
        String timestamp = firstHeader(capturedHeaders.get(), "X-timestamp");
        String signature = firstHeader(capturedHeaders.get(), "X-signature");
        assertEquals(expectedSignature(capturedBody.get(), nonce, timestamp), signature);

        String authorization = firstHeader(capturedHeaders.get(), "Authorization");
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .requireIssuer("chat-core")
                .requireAudience("lost-found-agent")
                .build()
                .parseSignedClaims(authorization.substring("Bearer ".length()))
                .getPayload();
        assertEquals("classify", claims.get("intended_action", String.class));
        assertEquals(nonce, claims.getId());
    }

    @Test
    void classifiesToNullWhenAgentReturnsNullCategory() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/classify", exchange -> {
            byte[] response = "{\"category\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        LostFoundAgentGateway gateway = new LostFoundAgentGateway(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/agent/invoke"),
                SECRET);
        User user = user(42L, Role.STUDENT);

        AgentClassifyResponse response = gateway.classify(
                new AgentClassifyWebRequest("mystery gadget"), user);

        assertEquals(null, response.category());
    }

    @Test
    void searchesAndSignsWithSearchAction() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<Map<String, java.util.List<String>>> capturedHeaders = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/agent/search", exchange -> {
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            capturedHeaders.set(exchange.getRequestHeaders());
            byte[] response = ("{\"status\":\"match_found\",\"match_results\":[],"
                    + "\"request_id\":\"trace-search\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        LostFoundAgentGateway gateway = new LostFoundAgentGateway(
                new ObjectMapper(),
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/agent/invoke"),
                SECRET);
        User user = user(42L, Role.STUDENT);
        AgentWebSearchRequest request = new AgentWebSearchRequest(
                "FOUND",
                "耳机",
                null,
                "black",
                "中央图书馆",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 11),
                List.of(new AgentWebInvokeRequest.AgentImage(
                        "lost-found-staging/k.png",
                        "VF1:fp",
                        "/api/lost-found/images/staging/k.png")));

        Map<String, Object> response = gateway.search(request, user);

        assertEquals("match_found", response.get("status"));
        String body = new String(capturedBody.get(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"report_type\":\"FOUND\""));
        assertTrue(body.contains("\"keyword\":\"耳机\""));
        assertTrue(body.contains("\"date_from\":\"2026-08-01\""));
        assertTrue(body.contains("\"object_key\":\"lost-found-staging/k.png\""));
        assertTrue(body.contains("\"visual_fingerprint\":\"VF1:fp\""));

        String nonce = firstHeader(capturedHeaders.get(), "X-nonce");
        String timestamp = firstHeader(capturedHeaders.get(), "X-timestamp");
        String signature = firstHeader(capturedHeaders.get(), "X-signature");
        assertEquals(expectedSignature(capturedBody.get(), nonce, timestamp), signature);

        String authorization = firstHeader(capturedHeaders.get(), "Authorization");
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .requireIssuer("chat-core")
                .requireAudience("lost-found-agent")
                .build()
                .parseSignedClaims(authorization.substring("Bearer ".length()))
                .getPayload();
        assertEquals("42", claims.getSubject());
        assertEquals("search", claims.get("intended_action", String.class));
        assertEquals(nonce, claims.getId());
    }

    private User user(Long id, Role role) {
        User user = new User("student@example.com", "unused");
        ReflectionTestUtils.setField(user, "id", id);
        user.setRole(role);
        return user;
    }

    private String firstHeader(Map<String, java.util.List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow()
                .getValue()
                .getFirst();
    }

    private String expectedSignature(byte[] body, String nonce, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(body);
        mac.update((byte) ':');
        mac.update(nonce.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) ':');
        return java.util.HexFormat.of().formatHex(
                mac.doFinal(timestamp.getBytes(StandardCharsets.UTF_8)));
    }
}
