# 通信安全说明（Communication Security）

> 本文档描述 CampusLink 各服务之间的通信安全链路。代码注释中多处引用"对齐通信安全说明文档"即指本文档。
> 最近更新：RS256 Delegation Token 链路已全线打通（内嵌 Token Service）。

## 一、整体链路

```
前端 (frontend_web)
  │  ① 用户 JWT（HS256，Bearer）
  ▼
Chat Backend (Java Spring Boot, :8080)
  │  ② HMAC-SHA256 签名 + Nonce + Timestamp（AGENT_SHARED_SECRET）
  │     POST /chat/stream（SSE）
  ▼
编排层 (agent/chat_core, :8000)
  │  ③ 兑换 RS256 Delegation Token：POST /internal/token/exchange（HMAC 头）
  │  ④ MCP streamable HTTP：Authorization: Bearer <RS256 token> + X-Timestamp
  ▼
Domain Agent / Utility Tool（独立 MCP Server，:8081~8090）
```

## 二、各跳安全机制

### ① 前端 → Chat Backend：用户 JWT

- 登录/注册返回 HS256 用户 JWT；前端以 `Authorization: Bearer <JWT>` 调用 `/api/**`。
- `SecurityConfig`：`/api/auth/**` 与健康检查放行；`/api/chat/**` 必须认证；其余默认拒绝。
- **原始用户 JWT 不离开 Chat Backend**（不转发给编排层或任何 Agent）。

### ② Chat Backend → 编排层：HMAC 请求签名

- 算法：`hex(hmac_sha256(body + ":" + nonce + ":" + timestamp))`，小写 hex
  （Java `HexFormat` 与 Python `hmac.new(...).hexdigest()` 对齐）。
- 头：`X-Nonce`、`X-Timestamp`、`X-Signature`、`X-Trace-Id`。
- 密钥：`AGENT_SHARED_SECRET`（`.env` 配置，两端共用）。
- 编排层入站校验：缺头/签名错/超时窗口（30s）/nonce 重放（60s）→ 401。

### ③ 编排层 → Token Service（当前内嵌于 Chat Backend）：兑换 RS256 Delegation Token

- 端点：`POST /internal/token/exchange`
- 请求体：`{ "userId", "role", "targetAgent", "intendedAction", "jti" }`
  - `jti`：编排层**即将用于调用该 Agent 的 `X-Nonce`**，后端将其绑定为 token 的 `jti`，
    保证 Agent 端可校验 `claims.jti == X-Nonce`（防重放一致性）。
- 认证：与 ② 相同的 HMAC 头（后端 `TokenExchangeController` 复验；仅编排层持有密钥）。
- 响应：`{ "token", "expiresInSeconds", "algorithm": "RS256", "kid" }`
  - token 为 **RS256** 签名（RSA 2048，密钥持久化于 `backend/keys/delegation-rsa-*.pem`，
    首次启动自动生成，重启后仍可验证）。
  - `aud` = `targetAgent`（防跨 Agent 滥用）；TTL 默认 30s。
- 公钥端点：`GET /.well-known/jwks.json`（公开，供 Agent 端验签；`kid` 按 RFC 7638 指纹）。

### ④ 编排层 → Agent / Utility Tool（Sprint 3：MCP streamable HTTP）

- 编排层作为 **MCP 客户端**（`mcp.streamable_http_client` + `ClientSession.call_tool`），
  每个 Agent/Utility 是独立 MCP Server（`agent/mcp_servers/`）
- 请求头：`Authorization: Bearer <Delegation Token>`、`X-Timestamp`（时间窗口）
- 自研 REST 时代的 **body HMAC 签名与 `jti == X-Nonce` 绑定已取消**：MCP 请求体由 SDK
  序列化为标准 JSON-RPC，传输完整性交给生产 TLS；防重放由 token 30s TTL + 时间窗口承担
- Token 获取：**RS256（Token Service）兑换，fail-closed**——兑换失败（未配置 / 网络 /
  非 2xx）编排层拒绝调用，**不降级本地签发**（HS256 回退已移除，2026-08-08）

### ⑤ Agent 端验证链（agent/mcp_servers/security.py `McpSecurityMiddleware`）

1. `Authorization: Bearer` 必需（MCP 请求含 initialize 握手，均经中间件）
2. Delegation Token 验签：
   - 配置 `TOKEN_SERVICE_JWKS_URL` → **RS256 模式**（PyJWKClient 从 JWKS 拉公钥）
   - 未配置 → HS256 模式（共享密钥，联调回退）
3. Claims 校验：`aud == agent_name`、`sub`/`exp` 必需
4. 可选时间窗口：`X-Timestamp` 与服务器时间差 ≤ 30s
5. 校验通过后身份写入 `request.state`（user_id / user_role）

> 注：`agent/shared/security.py`（自研 REST 时代的完整验证链）保留作为参考，
> MCP Server 已改用上述简化模型。

## 三、密钥清单

| 密钥 | 用途 | 位置 |
|------|------|------|
| `JWT_SECRET` | 用户会话 HS256 | `.env` |
| `AGENT_SHARED_SECRET` | 后端↔编排层↔Agent 的 HMAC 签名 | `.env` |
| RSA 密钥对（2048） | 后端签发 RS256 Delegation Token | `backend/keys/delegation-rsa-*.pem`（自动生成） |

## 四、安全端点一览（Chat Backend）

| 端点 | 认证 | 说明 |
|------|------|------|
| `GET /.well-known/jwks.json` | 公开 | RS256 验签公钥（仅含公钥） |
| `POST /internal/token/exchange` | HMAC（仅编排层） | 兑换 Delegation Token |
| `POST /api/chat/stream` | 用户 JWT | 聊天 SSE |

## 五、演进路径（Sprint 3+）

- **Token Service 独立部署**：`DelegationTokenProvider` 与 `TokenExchangeController` 的
  接口形态已按独立服务对齐。独立部署后仅需切换环境变量：
  - `TOKEN_SERVICE_URL` → 独立 Token Service 地址
  - `TOKEN_SERVICE_JWKS_URL` → 独立 Token Service 的 JWKS 端点
- 编排层 Token Service 不可用时 fail-closed 拒绝调用（HS256 本地回退已移除，2026-08-08）。
- Nonce 去重当前为单实例内存实现（后端与 Agent 端），多实例生产应换 Redis `SETNX`。
- 传输层生产启用 HTTPS / mTLS（`REQUIRE_HTTPS=true`）。
