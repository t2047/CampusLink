# Lost & Found 技术路线与开发记录

> 最后更新：2026-08-08
> 当前版本：基础 Lost & Found `1.0`，Lost & Found Agent `0.4.0`
> 本文档必须随每个 Lost & Found PR 更新，完成项保留历史记录，不直接删除。

## 1. 当前架构与边界

```text
React Web
   │ JWT
   ▼
Spring Boot API ── MySQL
   │              MinIO
   │ 内部 Delegation Token（HS256，60 秒内）
   ▼
Lost & Found Agent（FastAPI，8083）
   ▲
   │ HMAC + Delegation JWT
Chat Core / 测试客户端
```

- Spring Boot 是 Lost & Found 业务规则和数据写入的唯一入口。
- Agent 不直接访问 MySQL 或 MinIO，不持有用户密码，不返回发布者联系方式。
- 当前不接管或合并 `feature/chatcore`；通过 JSON Schema 保持后续兼容。
- 用户侧基础 Web 功能和管理员只读功能已经上线。

## 2. 已完成内容

| 功能 | 状态 | 版本 | 说明 |
|---|---|---:|---|
| 发布 LOST / FOUND 记录 | 已完成 | 1.0 | Web + Spring Boot，支持 0–5 张图片 |
| 条件筛选与分页 | 已完成 | 1.0 | 关键词、类别、颜色、地点、日期和状态 |
| 认领申请与审核 | 已完成 | 1.0 | 发布者批准或拒绝，批准后关闭其他申请 |
| 管理员只读运营视图 | 已完成 | 1.1 | 统计和全量记录筛选，不包含写操作 |
| Agent JSON 契约 | 已完成 | 0.1.0 | 类别、状态、隐私和确认语义已重新对齐 |
| Agent FastAPI 骨架 | 已完成 | 0.1.0 | 健康检查、能力、调用和 SSE 接口 |
| Agent 入站安全 | 已完成 | 0.1.0 | HMAC、Delegation JWT、Nonce、限流 |
| Agent 真实业务工具 | 已完成 | 0.2.0 | 报失、搜索、详情、认领，均复用现有 Service |
| 后端 Agent 内部 API | 已完成 | 0.2.0 | 动作级权限、真实用户回查和一次性 jti |
| 无密钥规则对话 | 已完成 | 0.3.0 | 中英文解析、多轮补充、四工具编排和 SSE |
| 写操作确认 | 已完成 | 0.3.0 | 用户绑定、10 分钟有效、一次性使用 |
| 可解释匹配重排 | 已完成 | 0.3.0 | 最多 100 个候选，返回达到阈值的 Top 5 |
| 可插拔 LLM | 已完成 | 0.4.0 | DeepSeek/OpenAI-compatible，严格校验并自动降级 |

## 3. Agent 对外接口

| Method | Path | 认证 | 用途 |
|---|---|---|---|
| `GET` | `/health` | 无 | 健康、运行模式和模型配置状态 |
| `GET` | `/agent/capabilities` | 无 | 能力声明和版本 |
| `POST` | `/agent/invoke` | JWT + HMAC | 同步执行自然语言请求 |
| `GET` | `/agent/stream?request_id=...` | JWT + HMAC | 读取短期 SSE 执行事件 |

完整字段以 `agent/schemas/lost-found-agent.json` 为准。受保护请求的 Token `jti` 必须与 `X-Nonce` 相同；当前 Chat Core 分支尚未满足这一条件，正式集成前必须修复。

## 4. 环境变量与本地启动

- Agent 模板：`agent/lost_found_agent/.env.example`
- `LOST_FOUND_LLM_API_KEY` 暂时可以为空，`auto` 模式会选择规则引擎。
- `AGENT_SHARED_SECRET`、`AGENT_BACKEND_SHARED_SECRET` 和 `LOST_FOUND_CONFIRMATION_SECRET` 必须分别使用至少 32 字符的随机值。
- 禁止在提交、日志、异常响应和测试快照中保存真实密钥。

