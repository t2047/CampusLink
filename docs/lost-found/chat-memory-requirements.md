# L&F Agent Chat Memory — 需求与实现方案

> 状态：P0–P4 已基本实现（本分支工作区），P5 待 E2E 验证；仍有少量修复/优化项
> 目标模块：`agent/lost_found_agent`（L&F Agent 本体）
> 配套改动：后端 Spring Boot（MySQL 存储 + 内部 API）、前端面板（会话 ID 持久化）
> 2026-08-15 整理

---

## 0. 实施进度（2026-08-15 更新）

| 阶段 | 状态 | 说明 |
|---|---|---|
| P0 后端记忆 API | ✅ | `lf_*` 三表（JPA `ddl-auto=update` 建表）、记忆内部 API、`AgentDelegationAuthFilter` action 扩展、归属校验、清除接口；`LostFoundMemoryInternalSecurityIntegrationTest` 9 例通过 |
| P1 invoke 主流程 | ✅ | `invoke_service.py` + `memory.py`；REST 8083 与 MCP 8085 共用 `LostFoundInvokeService`；invoke 前后加载/保存；事实抽取落库 |
| P2 记忆参与回复 | ✅ | LLM `interpret` 注入 `memory_context`；规则引擎缺字段低置信度补全 + 搜索候选类别偏置 |
| P3 摘要滚动 + pending 持久化 | ✅ | 超过 12 条折叠摘要（LLM 降级规则截断）；pending 草稿随会话落库、过期只恢复草稿不执行 |
| P4 前端会话持久化 | ✅/⚠️ | `LostFoundAgentPanel` 按账号 localStorage 恢复 active session + “New conversation”按钮；已覆盖目标单测。注意：当前只持久化 active sessionId，刷新后不会主动拉取历史消息/pending，只能在下一次 invoke 时由 agent 恢复上下文 |
| P5 MCP 链路验证 | ⏳ | MCP 入口已共用 invoke service 且不注入 recent_messages；MCP 异常兜底分支已补回归测试；**待** ChatPage 端到端验证 session_id 透传、非 L&F 意图不触发 memory API、双重上下文不重复注入 |

> 历史全量回归记录：agent pytest 154 例通过；后端 mvn test 232 例通过（含记忆 9 例，修复了 `MemoryUpsertSessionRequest.clearPendingConfirmation` 原始 boolean 在字段缺省时触发 Jackson `FAIL_ON_NULL_FOR_PRIMITIVES` 导致全部 400 的问题，改为可空 `Boolean`）；前端 vitest 246 例通过 + tsc 类型检查 + eslint 无报错。
>
> 当前复核（2026-08-15）：agent 目标测试 `tests/test_lost_found_server_mcp.py tests/test_memory.py tests/test_invoke_service.py` 通过（18 passed）；前端目标测试 `LostFoundAgentPanel.test.tsx` 通过（13 passed）。后端目标测试因当前环境无法下载 Maven parent POM 未能本地重跑，需在具备 Maven 依赖缓存/网络的环境复验。

### 0.1 当前代码完成情况（复核摘要）

| 模块 | 已完成内容 | 当前判断 |
|---|---|---|
| 后端存储 | `LfChatSession`、`LfChatMessage`、`LfUserMemoryFact`、三类 repository、`LostFoundMemoryService`、`LostFoundMemoryInternalController` | 基本完成；会话隔离使用 `(user_id, session_id)`，用户级接口使用 `/users/me`，符合安全边界 |
| 后端鉴权 | `AgentDelegationAuthFilter` 已增加 `memory_*` action 映射 | 基本完成；仍需在后端目标测试环境复跑 |
| Agent 记忆 | `MemoryClient`、`MemoryManager`、`FactExtractor`、摘要滚动、pending 草稿、记忆降级窗口 | 基本完成；目标测试通过 |
| Agent 主流程 | `LostFoundInvokeService` 收敛 REST/MCP invoke 主流程 | 基本完成；MCP 异常兜底分支已补 `detect_language` 导入并有目标测试覆盖 |
| 规则/LLM 注入 | LLM 读取 `memory_context`；规则引擎用长期事实做低置信度 location/colour 建议和 OPEN LOST_ITEM 类别偏置 | 基本完成；需继续防止旧事实污染本轮输入 |
| 前端面板 | `lf-active-session-<email>` localStorage、New conversation 按钮、目标测试 | 部分完成；只恢复 sessionId，不主动恢复历史消息/pending UI |
| ChatPage/MCP E2E | MCP 入口代码已接入共用 service，`include_recent_messages=False` | 待验证；尚未证明主聊天链路 session_id 稳定透传、非 L&F 不触发 memory |

