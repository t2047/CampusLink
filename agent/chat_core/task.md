# Chat Core 任务进度

> 本文档记录 Chat Core 实现的进度与决策。更新于：剩余任务一次性实现。

## 进度

| Task | 内容 | 状态 |
|------|------|------|
| 1 | 项目骨架 | ✅ |
| 2 | AgentState 定义 | ✅ |
| 3 | LangGraph 图骨架 | ✅ |
| 4 | Intent Router (LLM 分类) | ✅ 规则+DeepSeek LLM 混合 |
| 5 | MCP Client + Service Registry | ✅ 完整实现（含本地 HS256 token） |
| 6 | Token Service (RS256) | ✅ Chat Backend 内嵌 DelegationTokenProvider + TokenExchangeController；RS256 链路已全线启用（编排层兑换 + Agent JWKS 验签）；独立部署 Sprint 3 |
| 7 | Agent 共享安全中间件 | ✅ 已迁移至 `agent/mcp_servers/security.py`（RS256/JWKS 单一模式；旧 `agent/shared/security.py` 为 MCP 化前遗留） |
| 8 | SSE Streaming Handler | ✅ 同步模式；流式逐 token Sprint 3 |
| 9 | Chat API 端点 | ✅ |
| 10 | 集成测试 + Mock Agent | ✅ Mock agent/mock utility + 单元测试 |

## 决策记录

### Delegation Token 签名（2026-08-06，更新 2026-08-08）

- **Sprint 1 联调**：编排层用 `AGENT_SHARED_SECRET` 本地签发 HS256 Delegation Token
  （`AgentClient.issue_local_delegation_token`），Mock Agent 以 HS256 验签
- **当前（已启用）**：RS256 链路全线打通
  - 后端 `TokenExchangeController`：`POST /internal/token/exchange` 兑换（HMAC 头认证）
    + `GET /.well-known/jwks.json` 公钥端点
  - 编排层 `AgentClient.get_delegation_token`：兑换 RS256；**fail-closed**——兑换失败
    拒绝调用（HS256 本地回退已移除，2026-08-08）
  - Agent 端 `mcp_servers/security.py`：JWKS 拉公钥 RS256 验签（未配置 JWKS 直接拒绝）
- **Sprint 3 目标**：Token Service 独立部署（仅切换 `TOKEN_SERVICE_URL` /
  `TOKEN_SERVICE_JWKS_URL`，接口形态不变）

### LLM 选型（2026-08-07）

- DeepSeek（.env: DEEPSEEK_API_KEY / DEEPSEEK_BASE_URL / DEEPSEEK_MODEL）
- `orchestration/llm.py` 统一工厂：intent_llm (temp=0) / chat_llm (temp=0.7) / summary_llm (temp=0.3)

### SSE 回复重复（2026-08-08）

- 现象：chat 回复出现两遍（完整回复 + 逐字 token 流拼接）
- 根因：`stream_mode=["messages","updates"]` 是**并行流，顺序不保证**；updates
  （chat_responder 完成）先于 messages（逐字 token）到达时，`chat_streamed` 标志
  未置位 → 先发完整回复，随后 messages 全量 token 又来一遍
- 修复：updates 的完整回复改为缓冲（`pending_chat_reply`），流末仅当 messages 全程
  无产出才补发；异常路径（`stream_failed`）跳过补发，避免与 `_direct_llm_reply` 重复
- 回归测试：`test_sse_stream.py`（乱序 / 仅 messages / 仅 updates 三种场景）

### Sprint 3：MCP 化（2026-08-08）

- **协议统一**：编排层 ↔ Agent/Utility 全部改为 MCP（streamable HTTP）——  - `agent/mcp_servers/domain_server.py`：每 Domain Agent 一个进程，暴露单个 `invoke` 工具
    （当前为关键词 mock 逻辑，真实接入时替换 `_handle_request`）
  - `agent/mcp_servers/utility_server.py`：4 个工具（calculator / get_current_time /
    unit_converter / web_search(占位)；text_translator 已移除——翻译由 LLM 直答）
  - 编排层 `AgentClient`：`mcp.streamable_http_client` + `ClientSession.call_tool`
