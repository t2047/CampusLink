# Chat Core — 编排层本地开发指南

## 依赖安装

```bash
cd agent/chat_core
pip install -e ".[dev]"
```

## 环境变量

复制根目录 `.env`（DEEPSEEK_API_KEY / AGENT_SHARED_SECRET / TOKEN_SERVICE_URL /
TOKEN_SERVICE_JWKS_URL 等已配置）。
**进程启动时自动加载仓库根目录 `.env`**（`load_dotenv(find_dotenv())`，向上查找、不覆盖
已设置的变量）——无需手动 `source`，Linux / macOS / Windows 命令完全一致。

| 变量 | 说明 |
|------|------|
| `AGENT_SHARED_SECRET` | 编排层 → Token Service 兑换请求的 HMAC 认证密钥（与 Chat Backend 共用） |
| `TOKEN_SERVICE_URL` | Token Service 地址（当前内嵌于 Chat Backend，`http://localhost:8080`） |
| `TOKEN_SERVICE_JWKS_URL` | Agent 端 RS256 验签公钥端点（`http://localhost:8080/.well-known/jwks.json`） |
| `DEEPSEEK_*` | 意图分类 / 闲聊 LLM |

## 启动编排层

```bash
cd agent/chat_core
uvicorn orchestration.main:app --port 8000   # 自动加载仓库根 .env，无需手动 source
```

> 无需 `source .env`：`orchestration/main.py` 启动时自动 `load_dotenv(find_dotenv())`。
> （可选）如需 shell 级变量可手动加载：Linux `set -a && source ../../.env && set +a`；
> Windows PowerShell `powershell -ExecutionPolicy Bypass -File ..\load_env.ps1`。

验证：`curl http://localhost:8000/health`

## 启动 Agent / Utility MCP Server（Sprint 3）

每个领域 Agent 一个独立 MCP Server（streamable HTTP，端口 8081-8084），Utility 一个（8090）。
`domain_server.py` / `utility_server.py` 启动时自动加载仓库根 `.env`（无需手动 source）：

```bash
# 终端 1：Mail Agent MCP Server（端口 8081）
# 必须在 agent/ 目录下运行（mcp_servers 包位于 agent/）
cd agent
MCP_AGENT_NAME=mail-agent uvicorn mcp_servers.domain_server:app --port 8081

# 终端 2：Facility Agent（8082）— 同理换 MCP_AGENT_NAME=facility-agent
# 终端 3：Utility Tools MCP Server（8090）
uvicorn mcp_servers.utility_server:app --port 8090
```

> Windows PowerShell 注意：`$env:MCP_AGENT_NAME = "mail-agent"` 设置 Agent 名；
> .env 自动加载，无需额外命令。RS256 验签必需 `TOKEN_SERVICE_JWKS_URL`（.env 已含），
> 缺失时启动会打印 WARNING 且 MCP 请求全部 401。

MCP 端点均为 `http://localhost:<port>/mcp/`（`services.yaml` 的 `*_MCP_URL` 默认值）。
编排层通过 MCP streamable HTTP 调用（`Authorization: Bearer <Delegation Token>`）。

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

## 安全模式说明（当前，Sprint 3 MCP）

- **编排层 ← Chat Backend**：共享密钥 HMAC（`AGENT_SHARED_SECRET`）+ Nonce/Timestamp 防重放
- **编排层 → Token Service**：`POST {TOKEN_SERVICE_URL}/internal/token/exchange` 兑换
  **RS256 Delegation Token**（HMAC 头认证）
- **编排层 → Agent / Utility（MCP）**：`Authorization: Bearer <token>` + `X-Timestamp`，
  Agent 端 `McpSecurityMiddleware` 验签 + `aud` 匹配；生产传输完整性由 TLS 保证
  （自研 REST 时代的 body HMAC 与 `jti==X-Nonce` 绑定已随 MCP 化取消）
- **Agent 端验签**：RS256（JWKS，`TOKEN_SERVICE_JWKS_URL`）；**未配置直接拒绝**（fail-closed）
- **失败兜底**：Token Service 不可用时编排层 fail-closed 拒绝调用（不再回退 HS256），
  工具/子 Agent 失败转主 Agent（LLM）生成友好回复
- **Sprint 3+**：Token Service 独立部署，仅切换 `TOKEN_SERVICE_URL` / `TOKEN_SERVICE_JWKS_URL`

完整链路见 [docs/communication-security.md](../../docs/communication-security.md)。
