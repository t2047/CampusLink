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
| 7 | Agent 共享安全中间件 | ✅ agent/shared/security.py（HS256/RS256 双模式） |
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
  - 编排层 `AgentClient.get_delegation_token`：兑换 RS256，`jti` 绑定调用 Agent 的 `X-Nonce`；
    Token Service 不可用时回退本地 HS256（仅联调）
  - Agent 端 `shared/security.py`：设置 `TOKEN_SERVICE_JWKS_URL` 走 RS256（JWKS）验签
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

### 意图路由：规则 + LLM 混合（Task 4）- 规则预判：关键词命中 → 零成本快速路由（不触发 LLM）
- LLM 精判：规则未命中 → DeepSeek 结构化分类（temperature=0）
- 分类：`domain_agent` / `utility` / `chat`

### 编排层入站安全（Task 5/7）

- `orchestration/security/middleware.py`：HMAC + Nonce + Timestamp 校验
  （与 Chat Backend `OrchestrationClient.sign` 对齐，hex 编码）

## 架构要点

- 编排层 LangGraph：`input_guardrail → intent_router → agent_invoker / utility_executor / chat_responder → output_guardrail → response_aggregator`
- 多轮对话：`thread_id = userId`，MemorySaver checkpointer
- HITL：`interrupt()` 暂停等待审批，`Command(resume=...)` 恢复

## 待办（Sprint 3+）

- [ ] Token Service 独立部署（RS256 + JWKS 端点；当前由 Chat Backend 内嵌提供，接口形态已对齐）
- [ ] 编排层 HS256 回退移除（已默认 fail-closed：`ALLOW_HS256_FALLBACK` 默认 false；独立部署后删除回退代码）
- [ ] 编排层 Agent 路径流式逐 token 推送（chat 路径已 astream；Agent 路径当前同步返回完整 response）
- [ ] HITL 确认后重新调用 Agent（当前仅标记 confirmed/cancelled）
- [ ] 真实 Agent 组接入（替换 Mock）
- [ ] 分布式追踪（LangFuse）接入