- **安全模型变更**：MCP 层用 `Authorization: Bearer <Delegation Token>` + `X-Timestamp`
  窗口；自研 REST 的 body HMAC 与 `jti==X-Nonce` 绑定取消（完整性交给生产 TLS）
- 依赖：`mcp>=1.28,<2`（v1.x 稳定 API）；旧 `mock_agent.py` / `mock_utility.py` 已删除
- **schemas 契约对齐**（2026-08-08 更新）：
  - `transport` 段更新：REST 端点（`/agent/invoke`、`/agent/stream`）→ MCP `/mcp/` + `/health`
  - `streaming` 段标注 `not_implemented`：不再推送 Agent 内部进度（用户确认不需要内部实时
    显示）；确认交互由 invoke 返回 `status=needs_confirmation` + `confirmation_required` 承载
  - **`confirmation_id` 补齐**：invoke 工具参数 + `confirmation_required.confirmation_id`
    （HITL 确认重调的关联键；mock 以进程内 dict 校验有效性，真实实现可持久化）
- 待办（Sprint 3+）：web_search 真实接入；真实 Agent 业务逻辑替换
  `_handle_request`；Agent MCP Server 容器化

### 工具/子 Agent 失败兜底（2026-08-08）

- 需求：MCP 服务未启动时给出**明确英文报错**；工具/子 Agent 报错后**转主 Agent（LLM）处理**
- 实现：
  - `mcp/client.py`：`_describe_mcp_failure` 把传输层失败（连接拒绝/超时等）转成
    `MCP service '<name>' is unreachable at <url>: ... Please ensure the service is running.`；
    `error` 字段存英文详情（日志/排查），不再把裸异常给用户
  - `graph/nodes.py`：utility/agent 失败写入 `state.service_failures`；`chat_responder`
    检测到失败时由 LLM 生成友好兜底回复（不提及内部技术细节）
  - `graph/edges.py` + `graph/graph.py`：**utility 全部失败** / **子 Agent 全部失败**
    → 路由 `to_chat`（chat_responder）；部分失败 → 照常聚合，失败项显示
    "（xx 暂时不可用，请稍后重试）"友好文案
- 测试：`test_client.py`（`_describe_mcp_failure` 2 例 + `invoke_utility` 不可达）、
  `test_edges.py`（6 例路由）

### 意图路由：LLM 语义分类（Task 4，2026-08-08 更新）

- **规则预判已移除**：关键词规则无法理解"不要用计算器"这类否定语境（含关键词即命中
  utility），且新增能力需同步维护关键词表；分类完全交给 LLM（DeepSeek，temperature=0）
- LLM 失败/超时/返回非 JSON → 安全降级 `chat`（不误调 Agent/Utility）
- 分类：`domain_agent` / `utility` / `chat`（prompt 带 agent/utility 能力清单）

### 编排层入站安全（Task 5/7）

- `orchestration/security/middleware.py`：HMAC + Nonce + Timestamp 校验
  （与 Chat Backend `OrchestrationClient.sign` 对齐，hex 编码）

## 架构要点

- 编排层 LangGraph：`input_guardrail → intent_router → agent_invoker / utility_executor / chat_responder → output_guardrail → response_aggregator`
- 多轮对话：前端 session_id（localStorage 持久化）→ 后端 → 编排层 thread_id，
  MemorySaver checkpoint 累积消息上下文；上次停在中断（HITL）时换新 thread 防挂起
- HITL：`interrupt()` 暂停等待审批，`Command(resume=...)` 恢复

## 待办（Sprint 3+）

- [ ] Token Service 独立部署（RS256 + JWKS 端点；当前由 Chat Backend 内嵌提供，接口形态已对齐）
- [ ] 编排层 Agent 路径流式逐 token 推送（chat 路径已 astream；Agent 路径当前同步返回完整 response）
- [ ] HITL 确认后重新调用 Agent（当前仅标记 confirmed/cancelled）
- [ ] 真实 Agent 组接入（替换 Mock）
- [ ] 分布式追踪（LangFuse）接入
