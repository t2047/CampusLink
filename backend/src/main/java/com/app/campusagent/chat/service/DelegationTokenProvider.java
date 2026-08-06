package com.app.campusagent.chat.service;

import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Delegation Token 签发器 — RS256 签名。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>启动时生成或加载 RSA 密钥对（持久化到配置目录，重启后 Token 仍可验证）</li>
 *   <li>用 RS256 签发 Agent Delegation Token（默认 30s TTL）</li>
 *   <li>按 RFC 7638 生成 JWKS（JSON Web Key Set），供 Agent 端验签</li>
 * </ul>
 *
 * <p>演进路径：Sprint 3+ 由独立 Token Service 接管本组件职责，
 * 通过 {@code POST /internal/token/exchange} 签发；本类保留为
 * Chat Backend 内嵌实现，接口形态与 Token Service 对齐，切换成本低。</p>
 *
 * <p>安全要点：</p>
 * <ul>
 *   <li>私钥只存在于内存与密钥文件，绝不出现在日志 / 响应体中</li>
 *   <li>密钥文件权限在首次生成时收紧为仅属主可读写（POSIX 600）</li>
 *   <li>{@code kid} 使用 RFC 7638 指纹，Agent 端 JWKS 缓存可正确轮换</li>
 * </ul>
 */
@Component
public class DelegationTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(DelegationTokenProvider.class);

    /** RSA 密钥长度（行业标准：2048 位）。 */
    private static final int RSA_KEY_SIZE = 2048;
    /** 签发者标识。 */
    private static final String ISSUER = "token-service";
    /** 委派来源标识。 */
    private static final String DELEGATED_BY = "chat-backend";
    /** 签名算法（行业标准：RS256）。 */
    private static final String ALGORITHM = "RS256";

    /** 密钥目录（环境变量 DELEGATION_KEY_DIR 可覆盖，默认 ./keys）。 */
    @Value("${app.chat.delegation-key-dir:./keys}")
    private String keyDir;

    /** Delegation Token 有效期（默认 30 秒，与防重放窗口一致）。 */
    @Value("${app.chat.delegation-token-ttl-seconds:30}")
    private long tokenTtlSeconds;

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    /** RFC 7638 密钥指纹，供 JWKS 与 Agent 验签匹配。 */
    private String keyId;

    @PostConstruct
    public void init() {
        try {
            Path dir = Paths.get(keyDir).toAbsolutePath();
            Files.createDirectories(dir);
            Path privateKeyFile = dir.resolve("delegation-rsa-private.pem");
            Path publicKeyFile = dir.resolve("delegation-rsa-public.pem");

            if (Files.exists(privateKeyFile) && Files.exists(publicKeyFile)) {
                loadKeys(privateKeyFile, publicKeyFile);
                log.info("Loaded existing RSA key pair from {}", dir);
            } else {
                generateAndPersistKeys(privateKeyFile, publicKeyFile);
                log.info("Generated new RSA key pair at {}", dir);
            }

            this.keyId = computeKeyId();
            log.info("DelegationTokenProvider ready (algorithm={}, ttl={}s, kid={})",
                    ALGORITHM, tokenTtlSeconds, keyId);
        } catch (Exception e) {
            // 启动失败直接抛出，避免以无密钥状态对外签发
            throw new IllegalStateException("Failed to initialize DelegationTokenProvider", e);
        }
    }

    /**
     * 签发 Agent Delegation Token。
     *
     * @param userId         原始用户 ID（来自已验签的用户 JWT）
     * @param role           用户角色（如 STUDENT）
     * @param targetAgent    目标 Agent 名称（aud，防跨 Agent 滥用）
     * @param intendedAction 预期操作（如 invoke；后续可细化到具体 Tool）
     * @return 签好的 JWT 字符串
     */
    public String issueDelegationToken(String userId, String role, String targetAgent, String intendedAction) {
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofSeconds(tokenTtlSeconds));
        String nonce = UUID.randomUUID().toString();

        return Jwts.builder()
                .subject(userId)
                .claim("role", role)
                .audience().add(targetAgent).and()
                .issuer(ISSUER)
                .claim("intended_action", intendedAction)
                .claim("delegated_by", DELEGATED_BY)
                .id(nonce)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * 返回 JWKS JSON（{@code GET /.well-known/jwks.json} 的响应体）。
     * 仅含公钥，无任何敏感信息。
     */
    public String jwkSetJson() {
        BigInteger modulus = publicKey.getModulus();
        BigInteger exponent = publicKey.getPublicExponent();

        String n = base64UrlNoPadding(toUnsignedBytes(modulus));
        String e = base64UrlNoPadding(toUnsignedBytes(exponent));

        return "{\"keys\":[{"
                + "\"kty\":\"RSA\","
                + "\"kid\":\"" + keyId + "\","
                + "\"use\":\"sig\","
                + "\"alg\":\"" + ALGORITHM + "\","
                + "\"n\":\"" + n + "\","
                + "\"e\":\"" + e + "\""
                + "}]}";
    }

    /** 当前密钥指纹（调试 / 日志用）。 */
    public String getKeyId() {
        return keyId;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 私有方法
    // ──────────────────────────────────────────────────────────────────────

    private void loadKeys(Path privateKeyFile, Path publicKeyFile) throws Exception {
        byte[] privateDer = decodePem(Files.readString(privateKeyFile), "PRIVATE KEY");
        byte[] publicDer = decodePem(Files.readString(publicKeyFile), "PUBLIC KEY");

        KeyFactory kf = KeyFactory.getInstance("RSA");
        this.privateKey = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(privateDer));
        this.publicKey = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(publicDer));
    }

    private void generateAndPersistKeys(Path privateKeyFile, Path publicKeyFile) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(RSA_KEY_SIZE);
        KeyPair pair = generator.generateKeyPair();

        this.privateKey = (RSAPrivateKey) pair.getPrivate();
        this.publicKey = (RSAPublicKey) pair.getPublic();

        Files.writeString(privateKeyFile, toPem("PRIVATE KEY", privateKey.getEncoded()));
        Files.writeString(publicKeyFile, toPem("PUBLIC KEY", publicKey.getEncoded()));

        // 收紧私钥文件权限（POSIX 600），Windows 无 POSIX 权限则跳过
        try {
            Files.setPosixFilePermissions(privateKeyFile,
                    java.util.Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            log.debug("POSIX permissions not supported on this filesystem; skipped");
        }
    }

    /** 计算 RFC 7638 JWK SHA-256 指纹作为 kid。 */
    private String computeKeyId() throws Exception {
        BigInteger modulus = publicKey.getModulus();
        BigInteger exponent = publicKey.getPublicExponent();
        String n = base64UrlNoPadding(toUnsignedBytes(modulus));
        String e = base64UrlNoPadding(toUnsignedBytes(exponent));

        // RFC 7638: 对 {"e":"...","kty":"RSA","n":"..."} 规范形式做 SHA-256
        String canonical = "{\"e\":\"" + e + "\",\"kty\":\"RSA\",\"n\":\"" + n + "\"}";
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
        return base64UrlNoPadding(hash);
    }

    private static String toPem(String label, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + base64 + "\n-----END " + label + "-----\n";
    }

    private static byte[] decodePem(String pem, String label) {
        String cleaned = pem
                .replace("-----BEGIN " + label + "-----", "")
                .replace("-----END " + label + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }

    private static byte[] toUnsignedBytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static String base64UrlNoPadding(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
