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
# 开发模式（热重载）：改代码自动重启；自动加载仓库根 .env，无需手动 source
uvicorn orchestration.main:app --host 0.0.0.0 --port 8000 --reload
# 生产模式：去掉 --reload；--host 0.0.0.0 按部署需要（本机调试可省略，默认 127.0.0.1）
# uvicorn orchestration.main:app --port 8000
```

> 无需 `source .env`：`orchestration/main.py` 启动时自动 `load_dotenv(find_dotenv())`。
> （可选）如需 shell 级变量可手动加载：Linux `set -a && source ../../.env && set +a`；
> Windows PowerShell `powershell -ExecutionPolicy Bypass -File ..\load_env.ps1`。

验证：`curl http://localhost:8000/health`

## 启动 Agent / Utility MCP Server（Sprint 3）

每个领域 Agent 一个独立 MCP Server（streamable HTTP），Utility 一个（8090）。
`domain_server.py` / `utility_server.py` 启动时自动加载仓库根 `.env`（无需手动 source）：

```bash
# 终端 1：Mail Agent MCP Server（端口 8081）
# 必须在 agent/ 目录下运行（mcp_servers 包位于 agent/）；开发模式追加 --reload
cd agent
MCP_AGENT_NAME=mail-agent uvicorn mcp_servers.domain_server:app --host 0.0.0.0 --port 8081 --reload

# 终端 2：Facility Agent（8082）
uvicorn mcp_servers.facilities_server:app --host 0.0.0.0 --port 8082 --reload

# 终端 3：Utility Tools MCP Server（8090）
uvicorn mcp_servers.utility_server:app --host 0.0.0.0 --port 8090 --reload
```

> 生产模式去掉 `--reload`（`--host 0.0.0.0` 按部署需要，本机调试可省略）。

> Windows PowerShell 注意：`$env:MCP_AGENT_NAME = "mail-agent"` 设置 Agent 名；
> .env 自动加载，无需额外命令。RS256 验签必需 `TOKEN_SERVICE_JWKS_URL`（.env 已含），
> 缺失时启动会打印 WARNING 且 MCP 请求全部 401。

MCP 端点均为 `http://localhost:<port>/mcp/`（`services.yaml` 的 `*_MCP_URL` 默认值）。
编排层通过 MCP streamable HTTP 调用（`Authorization: Bearer <Delegation Token>`）。

### Lost & Found 业务经 MCP 适配层接入

L&F 不是脚手架 mock，而是自研 Agent（规则引擎 + LLM 意图解析 + 确认流）通过
**MCP 适配层**暴露给编排层（`mcp_servers/lost_found_server.py`，与 domain/utility
同目录统一）：

```bash
# 终端：L&F MCP 适配层（端口 8085；REST /agent/invoke 仍为 8083）
cd agent
uvicorn mcp_servers.lost_found_server:app --host 0.0.0.0 --port 8085 --reload
```

- 编排层 `LOSTFOUND_AGENT_MCP_URL` 指向 `http://localhost:8085/mcp/`
- 验签与其他 Agent 一致：RS256 Delegation Token（aud=lost-found-agent，JWKS）
- 工具 `invoke` 输出与原 `/agent/invoke` 契约一致（含 `needs_confirmation` /
  `confirmation_required`，编排层 HITL 确认流可直接复用）
- REST 通道（8083，HS256 直连后端）保持不变，互不影响

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

### HITL 确认恢复（`POST /chat/resume`）

子 Agent 需要人工确认（`needs_confirmation`）时，前端确认/取消后调用该端点，
以 `Command(resume={"approved": ...})` 恢复挂起的 LangGraph，确认后以
`confirmed=true + confirmation_id` 重调同一子 Agent 执行写操作，结果经 SSE 流式返回。

```bash
curl -X POST http://localhost:8000/chat/resume \
  -H "X-Nonce: <nonce>" -H "X-Timestamp: <ts>" -H "X-Signature: <sig>" \
  -H "Content-Type: application/json" \
  -d '{"userId":"u1","role":"STUDENT","sessionId":"<会话ID>","approved":true,"traceId":"t1"}'
```

安全校验（入站 HMAC 之外）：
- **所有权**：checkpoint 内 `user_id` 必须与调用者一致，否则 `403`（防 sessionId 横向越权）
- **中断态**：thread 必须确实停在 `human_approval` 中断，否则 `409`（防双重提交导致写操作重复执行）
- `sessionId` 必须与原始 `/chat/stream` 一致（resume 按原始 thread_id 恢复 checkpoint）

### 查看 L&F 原始输出（开发探针）

`agent/chat_core/_probe_lf.py` 直连 L&F MCP 网关（绕过编排层进程），打印网关原始响应
（`response` / `status` / `shared_context` / 缺失字段），用于排查"回复内容不符预期"：

```bash
cd agent/chat_core
& "D:/Programing/Anaconda3/envs/RAG/python.exe" _probe_lf.py "我掉了一个黑色手机"
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