### 0.2 待修复 / 待优化清单

| 优先级 | 项目 | 说明 |
|---|---|---|
| P0-fix | 修复 MCP 异常分支缺失导入 | ✅ 已完成：`agent/mcp_servers/lost_found_server.py` 已从 `lost_found_agent.rules` 导入 `detect_language`，并新增 `test_lost_found_server_mcp.py` 覆盖 invoke service 抛异常时返回 `status=failed` JSON |
| P0-verify | 后端目标测试复跑 | 在有 Maven 依赖缓存/网络的环境运行 `mvn -q -Dtest=LostFoundMemoryInternalSecurityIntegrationTest test`，确认 internal memory API、归属校验、清除接口仍通过 |
| P1-e2e | 完成 P5 ChatPage E2E | 验证 ChatPage → orchestration → L&F MCP 同一 session 连续两轮能复用记忆；非 L&F 意图不会触发 memory API；MCP 链路不重复注入完整 recent messages |
| P1-ui | 明确刷新后的用户体验 | 当前刷新只恢复 sessionId，不恢复消息列表。可选方案：新增 session history API 给前端拉取最近消息/pending，或在 UI 文案中明确“下一次发送消息后会继续上次上下文” |
| P1-db | 补正式 migration | 目前文档和实现依赖 JPA `ddl-auto=update` 建表。正式部署建议补 Flyway/Liquibase 或 SQL migration，避免生产环境 schema 不可控 |
| P2-privacy | 增加用户可见的隐私说明/清除入口 | 后端清除接口已规划/实现，前端清除入口仍是后续项；长期保留用户事实前应给用户明确说明 |
| P2-observability | 增加 memory API 观测 | 建议加 memory degraded 计数、读写耗时、跳过原因，便于定位后端不可用或 token 权限问题 |

---

## 1. 背景与现状

### 1.1 两条链路（共用业务模块，但入口不同）

```
链路 A（面板直连）:
  前端 LostFoundAgentPanel (/lost-found) → 后端 /api/lost-found/agent/invoke
    → lost-found-agent:8083 (/agent/invoke) → 后端内部 API /api/internal/lost-found/**

链路 B（聊天）:
  前端 ChatPage → orchestration:8000 (LangGraph + MemorySaver)
    → lost-found-mcp:8085 (/mcp/) → 同一 agent 模块 → 后端内部 API
```

- `lost-found-agent`(8083，REST) 与 `lost-found-mcp`(8085，MCP 适配层) **共用同一镜像/代码**（`agent/lost_found_agent/Dockerfile`，`context: ./agent`）。
- 两条链路当前都使用 `agent/lost_found_agent/` 的业务模块（`rules.py`、`llm.py`、`tools.py` 等），但入口并不相同：
  - REST 入口：`lost_found_agent/main.py` 自己装配 `RuleEngine` / LLM / confirmation store。
  - MCP 入口：`mcp_servers/lost_found_server.py` 也重新装配一套 `RuleEngine` / LLM / confirmation store。
- 因此本次改造不能只在 `main.py` 加记忆逻辑。必须抽出 REST/MCP 共用的 invoke service，否则会出现面板链路有记忆、ChatPage 链路没有记忆，或两边行为不一致。

### 1.2 现状：Agent 无状态，无知识库、无持久记忆

| 现状 | 说明 |
|---|---|
| 对话上下文 | 前端每轮把 `conversation_context.shared_data` 回传（`AgentWebInvokeRequest.toAgentPayload`），agent 只用当前这一轮，**不落库** |
| 会话 ID | 面板 `newSessionId()` 每次加载页面重新生成；`sessionId` 仅用于限流（`rate_limit.py`） |
| pending 确认 | `confirmation.py` 的 `ConfirmationStore` 为**进程内 dict，TTL 600s**，重启即失 |
| 聊天链路记忆 | orchestration 用 LangGraph `MemorySaver`（**内存级** checkpoint，按 thread_id 作用域，重启即失）；不落盘、不是知识库 |

