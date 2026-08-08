# Agent 接口变动说明（Sprint 3 · 2026-08-08）

> 面向按 `agent/schemas/*.json` 实现 Domain Agent 的组员。未提及的契约不变。

**一句话**：传输层由自研 REST+SSE 改为 **MCP（streamable HTTP）**；`invoke` 业务契约保留并补齐 `confirmation_id`；Agent 内部进度流取消。

## 变动总览

| 契约 | 旧 | 新 | 你要做什么 |
|---|---|---|---|
| 传输 | `POST /agent/invoke`、`GET /agent/stream`（HTTP+SSE） | `POST/GET /mcp/`（MCP）+ `GET /health` | 改用 MCP |
| invoke 输入 | message / conversation_context / confirmed / trace_parent | 同左 **+ confirmation_id** | 处理新参数 |
| invoke 输出 | response / status / confirmation_required / shared_context / actions_taken | 不变（`confirmation_required` 含 `confirmation_id`） | 生成并回显 ID |
| 内部进度流 | `/agent/stream` SSE 事件 | **取消**（不实现） | 不用做 |
| 鉴权 | Delegation Token（Bearer + X-Timestamp 窗口） | 不变 | 复用 `McpSecurityMiddleware` |
| internalTools | ReAct 内部工具列表 | 不变 | 照旧实现 |

## 要点

1. **invoke 是唯一入口**：你的 Agent 暴露单个 `invoke` 工具，返回**合法 JSON 字符串**（必须含 `response` + `status`，status ∈ completed / needs_confirmation / partial / failed）。业务逻辑放内部函数，由 invoke 调度。
2. **confirmation_id（新增，必须实现）**：
   - 返回 `status=needs_confirmation` 时，生成唯一 ID 存入 `confirmation_required.confirmation_id` 并保存；
   - 用户确认后编排层重调：`confirmed=true` + 同一 `confirmation_id`；
   - **必须校验** confirmation_id 有效性，无效/过期 → `status=failed`。
   - 注：编排层当前尚未带 confirmed/confirmation_id 重调（Sprint 3+ 待办接线），按契约实现即可，接线后无需改 Agent 端。
3. **进度流不做**：前端步骤展示改用 invoke 返回的 `actions_taken`；确认交互由 `status=needs_confirmation` 同步承载。
4. **鉴权**：请求头 `Authorization: Bearer <Delegation Token>` + `X-Timestamp`（±10 分钟）；token 的 `aud` = 你的 Agent 名。验签中间件已提供（`agent/mcp_servers/security.py`），直接用。

## 迁移动作

- 已实现 internalTools 业务逻辑（search_emails 等）→ 保留，在 invoke 内调用
- 按旧 REST 端点实现的服务 → 入口函数接到 invoke（参考 `agent/mcp_servers/domain_server.py` 模板）
- 新增：confirmation_id 生成/保存/校验

## 参考与验证

```bash
# 本地起 Agent（RS256 必需：source .env 加载 TOKEN_SERVICE_JWKS_URL；未配置则全部 401）
MCP_AGENT_NAME=mail-agent uvicorn mcp_servers.domain_server:app --port 8081
```

验证清单：
- [ ] `GET /health` 200
- [ ] invoke 返回合法 JSON（response + status）
- [ ] 删除类请求首次返回 `needs_confirmation` + `confirmation_required.confirmation_id`
- [ ] 该 ID + `confirmed=true` 重调 → `completed`；无效 ID → `failed`
- [ ] 无 token 请求被拒（401）

参考实现：`agent/mcp_servers/domain_server.py`（mail/facility/lost-found/skill 共用模板，含关键词 mock 与 confirmation_id 处理）；契约原文见 `agent/schemas/*.json`。