```bash
cd agent/lost_found_agent
cp .env.example .env
python3.12 -m pip install uv
uv sync --all-extras
uv run uvicorn lost_found_agent.main:app --port 8083
```

启动 MySQL、MinIO、Spring Boot 和 Agent 的可选联调环境：

```bash
docker compose --profile agent up -d --build
```

## 5. 内部 API 与安全流程

| Method | Path | `intended_action` | 用途 |
|---|---|---|---|
| `POST` | `/api/internal/lost-found/reports/lost` | `report_lost` | 用 JSON 创建无图片 LOST 记录 |
| `GET` | `/api/internal/lost-found/candidates` | `search_found_items` | 只返回 `FOUND + OPEN` 候选记录 |
| `GET` | `/api/internal/lost-found/reports/{id}` | `get_item_detail` | 读取详情，不返回联系方式或对象 Key |
| `POST` | `/api/internal/lost-found/reports/{id}/claims` | `claim_item` | 复用现有认领业务规则 |

Agent 每次工具调用都创建独立 Token。Spring Boot 依次校验签名、`aud=campus-api`、`iss=lost-found-agent`、不超过 60 秒的有效期、与路由一致的 `intended_action` 和未使用的 `jti`，然后根据 `sub` 从数据库重新查询真实用户。普通登录 JWT 不能访问内部 API。

## 6. 规则对话、确认与匹配

- `conversation_context.shared_data` 仅保留白名单字段，用于中英文多轮补充。
- 报失需要物品名、类别、详细描述、地点和日期；认领需要记录 ID 和不少于 10 字符的证明。
- 报失和认领首次调用不写数据库；确认 ID 与用户绑定、10 分钟有效且一次性使用。
- 报失确认创建记录后自动搜索 `FOUND + OPEN`，最多取 100 条并返回 Top 5。
- 重排权重：文字 30%、类别 30%、颜色 15%、地点 15%、日期 10%。缺失字段不计入分母，其他权重自动归一化。
- 默认阈值为 `0.35`，可通过 `LOST_FOUND_MATCH_MIN_SCORE` 调整。当前文字使用规则相似度，尚不是 Embedding。
- SSE 保留 5 分钟，包含开始、工具执行、补充信息、确认、Token、完成和错误事件。

## 7. LLM 模式与降级策略

- `auto` 在存在 API Key 时启用模型，不存在时使用规则模式；`rules` 强制规则；`llm` 缺少 Key 时拒绝启动。
- 模型仅识别四种允许意图并提取白名单字段，不能访问数据库、直接执行工具或绕过写操作确认。
- 模型输出必须通过 Pydantic 严格校验；未知工具、额外字段、非法类别和日期均触发规则降级。
- 每次用户调用最多执行两个后端工具；确认调用不会再次请求模型。
- 模型超时、HTTP 限流、服务不可用和无效 JSON 均自动降级，SSE 记录 `model_fallback` 事件但不记录密钥。
- CI 使用 Mock OpenAI-compatible Server 验证正常响应、故障降级、提示词注入和越权工具输出，无需真实 API Key。
- 本地已使用 `deepseek-v4-flash` 完成一次只读意图与字段提取冒烟测试；`deepseek-v4-pro` 也由模型列表接口确认可用，尚未进行批量质量与成本评估。

## 8. 已知限制与技术债

- 规则解析主要覆盖明确意图和标签化字段，复杂自然语言将在 LLM 阶段增强。
- Nonce、限流和 SSE 事件存储当前为单实例内存实现，横向扩容前需要 Redis。
- 当前使用开发期 HS256 共享密钥；生产环境需要 Token Service、RS256/JWKS 和 mTLS。
- 聊天请求暂不支持图片附件。
- 当前匹配仍为结构化查询；尚无 Embedding、向量索引或多模态模型。
- 真实模型尚未完成质量、延迟和费用验证；当前只完成 Mock 协议与故障降级验收。