### 1.3 痛点

1. 报失/报拾进行到一半（缺字段 → 追问 → 填好 → 确认），**刷新页面全部丢失**，用户要重新来。
2. Agent 不知道"用户上礼拜刚丢过一把伞"，无法基于历史物品提升匹配质量与追问效率。
3. 聊天链路（orchestration）虽有多轮上下文，但**重启丢失**，且与面板链路互不相通。

---

## 2. 需求决策（已与用户确认）

| 维度 | 决策 |
|---|---|
| 逻辑落点 | `agent/lost_found_agent` 模块内实现记忆逻辑 |
| 覆盖链路 | **面板直连 + 聊天（MCP）同一套代码都生效** |
| 记忆内容 | **对话历史 + 结构化事实**（意图/字段抽取） |
| 持久化位置 | **后端 MySQL**（agent 无 DB 连接，经后端内部 API 读写） |
| 作用域 | **用户级(user_id) 长期 + 会话级(session_id) 短期** 两级 |
| 参与回复 | **喂 LLM 解释器 + 回灌规则引擎** |
| 历史策略 | **原文 + 摘要滚动**（最近 N 条原文完整，更早折叠为摘要） |
| 面板会话 ID | **active session localStorage 持久化**（刷新后延续当前会话；新建 chat 时生成新 session） |
| 用户级记忆保留 | **长期保留**，但必须提供后端清除能力；前端清除入口可分阶段上线 |
| pending 确认 | **随会话持久化为草稿**；仍保留 600s TTL，过期后只能恢复草稿，不能直接执行确认 |

---

## 3. 核心功能与用户场景

### 3.1 功能清单

1. **会话记忆（短期）**：按 session_id 记录对话历史（user/agent 消息、字段提取结果），最近 N 条原文 + 滚动摘要。
2. **用户记忆（长期）**：按 user_id 沉淀结构化事实（历次报失/报拾的物品、类别、颜色、地点、时间、状态）。
3. **记忆参与生成**：把会话上下文 + 用户事实注入 LLM 解释器（意图/字段提取更准）与规则引擎（自动补全缺失字段、候选过滤/加权）。
4. **pending 确认持久化**：多轮澄清到一半的状态随会话落库，刷新/重启后可继续。
5. **会话连续性**：面板刷新页面后恢复当前 active session；点击"新会话"时创建新的 sessionId，历史会话相互隔离。
6. **MCP 链路同享**：聊天里也能感知用户历史（与面板行为一致），但避免与 orchestration 的 MemorySaver 重复注入完整历史。

### 3.2 典型场景

**场景 1：刷新不丢**
> 用户在 `/lost-found` 面板输入"我丢了把红色雨伞"，agent 追问"时间？"，用户刷新页面——输入框和历史都清空，但会话还在：重新打开后 agent 记得"用户要报失红伞，还差时间"，用户直接补"昨天下午"即可确认。

**场景 2：历史物品辅助匹配**
> 用户上周报失过"蓝色雨伞"（未找到，OPEN）。这周又来以图搜物传一张伞图——agent 结合用户历史：优先提示"您上周报失过蓝色雨伞，本次是否登记新的拾获？"，并把候选过滤加权到用户常报的类别。

**场景 3：字段自动补全**
> 用户历史里频繁出现地点"图书馆"。本次报失只说"我丢了 AirPods"，agent 可低置信度提示"你之前常在图书馆登记失物，本次地点是否也是图书馆？"；用户确认前不得静默填入并提交。

**场景 4：聊天链路延续**
> 用户在 ChatPage 聊"帮我看看有没有人捡到钱包"，agent 因记忆感知用户本月已报失过 2 次钱包，直接给出更相关的候选和更少的追问。

---

## 4. 总体架构

