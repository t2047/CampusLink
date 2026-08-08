package com.app.campusagent.controller;

import com.app.campusagent.chat.config.ChatProperties;
import com.app.campusagent.chat.controller.TokenExchangeController;
import com.app.campusagent.chat.service.DelegationTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token Exchange 端点测试（内嵌 Token Service）。
 *
 * <p>覆盖：HMAC 签名合法/非法、缺 Header、时间窗口过期、Nonce 重放、请求体校验、JWKS 端点。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TokenExchangeController Tests")
class TokenExchangeControllerTest {

    private static final String SECRET = "test-shared-secret";
    private static final String BODY =
            "{\"userId\":\"u1\",\"role\":\"STUDENT\",\"targetAgent\":\"mail-agent\",\"jti\":\"req-nonce-1\"}";
    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.mockRs256Token";

    private MockMvc mockMvc;

    @Mock
    private DelegationTokenProvider delegationTokenProvider;

    @BeforeEach
    void setUp() {
        ChatProperties properties = new ChatProperties();
        properties.setSharedSecret(SECRET);
        properties.setDelegationTokenTtlSeconds(30);

        // lenient：多数测试只覆盖 401/400 路径，不会用到全部 stub（避免 UnnecessaryStubbingException）
        lenient().when(delegationTokenProvider.issueDelegationToken(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(TOKEN);
        lenient().when(delegationTokenProvider.getKeyId()).thenReturn("test-kid");
        lenient().when(delegationTokenProvider.jwkSetJson())
                .thenReturn("{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test-kid\"}]}");

        TokenExchangeController controller =
                new TokenExchangeController(delegationTokenProvider, properties, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private static String sign(String body, String nonce, long timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((body + ":" + nonce + ":" + timestamp)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String[] validHeaders() {
        String nonce = UUID.randomUUID().toString();
        long ts = Instant.now().getEpochSecond();
        return new String[]{
                "X-Nonce", nonce,
                "X-Timestamp", String.valueOf(ts),
                "X-Signature", sign(BODY, nonce, ts),
        };
    }

    @Nested
    @DisplayName("POST /internal/token/exchange")
    class ExchangeEndpoint {

        @Test
        @DisplayName("✅ 200 - 合法 HMAC 请求签发 RS256 token")
        void shouldIssueTokenWithValidSignature() throws Exception {
            String[] headers = validHeaders();
            mockMvc.perform(post("/internal/token/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Nonce", headers[1])
                            .header("X-Timestamp", headers[3])
                            .header("X-Signature", headers[5])
                            .content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").value(TOKEN))
                    .andExpect(jsonPath("$.expiresInSeconds").value(30))
                    .andExpect(jsonPath("$.algorithm").value("RS256"))
                    .andExpect(jsonPath("$.kid").value("test-kid"));
        }

        @Test
        @DisplayName("❌ 401 - 缺安全 Header")
        void shouldRejectMissingHeaders() throws Exception {
            mockMvc.perform(post("/internal/token/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("❌ 401 - 签名错误")
        void shouldRejectBadSignature() throws Exception {
            String nonce = UUID.randomUUID().toString();
            long ts = Instant.now().getEpochSecond();
            mockMvc.perform(post("/internal/token/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Nonce", nonce)
                            .header("X-Timestamp", String.valueOf(ts))
                            .header("X-Signature", "deadbeef")
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("❌ 401 - Timestamp 超出时间窗口")
        void shouldRejectExpiredTimestamp() throws Exception {
            String nonce = UUID.randomUUID().toString();
            long ts = Instant.now().getEpochSecond() - 100;
            mockMvc.perform(post("/internal/token/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Nonce", nonce)
                            .header("X-Timestamp", String.valueOf(ts))
                            .header("X-Signature", sign(BODY, nonce, ts))
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("❌ 401 - Nonce 重放")
        void shouldRejectNonceReplay() throws Exception {
            String nonce = UUID.randomUUID().toString();
            long ts = Instant.now().getEpochSecond();
            String sig = sign(BODY, nonce, ts);

            mockMvc.perform(post("/internal/token/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Nonce", nonce)
                            .header("X-Timestamp", String.valueOf(ts))
                            .header("X-Signature", sig)
                            .content(BODY))
                    .andExpect(status().isOk());

            // 同一 nonce 再次使用 → 401
            mockMvc.perform(post("/internal/token/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Nonce", nonce)
                            .header("X-Timestamp", String.valueOf(ts))
                            .header("X-Signature", sig)
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("❌ 401 - 共享密钥未配置时拒绝兑换（fail-fast）")
        void shouldRejectWhenSharedSecretMissing() throws Exception {
            ChatProperties emptyProps = new ChatProperties();
            emptyProps.setSharedSecret("");
            TokenExchangeController controller = new TokenExchangeController(
                    delegationTokenProvider, emptyProps, new ObjectMapper());
            MockMvc emptyMvc = MockMvcBuilders.standaloneSetup(controller).build();

            String[] headers = validHeaders();
            emptyMvc.perform(post("/internal/token/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Nonce", headers[1])
                            .header("X-Timestamp", headers[3])
                            .header("X-Signature", headers[5])
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("❌ 400 - 请求体缺少必填字段")
        void shouldRejectMissingFields() throws Exception {
            String badBody = "{\"userId\":\"u1\"}";
            String nonce = UUID.randomUUID().toString();
            long ts = Instant.now().getEpochSecond();
            mockMvc.perform(post("/internal/token/exchange")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-Nonce", nonce)
                            .header("X-Timestamp", String.valueOf(ts))
                            .header("X-Signature", sign(badBody, nonce, ts))
                            .content(badBody))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("GET /.well-known/jwks.json")
    class JwksEndpoint {

        @Test
        @DisplayName("✅ 200 - 返回公钥 JWKS")
        void shouldReturnJwks() throws Exception {
            mockMvc.perform(get("/.well-known/jwks.json"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.keys[0].kid").value("test-kid"));
        }
    }
}
