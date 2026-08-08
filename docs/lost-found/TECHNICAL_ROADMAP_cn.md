# Lost & Found 技术路线与开发记录

> 最后更新：2026-08-08
> 当前版本：基础 Lost & Found `1.0`，Lost & Found Agent `0.1.0`
> 本文档必须随每个 Lost & Found PR 更新，完成项保留历史记录，不直接删除。

## 1. 当前架构与边界

```text
React Web
   │ JWT
   ▼
Spring Boot API ── MySQL
   │              MinIO
   │ 内部 Delegation Token（下一阶段）
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
| Agent 真实业务工具 | 未开始 | 0.2.0 | 计划在下一阶段接入 |
| 无密钥规则对话 | 未开始 | 0.3.0 | 中英文解析、多轮补充和确认 |
| 可插拔 LLM | 未开始 | 0.4.0 | DeepSeek/OpenAI-compatible，可自动降级 |

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

## 5. 已知限制与技术债

- Agent 目前只有基础框架，尚未调用真实 Lost & Found API。
- Nonce、限流和 SSE 事件存储当前为单实例内存实现，横向扩容前需要 Redis。
- 当前使用开发期 HS256 共享密钥；生产环境需要 Token Service、RS256/JWKS 和 mTLS。
- 聊天请求暂不支持图片附件。
- 当前匹配仍为结构化查询；尚无 Embedding、向量索引或多模态模型。
- 真实模型尚未完成质量、延迟、费用和故障降级验证。

## 6. 后续功能清单

| 优先级 | 功能 | 状态 | 依赖 | 建议负责人 | 验收标准 | 目标迭代 |
|---:|---|---|---|---|---|---|
| P0 | Agent 内部 API 与四个真实工具 | 未开始 | Spring Security、现有 Lost & Found Service | Lost & Found 后端 | 四个工具通过权限和集成测试 | Agent 0.2 |
| P0 | 中英文规则对话和写操作确认 | 未开始 | Agent 工具 | Agent 开发 | 无密钥完成报失、搜索、详情、认领 | Agent 0.3 |
| P0 | Chat Core 正式集成并修复 Nonce | 未开始 | Chat Core | Chat Core + Agent | 完整安全链端到端通过 | 集成迭代 |
| P1 | 可插拔 LLM 与真实密钥联调 | 未开始 | API Key、规则模式 | Agent 开发 | 自动降级、费用和延迟报告 | Agent 0.4 |
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

## 7. 更新规则

1. 每个 Lost & Found PR 必须更新“已完成内容”“已知限制”和“后续功能清单”。
2. 已完成项保留原行并更新状态、版本和验收结果。
3. 新技术债必须填写优先级、依赖、建议负责人、验收标准和目标迭代。
4. API 或安全契约变化时，同时更新 JSON Schema、README 和自动化契约测试。