## 9. 后续功能清单

| 优先级 | 功能 | 状态 | 依赖 | 建议负责人 | 验收标准 | 目标迭代 |
|---:|---|---|---|---|---|---|
| P0 | Agent 内部 API 与四个真实工具 | 已完成 | Spring Security、现有 Lost & Found Service | Lost & Found 后端 | 四个工具通过权限和集成测试 | Agent 0.2 |
| P0 | 中英文规则对话和写操作确认 | 已完成 | Agent 工具 | Agent 开发 | 无密钥完成报失、搜索、详情、认领 | Agent 0.3 |
| P0 | Chat Core 正式集成并修复 Nonce | 未开始 | Chat Core | Chat Core + Agent | 完整安全链端到端通过 | 集成迭代 |
| P1 | 可插拔 LLM 与 Mock 联调 | 已完成 | 规则模式 | Agent 开发 | 严格校验、自动降级和安全测试通过 | Agent 0.4 |
| P1 | 真实模型密钥联调 | 未开始 | API Key、评估样本 | Agent 开发 | 输出质量、P95 延迟和费用报告完成 | 联调迭代 |
| P1 | 多语言文本 Embedding 与向量召回 | 未开始 | 向量数据库、评估集 | ML | Recall@K 达到评审目标 | 匹配 2.0 |
| P1 | 图片 Embedding 与多模态匹配 | 未开始 | 图片模型、MinIO | ML | 返回可解释多模态 Top 5 | 匹配 2.1 |
| P1 | 匹配反馈和排序评估数据集 | 未开始 | 用户反馈数据 | ML + 数据 | 可复现实验和版本对比 | 匹配 2.1 |
| P1 | 认领和状态变化通知 | 未开始 | 通知服务 | 后端 | 关键状态可靠送达 | 业务 1.2 |
| P2 | 聊天图片上传 | 未开始 | Chat UI、Agent、MinIO | Web + Agent | 图片随报失记录安全保存 | 业务 1.3 |
| P2 | 用户编辑、关闭和删除 | 未开始 | 审计规则 | 后端 + Web | 权限、并发和状态冲突测试通过 | 业务 1.3 |
| P2 | 管理员审核、下架和审计日志 | 未开始 | 管理员权限 | 管理后台 | 所有写操作可追溯 | 管理 1.2 |
| P2 | Redis 分布式安全状态 | 未开始 | Redis | 平台 | 多实例限流与防重放一致 | 平台 1.1 |
| P2 | Token Service、RS256/JWKS、mTLS | 未开始 | 基础设施 | 安全 + 平台 | 移除服务间共享签名密钥 | 平台 2.0 |
| P2 | OpenTelemetry、脱敏日志和告警 | 未开始 | 监控平台 | DevSecOps | Trace 串联且无敏感字段 | 平台 1.2 |
| P3 | 数据保留、归档和隐私删除 | 未开始 | 产品与合规规则 | 后端 + 安全 | 自动策略和恢复演练通过 | 合规迭代 |
| P3 | 移动端入口与推送 | 未开始 | Mobile、通知服务 | Mobile | 核心链路移动端验收通过 | Mobile 1.0 |
| P3 | 生产部署、回滚和灾难恢复 | 未开始 | 部署目标、密钥管理 | DevSecOps | 自动部署、回滚和恢复演练通过 | 上线迭代 |

## 10. 更新规则

1. 每个 Lost & Found PR 必须更新“已完成内容”“已知限制”和“后续功能清单”。
2. 已完成项保留原行并更新状态、版本和验收结果。
3. 新技术债必须填写优先级、依赖、建议负责人、验收标准和目标迭代。
4. API 或安全契约变化时，同时更新 JSON Schema、README 和自动化契约测试。