```
┌─────────────────────────────────────────────────────────────────┐
│ 前端                                                             │
│  LostFoundAgentPanel: active sessionId → localStorage            │
└───────────────┬─────────────────────────────────────────────────┘
                │ /api/lost-found/agent/invoke (JWT)
┌───────────────▼─────────────────────────────────────────────────┐
│ 后端 Spring Boot                                                 │
│  · 现有: Auth/Delegation 验签、业务 API                          │
│  · 新增: 记忆内部 API /api/internal/lost-found/memory/**          │
│  · 新增: 记忆表 (MySQL) lf_*                                     │
└───────────────┬─────────────────────────────────────────────────┘
                │ 转发(带 conversation_context)
┌───────────────▼─────────────────────────────────────────────────┐
│ L&F Agent 模块 (agent/lost_found_agent)                          │
│  · 新增: memory.py (MemoryClient + MemoryManager)                │
│  · 新增: invoke_service.py (REST/MCP 共用主流程)                  │
│  · 现有: rules.py / llm.py / tools.py (CampusApiClient)          │
│  · 注入: LLM 解释器 + 规则引擎                                   │
│  · pending confirmation 草稿持久化，执行确认仍受 TTL 控制          │
└─────────────────────────────────────────────────────────────────┘
```

设计原则：
- **agent 无 DB 连接**：所有读写走后端内部 API（复用现有 `CampusApiClient` + delegation token 模式，`AgentDelegationAuthFilter` 验签）。
- **记忆逻辑集中在 agent**：提取时机、注入格式、摘要裁剪由 agent 决定；后端只做存储 CRUD。
- **作用域由身份决定**：`user_id`（delegation token sub）隔离用户级记忆，`session_id` 隔离会话记忆；后端强制校验归属，防止横向越权。
- **入口逻辑共享**：REST 8083 与 MCP 8085 必须调用同一个 invoke service；不要在两个入口分别复制记忆读写逻辑。
- **记忆是增强，不是主流程依赖**：记忆内部 API 失败时降级为无记忆执行，不能阻断搜索、报失、报拾、认领等核心功能。

---

## 5. 数据模型（MySQL DDL 草案）

> 表名前缀 `lf_`；落在现有 `campusLink_db`。用户已存在 `users(id, email, role)`。

### 5.1 `lf_chat_sessions` — 会话 + 滚动摘要 + pending 确认

