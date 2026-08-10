package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.dto.agent.AgentWebInvokeRequest;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Web 与 Agent 之间的安全代理。共享密钥只保存在服务端，绝不下发到浏览器。
 */
@Service
public class LostFoundAgentGateway {

    private static final String AGENT_NAME = "lost-found-agent";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI invokeUri;
    private final String sharedSecret;

    @Autowired
    public LostFoundAgentGateway(
            @Value("${app.agent.lost-found-url:http://localhost:8083}") String agentUrl,
            @Value("${app.agent.shared-secret:}") String sharedSecret) {
        this(
                new ObjectMapper(),
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                URI.create(agentUrl.replaceAll("/+$", "") + "/agent/invoke"),
                sharedSecret);
    }

    LostFoundAgentGateway(
            ObjectMapper objectMapper,
            HttpClient httpClient,
            URI invokeUri,
            String sharedSecret) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.invokeUri = invokeUri;
        this.sharedSecret = sharedSecret;
    }

    public Map<String, Object> invoke(AgentWebInvokeRequest request, User currentUser) {
        ensureConfigured();
        String nonce = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        long timestamp = Instant.now().getEpochSecond();

        try {
            byte[] body = objectMapper.writeValueAsBytes(request.toAgentPayload(traceId));
            HttpRequest agentRequest = HttpRequest.newBuilder(invokeUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + delegationToken(currentUser, nonce, timestamp))
                    .header("Content-Type", "application/json")
                    .header("X-Nonce", nonce)
                    .header("X-Timestamp", Long.toString(timestamp))
                    .header("X-Signature", signature(body, nonce, timestamp))
                    .header("X-Trace-Id", traceId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> response = httpClient.send(
                    agentRequest,
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LostFoundApiException(
                        HttpStatus.BAD_GATEWAY,
                        "AGENT_REQUEST_FAILED",
                        "Lost & Found Agent rejected the request");
            }
            Map<String, Object> payload = objectMapper.readValue(
                    response.body(),
                    new TypeReference<>() { });
            if (!(payload.get("response") instanceof String)
                    || !(payload.get("status") instanceof String)) {
                throw new LostFoundApiException(
                        HttpStatus.BAD_GATEWAY,
                        "AGENT_INVALID_RESPONSE",
                        "Lost & Found Agent returned an invalid response");
            }
            return payload;
        } catch (JsonProcessingException exception) {
            throw new LostFoundApiException(
                    HttpStatus.BAD_GATEWAY,
                    "AGENT_INVALID_RESPONSE",
                    "Lost & Found Agent returned an invalid response",
                    exception);
        } catch (IOException exception) {
            throw unavailable(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable(exception);
        } catch (GeneralSecurityException exception) {
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_SECURITY_CONFIGURATION_INVALID",
                    "Lost & Found Agent security is not configured correctly",
                    exception);
        }
    }

    private void ensureConfigured() {
        if (sharedSecret == null || sharedSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new LostFoundApiException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AGENT_NOT_CONFIGURED",
                    "Lost & Found Agent is not configured");
        }
    }

    private String delegationToken(User currentUser, String nonce, long timestamp) {
        SecretKey key = Keys.hmacShaKeyFor(sharedSecret.getBytes(StandardCharsets.UTF_8));
        Date issuedAt = Date.from(Instant.ofEpochSecond(timestamp));
        return Jwts.builder()
                .subject(currentUser.getId().toString())
                .claim("role", currentUser.getRole().name())
                .audience().add(AGENT_NAME).and()
                .issuer("chat-core")
                .issuedAt(issuedAt)
                .expiration(Date.from(issuedAt.toInstant().plusSeconds(30)))
                .id(nonce)
                .claim("intended_action", "invoke")
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    private String signature(byte[] body, String nonce, long timestamp)
            throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update(body);
        mac.update((byte) ':');
        mac.update(nonce.getBytes(StandardCharsets.UTF_8));
        mac.update((byte) ':');
        byte[] digest = mac.doFinal(Long.toString(timestamp).getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private LostFoundApiException unavailable(Exception exception) {
        return new LostFoundApiException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "AGENT_UNAVAILABLE",
                "Lost & Found Agent is temporarily unavailable",
                exception);
    }
}
