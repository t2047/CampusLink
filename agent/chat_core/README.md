# Chat Core — 编排层本地开发指南

## 依赖安装

```bash
cd agent/chat_core
pip install -e ".[dev]"
```

## 环境变量

复制根目录 `.env`（DEEPSEEK_API_KEY / AGENT_SHARED_SECRET / TOKEN_SERVICE_URL /
TOKEN_SERVICE_JWKS_URL 等已配置）。
`.env` 需在进程工作目录可读（或由部署环境注入）。

| 变量 | 说明 |
|------|------|
| `AGENT_SHARED_SECRET` | HMAC 请求签名密钥（与 Chat Backend 共用） |
| `TOKEN_SERVICE_URL` | Token Service 地址（当前内嵌于 Chat Backend，`http://localhost:8080`） |
| `TOKEN_SERVICE_JWKS_URL` | Agent 端 RS256 验签公钥端点（`http://localhost:8080/.well-known/jwks.json`） |
| `ALLOW_HS256_FALLBACK` | Token Service 不可用时是否回退本地 HS256（默认 `false` = fail-closed；本地联调在 `.env` 设 `true`） |
| `DEEPSEEK_*` | 意图分类 / 闲聊 LLM |

## 启动编排层

```bash
cd agent/chat_core
# 确保 .env 可被读取（python-dotenv 或 shell export）
set -a && source ../.env && set +a   # Linux/macOS
# Windows PowerShell: 逐条 $env:XXX=...
uvicorn orchestration.main:app --port 8000
```

验证：`curl http://localhost:8000/health`

## 启动 Mock Agent / Mock Utility（联调用）

```bash
# 终端 1：Mock Mail Agent（端口 8081）
# RS256 模式：设置 TOKEN_SERVICE_JWKS_URL 后 Agent 走 JWKS 验签；
# 不设置则退化为 HS256（联调回退模式，与编排层本地签发对应）
set -a && source ../.env && set +a
MOCK_AGENT_NAME=mail-agent uvicorn mock_agent:app --port 8081

# 终端 2：Mock Utility Tools（端口 8090）
set -a && source ../.env && set +a
uvicorn mock_utility:app --port 8090
```

## 端到端调用编排层（含安全 Headers）

```bash
# 计算 X-Signature 需要与编排层相同的 AGENT_SHARED_SECRET + body + nonce + timestamp
# 可用下方 Python 片段构造后 curl
python - <<'PY'
import hmac, hashlib, time, uuid, json, os
secret = os.environ["AGENT_SHARED_SECRET"]
body = json.dumps({"userId":"u1","role":"STUDENT","message":"帮我找张三的邮件","traceId":"t1"}, ensure_ascii=False, separators=(",",":"))
nonce = str(uuid.uuid4()); ts = int(time.time())
sig = hmac.new(secret.encode(), f"{body}:{nonce}:{ts}".encode(), hashlib.sha256).hexdigest()
print(f'X-Nonce: {nonce}\nX-Timestamp: {ts}\nX-Signature: {sig}')
print(f'BODY: {body}')
PY
```

然后用返回的 Header 调用：

```bash
curl -X POST http://localhost:8000/chat/stream \
  -H "X-Nonce: <nonce>" -H "X-Timestamp: <ts>" -H "X-Signature: <sig>" \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","role":"STUDENT","message":"帮我找张三的邮件","traceId":"t1"}'
```

## 运行测试

```bash
cd agent/chat_core
pytest -q
```

## 安全模式说明（当前）

- **编排层 ← Chat Backend**：共享密钥 HMAC（`AGENT_SHARED_SECRET`）+ Nonce/Timestamp 防重放
- **编排层 → Token Service**：`POST {TOKEN_SERVICE_URL}/internal/token/exchange` 兑换
  **RS256 Delegation Token**（HMAC 头认证；`jti` 绑定编排层即将使用的 `X-Nonce`）
- **Agent 端验签**：设置 `TOKEN_SERVICE_JWKS_URL` → RS256（PyJWKClient 拉公钥）；
  未设置 → HS256（共享密钥）
- **联调回退**：Token Service 不可用时编排层用 `AGENT_SHARED_SECRET` 本地签发 HS256
  Delegation Token（仅限本地联调；生产设 `ALLOW_HS256_FALLBACK=false` 即 fail-closed，
  禁止降级）
- **Sprint 3+**：Token Service 独立部署，仅切换 `TOKEN_SERVICE_URL` / `TOKEN_SERVICE_JWKS_URL`

完整链路见 [docs/communication-security.md](../../docs/communication-security.md)。