```sql
CREATE TABLE lf_chat_sessions (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT       NOT NULL,
  session_id       VARCHAR(200) NOT NULL,
  title            VARCHAR(120) NULL,
  summary          TEXT         NULL,              -- 滚动摘要（更早消息折叠后）
  pending_confirmation JSON      NULL,              -- 未完成的确认草稿（payload + confirmation_id + expires_at）
  archived         BOOLEAN      NOT NULL DEFAULT FALSE,
  last_active_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_user_session (user_id, session_id),
  KEY idx_user (user_id, last_active_at),
  CONSTRAINT fk_session_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> `session_id` 不能单独唯一。隔离边界必须是 `(user_id, session_id)`，防止不同用户在极端情况下复用同一个 sessionId 导致串记忆。

### 5.2 `lf_chat_messages` — 会话消息（原文 + 字段提取）

```sql
CREATE TABLE lf_chat_messages (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  chat_session_id  BIGINT       NOT NULL,
  role             ENUM('user','agent') NOT NULL,
  message_text     TEXT         NOT NULL,
  intent           VARCHAR(50)  NULL,              -- report_lost/report_found/search_* /claim...
  extracted_fields JSON         NULL,              -- 结构化抽取: {item_name,category,colour,location,event_date,time_description}
  image_object_keys JSON        NULL,              -- 本轮涉及的暂存图 object_key 列表
  trace_id         VARCHAR(64)  NULL,
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY idx_session (chat_session_id, created_at),
  CONSTRAINT fk_message_session FOREIGN KEY (chat_session_id) REFERENCES lf_chat_sessions(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 5.3 `lf_user_memory_facts` — 用户级长期事实

```sql
CREATE TABLE lf_user_memory_facts (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id          BIGINT       NOT NULL,
  session_id       VARCHAR(200) NULL,              -- 来源会话（审计/溯源）
  fact_type        ENUM('lost_item','found_item','preference','location') NOT NULL,
  item_name        VARCHAR(100) NULL,
  category         VARCHAR(50)  NULL,              -- 对齐 lost_found_reports.category 枚举
  colour           VARCHAR(50)  NULL,
  location         VARCHAR(200) NULL,
  event_date       DATE         NULL,
  time_description VARCHAR(100) NULL,
  status           ENUM('OPEN','CLAIMED','CLOSED') NULL,  -- 关联物品状态（可空）
  confidence       FLOAT        NULL,              -- 提取置信度（可选）
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  KEY idx_user (user_id, updated_at),
  KEY idx_user_type (user_id, fact_type),
  CONSTRAINT fk_fact_user FOREIGN KEY (user_id) REFERENCES users(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> 事实去重/合并策略：同 user_id + 同 fact_type + 同 (category, location) 视为同一事实，更新 item_name/status 而不重复插入；`OPEN` 状态的丢失物品是匹配优先项。

---

## 6. 后端内部 API 设计

> 统一前缀 `/api/internal/lost-found/memory/**`，走现有 `AgentDelegationAuthFilter`（`aud=campus-api`、`iss=lost-found-agent`）。需在 `AgentDelegationAuthFilter.expectedAction` 增加新 action（见 §6.2）。

### 6.1 端点

| 方法/路径 | intended_action | 说明 |
|---|---|---|
| `POST /api/internal/lost-found/memory/sessions` | `memory_upsert_session` | 建/更新会话（含 summary、pending_confirmation） |
| `GET /api/internal/lost-found/memory/sessions/{sessionId}` | `memory_read` | 取会话：历史消息(最近 N)、summary、pending_confirmation |
| `POST /api/internal/lost-found/memory/sessions/{sessionId}/messages` | `memory_append` | 追加一条消息（含 extracted_fields） |
| `GET /api/internal/lost-found/memory/users/me` | `memory_read` | 取当前用户级事实（按 fact_type/updated_at） |
| `POST /api/internal/lost-found/memory/users/me/facts` | `memory_upsert_fact` | 写入/合并一条事实 |
| `DELETE /api/internal/lost-found/memory/users/me` | `memory_delete` | 清除当前用户长期事实与可清除会话记忆 |

### 6.2 鉴权扩展

`AgentDelegationAuthFilter.expectedAction()` 增加映射；`_delegation_token`（agent `tools.py`）新增对应 action。后端记忆接口必须校验：
- `sessionId` 归属：读/写会话时始终用 delegation token 的 `sub` 作为 `user_id` 查询 `(user_id, session_id)`，不能只按 `session_id` 查。
- 用户级接口使用 `/users/me`，后端从 token `sub` 推导用户，不允许客户端或 agent 在 URL 中指定任意 `userId`。
- 记忆接口不要把原始 delegation token、claim proof、其他用户联系方式、storage object key 写入应用日志。

### 6.3 幂等与并发

- `upsert` 用 `ON DUPLICATE KEY UPDATE`（`user_id + session_id` 唯一键）。
- 同会话并发 invoke：以 `last_active_at` / 乐观版本为准，`last-write-wins`（失物招领场景单用户并发极低，可接受；若需更严可加 `version` 列）。

---

## 7. Agent 侧实现（核心）

### 7.1 新增 `invoke_service.py` 与 `memory.py` 模块

```
invoke_service.py
├── LostFoundInvokeService  # REST/MCP 共用 invoke 主流程：鉴权后调用，统一 LLM/规则/记忆
└── handle_invoke(...)      # main.py 与 lost_found_server.py 都调用这里

memory.py
├── MemoryClient         # 封装后端记忆内部 API（复用 CampusApiClient/delegation）
│   ├── upsert_session(session_id, summary, pending_confirmation)
│   ├── get_session(session_id)                 # → {messages, summary, pending_confirmation}
│   ├── append_message(session_id, role, text, intent, extracted_fields, images)
│   ├── get_user_facts(user_id)
│   └── upsert_fact(user_id, fact, source_session)
├── MemoryManager        # 编排：加载 → 注入 → 保存 → 裁剪
│   ├── build_context(verified, conversation)   # → memory_context（喂 LLM + 规则）
│   ├── persist_turn(...)                        # invoke 结束后写消息+事实
│   └── roll_summary(...)                        # 超过阈值时把旧消息折叠为摘要
└── FactExtractor        # 从解释结果结构化抽取（不额外调 LLM）
```

### 7.2 记忆加载与注入（共用 invoke service）

在 `LostFoundInvokeService.handle_invoke(...)` 内、`interpret_with_retry` 前：

```python
memory_ctx = await memory_manager.build_context(verified, payload.conversation_context)
# memory_ctx = {
#   "session_summary": "...",          # 旧消息摘要
#   "recent_messages": [...],          # 最近 N 条原文（role/text）
#   "user_facts": [...],               # 用户级事实
#   "pending_confirmation": {...},     # 未完成确认
# }
```

- **注入 LLM**：`interpret_with_retry(..., extra_context=memory_ctx)`，在 prompt 中拼入摘要/事实/pending，让意图与字段提取更有上下文。
- **注入规则引擎**：`rule_engine.handle(..., memory_context=memory_ctx)`：
  - 缺字段补全：当本轮输入和当前会话事实都缺字段时，才参考用户长期事实。补全必须以低置信度写入 shared_context，并在回复中提示用户确认，例如"我根据你之前的信息推测地点可能是图书馆，请确认"。
  - 匹配加权：`search_candidates` 时把用户事实里的 OPEN 丢失物品类别作为候选偏置/附加候选。

字段优先级固定为：
1. 本轮用户输入 / 本轮图片。
2. 当前会话 `shared_data` 与会话记忆。
3. 用户级长期事实。
4. 默认值。

长期事实不得覆盖本轮明确输入；不得静默替用户提交写操作。

### 7.3 结构化事实抽取

**不额外调 LLM**，直接复用现有解释结果：
- 成功创建报失/报拾 → 写 `lost_item`/`found_item` 事实（字段来自 `interpretation.fields`）。
- 检索/认领行为 → 更新关联事实 `status`。
- `preference`/`location` 事实：在字段提取中当 location/colour 在用户历史出现 ≥2 次时生成（低频不写，避免噪音）。

### 7.4 历史保留与摘要滚动

- 保留策略：**最近 12 条原文完整**；更早的消息在每次写入后触发滚动。
- 滚动时机：`len(messages) > 12` 时，把最早的 10 条丢给 LLM 生成一句摘要并入 `summary`，并从消息表删除这些原文（可加 `MAX_SUMMARY_LENGTH=800` 字符上限，超长则规则截断）。
- LLM 不可用时降级：不滚动、只删最早的 `max(0, len-12)` 条原文，保最新 12 条（功能不失效）。
- 会话级 token 预算：注入 LLM 时按 `(summary + recent_messages + user_facts 截断)` 控制总量，防止上下文超限。

### 7.5 pending confirmation 草稿持久化

- 改造 `confirmation.py`：`ConfirmationStore` 的 `create/consume` 与 `lf_chat_sessions.pending_confirmation` 同步（DB 为准，内存为缓存）。
- `pending_confirmation` 必须包含 `confirmation_id`、`action`、`payload`、`created_at`、`expires_at`、`user_id`、`session_id`。
- invoke 恢复时：`get_session` 返回 pending_confirmation → 若 `confirmation_id` 仍在 TTL 内、归属一致、action 一致，则继续流程（刷新/重启不丢）。
- 保留 TTL 语义（仍 600s）。过期后可以恢复为"未完成草稿"，但必须重新生成 confirmation_id，不能直接执行写操作。
- 用户点击确认时，最终执行仍必须走现有 report_lost / report_found / claim_item 工具和后端权限校验。

### 7.6 MCP 链路同享

`lost-found-mcp` 必须改为调用 `LostFoundInvokeService`，不能继续在 `lost_found_server.py` 内复制 REST invoke 流程。注意：
- orchestration 已有 `MemorySaver`（thread 级），agent 侧新增的是**持久化**记忆，两者会叠加。
- 为避免重复：MCP 链路默认只注入用户事实 + 会话摘要 + pending 草稿，不重复注入完整 recent messages；面板链路可以注入 recent messages。
- 如果 orchestration 没有透传稳定 `conversation_context.session_id`，agent 必须回退到 request_id，但这会导致每轮新会话；P5 必须用 ChatPage E2E 验证 session_id 透传。

---

## 8. 前端改动

### 8.1 active session 持久化（`LostFoundAgentPanel.tsx`）

- 当前：`const [sessionId] = useState(newSessionId)` → 每次加载重生成。
- 改为：优先从 `localStorage.getItem('lf-active-session-<userId>')` 读取，没有才生成并写入；同一浏览器、同一用户刷新后复用当前 active chat。
  - key 带 userId 防止多账号串会话。
  - 提供"新会话"按钮：生成新 sessionId 并覆盖 active key，旧 session 保留在后端历史中。
  - 后续如做历史列表，可从后端拉取最近 sessions；本阶段不要求完整历史 UI。

### 8.2 共享数据与 pending 恢复

- `sharedData` 仍每轮回传；刷新后首次 invoke 由 agent 从记忆恢复上下文，前端无需自己持久化 sharedData。
- 当前已实现：刷新后恢复 active `sessionId`；下一次 invoke 时由 agent 从记忆恢复上下文/pending。
- 待优化：如果希望首屏直接显示历史消息或"继续上次未完成的登记？"，前端还需要新增读取会话历史/summary/pending 的 API 或通过轻量 restore invoke 拉取。
- 目标行为：刷新后若会话有未过期 pending_confirmation，面板首屏显示"继续上次未完成的登记？"；若 pending 已过期，面板只显示草稿摘要和"重新确认"入口，重新确认会请求 agent 生成新的 confirmation_id。

### 8.3 记忆展示与清除

- 后端 `DELETE /api/internal/lost-found/memory/users/me` 必须实现，供 agent 或未来用户入口调用。
- 前端清除入口可列入 P2：面板加"我的历史"折叠区（展示 user facts）+ "清除记忆"按钮。
- 即使前端暂不展示清除按钮，也应在隐私说明中明确 L&F agent 会长期保留用户级事实。

---

## 9. 边界、安全与风险

1. **越权**：所有记忆接口按 delegation token `sub` 强制隔离；后端校验 session 归属。这是第一优先级。
2. **隐私**：记忆含用户失物/拾获信息，属敏感。前端仅本人可见；不写入日志；清除接口（P2）与长期保留策略需用户知情。
3. **Token 预算**：注入内容超限会拖慢 LLM 或超时。必须按 §7.4 控制（summary 上限、recent N、facts 截断）。
4. **LLM 不可用**：记忆提取/摘要若依赖 LLM，失败时走规则降级（§7.4），不影响主流程。
5. **双重上下文**（MCP 链路）：orchestration MemorySaver + agent 记忆叠加，避免重复注入完整历史（§7.6）。
6. **数据一致性**：消息先写库再响应用户，或先响应异步写？——建议**同步写**（失败不影响回复，仅降级记忆），避免丢失关键上下文。
7. **旧数据兼容**：历史 14 条报告已是 READY 向量，与记忆无冲突；`lf_*` 表为新增，不影响现有表。
8. **限流**：session_id 持久化后，限流 key 语义不变（仍是会话级 + 用户级），注意不要因复用 sessionId 导致跨刷新累计限流到用户（rate_limit 为进程内，重启清零，可接受）。
9. **主界面影响**：ChatPage 路由到 L&F 时会读写 L&F 记忆；普通聊天、Mail、Facilities 不应读写这些表。P5 必须验证非 L&F 意图不会触发 memory API。
10. **旧信息污染**：用户长期事实只能用于建议/加权/低置信度补全，不能覆盖本轮明确字段，不能静默创建或认领。
11. **确认误操作**：持久化 confirmation 只解决刷新/重启丢失问题，不改变 TTL 和用户确认要求；过期确认必须重新生成。
12. **数据膨胀**：用户事实长期保留，但 chat 原文必须摘要滚动；历史 session 可按数量归档（例如每用户最近 50 个 active sessions）。
13. **MCP 异常兜底**：`lost_found_server.py` 异常分支必须保持可用；已为缺少 `detect_language` 导入的问题补回归测试，后续重构 MCP 入口时不得删除该覆盖。

---

## 10. 分阶段实施计划

| 阶段 | 内容 | 交付物 | 验证 |
|---|---|---|---|
| **P0** | 后端：`lf_*` 三张表 DDL + 记忆内部 API + `AgentDelegationAuthFilter` 扩展 + 归属校验 + 清除接口 | 后端可 CRUD 记忆 | curl 内部 API（签名）单测 |
| **P1** | agent：`invoke_service.py` + `memory.py`（MemoryClient + MemoryManager）+ REST/MCP 入口共用 invoke service + invoke 加载/保存 + 事实抽取 | 面板链路跨轮记忆落库；MCP 入口不复制逻辑 | 多轮 invoke 后查 DB |
| **P2** | 注入 LLM + 规则引擎（字段补全/匹配加权） | 记忆参与回复 | 多轮场景自动化测试 |
| **P3** | 摘要滚动 + pending confirmation 持久化 | 刷新/重启不丢 | 刷新场景手工验证 |
| **P4** | 前端 active session localStorage + 新会话按钮 +（可选）历史展示/清除 | 面板刷新延续；新 chat 隔离 | 浏览器刷新验证 |
| **P5** | MCP 链路验证（共用 invoke service）+ 双重上下文控制 + 非 L&F 意图不触发记忆 | 聊天链路记忆一致且不污染主界面 | ChatPage 端到端 |

> 当前追加任务：P5 仍需 ChatPage 端到端验证；P4 若要求首屏恢复历史/pending，则需要新增前端读取会话状态能力，当前仅恢复 active sessionId。正式部署前建议补数据库 migration 或至少提供可审计的 SQL 初始化脚本，避免只依赖 `ddl-auto=update`。

> 依赖：P0 必须先于 P1；P2 依赖 P1；P3/P4 可并行于 P2 之后。

---

## 11. 测试方案

- **单测（agent）**：`MemoryManager` 上下文构建、`FactExtractor` 抽取、摘要滚动边界（12 条阈值）、LLM 降级。
- **单测（后端）**：记忆 API 的归属校验（越权 403）、upsert 幂等、TTL 语义。
- **集成（多轮）**：用签名请求（`AGENT_SHARED_SECRET` + 真实数字 user_id，见记忆 `lost-found-embedding-backfill` 的冒烟方法）连续 invoke 多轮，断言：消息入 `lf_chat_messages`、事实入 `lf_user_memory_facts`、`summary` 滚动正确。
- **E2E**：面板输入报失 → 刷新页面 → 继续流程能确认创建；ChatPage 聊天能感知用户历史。
- **MCP 回归**：ChatPage 同一 session 连续两轮 L&F 请求能复用记忆；非 L&F 请求不调用 memory API；orchestration MemorySaver 与 agent memory 不重复注入完整历史。
- **MCP 异常路径**：模拟 `LostFoundInvokeService.handle_invoke` 抛异常，断言 MCP 工具返回 `status=failed` JSON，而不是因兜底代码二次异常中断。
- **确认回归**：确认流程（未完成时）在刷新后仍能完成；TTL 过期后只能恢复草稿并重新确认；规则引擎/LLM 两种模式行为一致。

---

## 12. 未决项 / 建议默认值

| 项 | 建议默认 | 备注 |
|---|---|---|
| 最近原文条数 N | 12 | 可调 |
| 摘要最大长度 | 800 字符 | 超长规则截断 |
| 事实去重阈值 | 同 user + 同 fact_type + 同 (category, location) | 更新不重复插 |
| preference/location 事实写入 | 出现 ≥2 次 | 低频不写，避免噪音 |
| pending 确认 TTL | 600s（沿用），持久化 | 以 updated_at 判断 |
| pending 过期语义 | 恢复草稿，不直接执行 | 必须重新生成 confirmation_id |
| 清除记忆接口 | P0 必做 | 前端按钮可 P2 |
| 记忆注入是否含完整历史 | 不含（orchestration 已带） | 仅面板链路注入 recent messages |
| 后端存储层 | MyBatis/JPA 均可，建议复用现有 repository 风格 | 与 `lost_found_reports` 一致 |
| session 唯一键 | `(user_id, session_id)` | 禁止只按 session_id 唯一 |
| 历史 session 数量 | 每用户最近 50 个 active sessions | 超过后归档或只保摘要 |
