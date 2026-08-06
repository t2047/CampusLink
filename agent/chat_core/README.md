# Chat Core — 编排层本地开发指南

## 依赖安装

```bash
cd agent/chat_core
pip install -e ".[dev]"
```

## 环境变量

复制根目录 `.env`（DEEPSEEK_API_KEY / AGENT_SHARED_SECRET 等已配置）。
`.env` 需在进程工作目录可读（或由部署环境注入）。

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

## Sprint 1 安全模式说明

- **编排层 ← Chat Backend**：共享密钥 HMAC（`AGENT_SHARED_SECRET`）
- **编排层 → Agent**：本地签发 HS256 Delegation Token（`AgentClient.issue_local_delegation_token`），Mock Agent 以 HS256 验签
- **Sprint 3+**：切换 Token Service RS256（JWKS 验签），代码路径已预留
