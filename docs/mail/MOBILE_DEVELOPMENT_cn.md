# CampusLink Mail 模块后端接口文档（移动端开发用）

> 适用对象：`frontend_mobile`（Android/Kotlin）团队开发 Mail（邮件 + 日历）功能。
> 本文档覆盖 mail-calendar 模块**全部后端代码**的行为、接口契约与移动端集成要点，可直接作为移动端对接的权威参考。
> 后端代码位置：`agent/mail_agent/`（REST 服务）+ `agent/mcp_servers/mail_server.py`（MCP 网关，聊天场景用，移动端一般不需要直接对接）。

---

## 1. 模块总览

### 1.1 架构

```
┌────────────────────────────────────────────────────────────────────┐
│                          移动端 (Android)                           │
│   baseUrl = https://campuslink.tokeninf.xyz （生产走 nginx 同源代理） │
│   或 http://<host>:5000            （本地联调直连 mail 服务）        │
└───────────────┬────────────────────────────────────────────────────┘
                │  Authorization: Bearer <CampusLink JWT>
                ▼
┌─────────────────────────────── nginx（生产）────────────────────────┐
│  /api/mail/*  → mail-service:5000   （proxy_read_timeout 300s）     │
│  /callback    → mail-service:5000   （Gmail OAuth 回调）             │
│  /api/*       → chat-backend:8080   （Java 后端：登录/用户等）        │
└───────────────┬────────────────────────────────────────────────────┘
                ▼
┌────────────────────────── mail-service (FastAPI :5000) ──────────────┐
│  agent/mail_agent/mail_agent/                                       │
│  ├─ main.py          所有 REST 路由 /api/mail/**                     │
│  ├─ auth.py          请求身份解析（用户 JWT 验签 + 内部令牌）          │
│  ├─ gmail_service.py Gmail API 操作（OAuth、列表、读写、归档、删除）   │
│  ├─ calendar_service.py SQLite 日历 CRUD + 邮件日程抽取               │
│  ├─ agent.py          LangChain ReAct Agent（自然语言操作邮件）        │
│  ├─ classifier.py     邮件分类（LLM 优先，ML 模型兜底；campus/career/finance/other）│
│  ├─ models.py         Pydantic 数据模型                               │
│  └─ config.py         环境变量 / Gmail OAuth 客户端配置               │
└────────────────────────────────────────────────────────────────────┘
```

- **mail-service**：`agent/mail_agent/`，FastAPI 应用，监听 **5000** 端口。所有邮件/日历能力都通过它暴露。
- **mail-agent-mcp**：`agent/mcp_servers/mail_server.py`，监听 **8081**，把 mail 服务包装成 MCP streamable HTTP 工具给聊天编排层调用（`chat` 模块 → 意图路由 → MCP invoke → mail REST）。**移动端做独立 Mail 页面不需要对接它**；只有做「聊天里问邮件」时才相关（见 §6.5）。
- **身份**：CampusLink 的 JWT 通过 `Authorization: Bearer <jwt>` 传入。mail 服务现在**验签用户 JWT**（HS256，`JWT_SECRET` 与后端一致），`sub`（邮箱）即用户身份；聊天/MCP 通道无法携带用户 JWT（用户 JWT 不离开 Chat Backend），改由 `mail-agent-mcp` 用共享密钥代签短时内部令牌（HS256，`MAIL_INTERNAL_SECRET`/`AGENT_SHARED_SECRET`，`aud=mail-service`，30s TTL）转发，`sub` 为数字 userId（详见 §4.1）。
- **Gmail 授权**：**每个用户绑定自己的 Gmail 账号**（OAuth2 web flow，授权后刷新令牌按用户持久化到 `gmail_tokens/`）。不再有全站共享邮箱；各用户看到的是自己的 Gmail（详见 §4.2）。

### 1.2 代码文件速查

| 文件 | 职责 |
|---|---|
| `agent/mail_agent/mail_agent/main.py` | FastAPI 路由定义、统一错误处理（`MailApiError`）、OAuth 回调 |
| `agent/mail_agent/mail_agent/auth.py` | 请求身份解析：验签用户 JWT（HS256，`JWT_SECRET`）或内部令牌（`MAIL_INTERNAL_SECRET`），返回用户身份 |
| `agent/mail_agent/mail_agent/gmail_service.py` | Gmail API 封装（**按用户**）：OAuth 流程、消息列表/详情/发送/标记/归档/删除、模糊搜索、分页 token 缓存、60s 消息缓存 |
| `agent/mail_agent/mail_agent/calendar_service.py` | SQLite 日历（`calendar.db`）事件 CRUD、从邮件正文抽取日程（规则解析器 + LLM 两种模式）、导入去重 |
| `agent/mail_agent/mail_agent/agent.py` | LangChain ReAct Agent：7 个工具（搜索/读/删/批量删/加星/归档/发送），多轮会话记忆 |
| `agent/mail_agent/mail_agent/classifier.py` | 邮件分类：**LLM 优先**（`MAIL_LLM_*`/`DEEPSEEK_*`，整页一次调用），未命中回退 ML 模型（懒加载 + 按 message_id 缓存，最终回退 `other`）；`MAIL_CLASSIFIER_MODE` 可固定为 `llm`/`ml` |
| `agent/mail_agent/mail_agent/models.py` | `MailMessage`、`PageResponse`、`SendMailRequest`、`UpdateMailRequest`、OAuth 响应等 Pydantic 模型 |
| `agent/mail_agent/mail_agent/config.py` | 环境变量读取：Gmail 客户端、每个用户的 token 目录（`GMAIL_TOKEN_DIR`）、身份密钥（`JWT_SECRET`/`MAIL_INTERNAL_SECRET`）、LLM 配置 |
| `agent/mail_agent/ml/` | 训练好的分类模型 `email_classifier.joblib` 及加载器 |
| `agent/mcp_servers/mail_server.py` | MCP 网关（聊天场景），REST 客户端 + 关键词规则回退 |
| `agent/schemas/mail-agent.json` | mail agent 的 MCP 契约（invoke 输入/输出 schema） |
| `frontend_web/src/api/mail.ts` | **Web 端接口调用范例**（邮件） |
| `frontend_web/src/api/mailCalendar.ts` | **Web 端接口调用范例**（日历） |
| `frontend_web/src/api/mailAgent.ts` | **Web 端接口调用范例**（Agent 聊天） |
| `frontend_web/src/types.ts` | Web 端 TS 类型定义（与后端 JSON 形状一致，可对照翻译成 Kotlin data class） |

---

## 2. 本地启动与联调

### 2.1 启动 mail 服务（本地）

```bash
cd agent/mail_agent
python -m venv .venv
.venv\Scripts\pip install -e .
.venv\Scripts\uvicorn mail_agent.main:app --host 0.0.0.0 --port 5000 --reload
```

- 依赖：`fastapi`、`pydantic`、`google-api-python-client`、`google-auth-oauthlib`、`langchain`、`langgraph`、`scikit-learn` 等（见 `pyproject.toml`）。
- 健康检查：`curl http://localhost:5000/health` 返回 `{status, service, gmail_connected, gmail_users_connected, agent_configured, agent_model}`（`gmail_connected` = 是否有用户已绑定 Gmail）。
- 环境变量从仓库根 `.env` 读取（`config.py` 自动 `load_dotenv`）。与 mail 相关的变量：

| 环境变量 | 默认/回退 | 说明 |
|---|---|---|
| `GMAIL_CLIENT_ID` / `GMAIL_CLIENT_SECRET` | **必填，无默认**（缺失时 OAuth fail-closed） | Google Cloud OAuth Web 客户端；已从代码移除内置默认客户端，必须显式配置（Console → APIs & Services → Credentials） |
| `GMAIL_REDIRECT_URI` | 空 → 按请求 Host/X-Forwarded-Proto 推导为 `https://<host>/callback` | 本地设为 `http://localhost:5000/callback`（已注册在 Console） |
| `MAIL_FRONTEND_URL` | 空 → 回跳请求来源 | OAuth 完成后浏览器跳回的前端地址 |
| `MAIL_LLM_API_KEY` / `BASE_URL` / `MODEL` | 回退 `DEEPSEEK_API_KEY` / `api.deepseek.com` / `deepseek-v4-flash` | Agent 与日历 LLM 抽取共用 |
| `MAIL_CALENDAR_DB` | `agent/mail_agent/calendar.db` | SQLite 日历库路径 |
| `GMAIL_TOKEN_DIR` | `agent/mail_agent/gmail_tokens/` | **每个用户**一个 Gmail OAuth 刷新令牌文件（`<sha256(user_id)>.json`） |
| `JWT_SECRET` | 空（未配置则用户 JWT 通道 fail-closed） | 用户 JWT 验签密钥，**必须与后端 Java 的 `JWT_SECRET` 一致** |
| `MAIL_INTERNAL_SECRET` | 回退 `AGENT_SHARED_SECRET` | 内部令牌（聊天/MCP 通道代签）验签密钥 |

### 2.2 Gmail 授权（每个用户必须做一次，否则自己的邮件接口返回 409）

mail 服务直连真实 Gmail，需要**当前登录用户**先授权自己的 Gmail：

1. 服务跑在 5000 端口（`http://localhost:5000/callback` 必须已在 Google Cloud Console 注册为 Authorized redirect URI，**端口不能改**）。
2. 请求头带 JWT 调 `GET /api/mail/oauth/url`，得到 `auth_url`（已绑定该用户）。
3. 浏览器打开 `auth_url` → Google 同意页 → 授权后跳回 `/callback` → 服务用 code 换 token 并按 `state` 还原用户，持久化到 `gmail_tokens/<sha256(user_id)>.json`。
4. 之后 `GET /api/mail/oauth/status` 应返回 `{connected: true, email: ...}`（只反映当前用户）。

重新授权/换账号：调 `POST /api/mail/oauth/disconnect`（**只清当前用户**的 token）后重走流程。

> **移动端注意**：此流程是为 Web 浏览器设计的。原生 App 里建议用系统浏览器/WebView 打开 `auth_url`，授权完成后让服务端 `/callback` 自行完成换 token，App 再轮询 `GET /api/mail/oauth/status` 判断是否 connected（详见 §4.2）。

### 2.3 移动端联调的 base URL

- **本地**：直连 `http://<开发机IP>:5000`（移动端不走浏览器，无 CORS 限制）。
- **生产**：走 nginx 同源代理 `https://campuslink.tokeninf.xyz`，`/api/mail/*` 会被转发到 mail-service，`proxy_read_timeout 300s`（大接口够用）。**移动端 URL 与 Web 端一致：`https://campuslink.tokeninf.xyz/api/mail/...`**。
- Android 模拟器访问宿主机用 `http://10.0.2.2:5000`。

---

## 3. 通用约定

### 3.1 认证头

所有 `/api/mail/**` 接口必须带：

```
Authorization: Bearer <CampusLink JWT>
```

- 缺头 / 不是 `Bearer ` 开头 / token 无法验签 → **401** `{"code":"UNAUTHORIZED","error":"..."}`。
- 服务**验签用户 JWT**（HS256，`JWT_SECRET` 与后端一致）：用户身份 = `sub`（邮箱）；也可接受内部令牌（见 §4.1）。移动端传真实登录 JWT 即可。

### 3.2 统一错误格式

非 2xx 响应的 body 是扁平 JSON：

```json
{ "code": "GMAIL_ERROR", "error": "错误描述", ...可选的附加字段 }
```

常见错误码：

| HTTP | code | 触发场景 |
|---|---|---|
| 401 | `UNAUTHORIZED` | 缺 Authorization / 前缀不对 / JWT 验签失败（密钥不一致或已过期） |
| 409 | `GMAIL_NOT_CONNECTED` | 未授权 Gmail；**响应里带 `auth_url` 字段**，可直接拿去跳转授权 |
| 400 | `OAUTH_ERROR` | OAuth 回调缺 code/state 或 state 无效 |
| 422 | `CALENDAR_VALIDATION` | 日历事件时间非法（如 end < start、非 ISO 格式） |
| 404 | `CALENDAR_EVENT_NOT_FOUND` | 日历事件不存在或不属于当前用户 |
| 502 | `GMAIL_ERROR` | Gmail API 上游错误（附 Gmail 的 reason） |
| 503 | `MAIL_AGENT_NOT_CONFIGURED` | Agent 未配置 LLM key |
| 500 | `MAIL_AGENT_ERROR` | Agent 执行失败 |

移动端 `AuthenticatedHttpClient.parseError` 已按 `{code, error}` 解析，可直接复用。

### 3.3 时间格式

- 邮件 `created_at` / `updated_at`：Gmail `internalDate` 转换的 **UTC ISO 8601**（如 `2026-07-01T08:00:00+00:00`）。
- 日历 `start_time` / `end_time` / `created_at` / `updated_at`：**ISO 8601** 字符串（带时区或 naive 均可，后端按字符串比较；`datetime.fromisoformat` 解析）。
- 日期筛选参数（`after` / `before`）：`YYYY-MM-DD`（或 `YYYY/MM/DD`），按**接收时间**（internalDate）过滤，`after` 含当天、`before` 不含当天。

### 3.4 分页

`GET /api/mail/messages` 分页：`page`（**0 起始**）、`size`（默认 20，**最大 50**）。响应为 `PageResponse`：

```json
{
  "content": [MailMessage...],
  "page": 0,
  "size": 20,
  "total_elements": 137,
  "total_pages": 7,
  "first": true,
  "last": false
}
```

> ⚠️ `total_elements` 来自 Gmail 的 `resultSizeEstimate`，**是估算值**（普通列表场景），仅模糊搜索/日期筛选时为本地精确计数。UI 显示"共 X 封"时应以 `last` 为准做加载更多，而不是依赖 total。

---

## 4. 认证与身份（移动端必读）

### 4.1 两层认证

1. **CampusLink JWT**（App 登录后已有）：所有 `/api/mail/**` 的 Bearer 头。mail 服务**验签**该 JWT（HS256，`JWT_SECRET` 须与后端一致），`sub`（邮箱）即用户身份。移动端沿用现有 `SessionStore` 取 token 的机制。
2. **Gmail OAuth**（**每个用户自己的**账号）：决定 `connected` 状态。当前用户未连接时，其邮件/日历抽取接口返回 409（响应带该用户的 `auth_url`）。

> 聊天/MCP 通道（`mail-agent-mcp`）：用户 JWT 不离开 Chat Backend，因此网关用 `MAIL_INTERNAL_SECRET`（回退 `AGENT_SHARED_SECRET`）代签 30s 内部令牌（`aud=mail-service`，`sub`=数字 userId）调用 mail REST，按该 userId 使用自己的 Gmail 绑定。Web/移动端不涉及此通道。

### 4.2 Gmail OAuth 在移动端的建议流程

```
1. GET /api/mail/oauth/url          → {auth_url, connected}（auth_url 已绑定当前用户）
2. 用系统浏览器/WebView 打开 auth_url
   → 用户同意 → Google 302 到 https://campuslink.tokeninf.xyz/callback
   → mail-service 按 state 还原用户并换 token，持久化到该用户的 token 文件
     （/callback 由 nginx 转发，见 nginx.conf）
   → 302 到 MAIL_FRONTEND_URL/mail?connected=1（移动端可忽略此跳转）
3. App 轮询 GET /api/mail/oauth/status → {connected: true, email}
4. connected 后即可正常调邮件接口
```

- App 无需自己处理 code/state，`/callback` 的 code 交换在服务端完成（`state` 由服务端内存保存并绑定发起用户，单进程部署足够）。
- 若用 WebView 打开，**不要**拦截重定向自行处理；直接让页面走完，靠轮询 status 收尾即可。
- 生产必须通过注册过的域名（`https://campuslink.tokeninf.xyz/callback`）触发，否则 Google 报 `redirect_uri_mismatch`。
- `POST /api/mail/oauth/disconnect` 只清**当前用户**的 Gmail token，不影响其它用户；移动端仍建议谨慎暴露此入口。

### 4.3 用户隔离现状（重要 caveat）

- **邮件与日历都按用户隔离**：`user_id` = 验签后 JWT 的 `sub`（邮箱，Web/移动端）或内部令牌的 `sub`（聊天通道为数字 userId）。每个用户看到的是自己 Gmail 里的邮件、自己的日历事件。
- 因此：移动端**必须**把登录后拿到的 JWT 原样作为 Bearer 传入；未绑定 Gmail 的用户调邮件接口会得到 409 `GMAIL_NOT_CONNECTED`（带自己的 `auth_url`），引导授权而不是当成错误。
- ⚠ 聊天通道（数字 userId）与 Web/移动端（邮箱）是两个不同的用户键：同一个人若在两端分别授权，会得到两份独立的 Gmail 绑定（见 §4.1 说明）。

---

## 5. REST API 完整参考

> 基础路径：`/api/mail`（生产经 nginx 同源代理；本地直连 `:5000`）。以下省略公共 Bearer 头。

### 5.1 健康检查

**`GET /health`**（无鉴权）

```json
{ "status": "ok", "service": "mail-agent",
  "gmail_connected": true, "gmail_users_connected": 3,
  "agent_configured": true,
  "agent_model": "deepseek-v4-flash" }
```

### 5.2 OAuth

| 方法 | 路径 | 说明 | 响应 |
|---|---|---|---|
| GET | `/api/mail/oauth/url` | 获取 Google 授权链接 | `OAuthUrlResponse {auth_url, connected}` |
| GET | `/api/mail/oauth/status` | 查询连接状态 | `OAuthStatusResponse {connected, email: string\|null}` |
| POST | `/api/mail/oauth/disconnect` | 断开并删除**当前用户**的 token | `OAuthStatusResponse {connected:false, email:null}` |
| GET | `/callback` | Google 回调（浏览器直连，App 不直接调） | 302 → `{frontend}/mail?connected=1` |

### 5.3 邮件

#### 5.3.1 列表 `GET /api/mail/messages`

Query 参数：

| 参数 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `folder` | enum | `inbox` | `inbox` / `sent` / `archived` / `trash` / `spam`（映射关系见 §5.3.7） |
| `q` | string | `""` | 搜索词；自然语言走模糊匹配，含 `:` 的 Gmail 语法（`from:`、`subject:`）原样透传 |
| `unread` | bool | 空 | `true` 只看未读，`false` 只看已读 |
| `starred` | bool | 空 | `true` 只看加星，`false` 只看未加星 |
| `page` | int ≥0 | 0 | 0 起始页码 |
| `size` | int 1..50 | 20 | 每页条数 |
| `after` / `before` | date | 空 | 按**接收时间**过滤：`YYYY-MM-DD`，after 含当天、before 不含 |

响应：`PageResponse`（§3.4），`content` 为 `MailMessage[]`。

**搜索行为（`q`）**：不带 `:` 的自然语言词（含中文）走本地模糊匹配——Gmail 先按 OR 预筛最多 200 封候选，再对 subject/sender/preview 做子串命中、词元覆盖率、编辑距离打分（阈值 0.30，容忍拼写错误如 `exma`→`exam`），按分数降序、同时刻新的在前，本地分页。带 `:` 的查询（如 `from:prof@nus.edu.sg`）原样作为 Gmail 语法透传。

#### 5.3.2 详情 `GET /api/mail/messages/{message_id}`

- 返回完整 `MailMessage`（含 `body` 纯文本、`body_html` 原始 HTML、`category`）。
- ⚠️ **副作用：会把该邮件标记为已读**（`mark_read=True`）。移动端"读邮件"页打开即调用即可，符合语义。
- 60 秒内重复读取同一封走内存缓存，不重复拉取。

#### 5.3.3 发送 `POST /api/mail/messages` → 201

请求体 `SendMailRequest`：

```json
{ "recipients": ["a@nus.edu.sg", "b@nus.edu.sg"], "subject": "标题", "body": "正文" }
```

- `recipients`：1..20 个，必须含 `@`，自动 trim、去空。
- `subject`：1..160 字符；`body`：1..10000 字符。
- 校验失败返回 **422**（FastAPI 校验错误体：`{"detail":[...]}`，与业务错误格式不同，移动端解析时注意）。
- 响应：发送成功后的 `MailMessage`（发件人为**当前用户自己的** Gmail 账号，`folder=sent`）。

#### 5.3.4 更新标记 `PATCH /api/mail/messages/{message_id}`

请求体 `UpdateMailRequest`（字段均可选）：

```json
{ "read": true, "starred": false, "folder": "inbox" }
```

- `read`：true=标记已读，false=标记未读。
- `starred`：true=加星，false=取消星标。
- `folder`：`inbox`（加回收件箱）或 `archived`（移出收件箱）；`trash` 等价于删除。
- 响应：更新后的 `MailMessage`。

#### 5.3.5 归档 `POST /api/mail/messages/{message_id}/archive`

等价于 PATCH `{folder:"archived"}`。响应：`MailMessage`。

#### 5.3.6 删除（移入回收站）`POST /api/mail/messages/{message_id}/delete`

- 注意：**删除 = 移入 Trash**，不是永久删除（Gmail `gmail.modify` scope 不 bypass 回收站）。
- 响应：`MailMessage`（此时 `folder=trash`）。
- 服务端有 5 分钟幂等保护（Agent 场景），REST 直接调用无碍。

#### 5.3.7 文件夹 ↔ Gmail 标签映射

| 前端 folder | Gmail |
|---|---|
| `inbox` | `INBOX` 标签 |
| `sent` | `SENT` 标签 |
| `trash` | `TRASH` 标签 |
| `spam` | `SPAM` 标签 |
| `archived` | 不在 inbox/sent/trash/draft/spam 中的邮件 |

`read` ↔ `UNREAD` 标签（无 UNREAD = 已读）；`starred` ↔ `STARRED` 标签。

### 5.4 日历

日历数据存在服务端 SQLite（`calendar.db`），**与 Gmail 无关**，按 `user_id`（= 验签后的用户身份，Web/移动端为邮箱，见 §4.3）隔离。

#### 5.4.1 事件列表 `GET /api/mail/calendar/events`

Query：`start` / `end`（可选，ISO 日期时间；返回 `[start, end)` 区间内的事件，即 `end_time > start && start_time < end`）。

响应：`CalendarEvent[]`，按 `start_time` 升序。

#### 5.4.2 创建事件 `POST /api/mail/calendar/events` → 201

请求体 `CalendarEventRequest`：

```json
{
  "title": "CS2103 期末考试",          // 1..200 字符，必填
  "description": "",                   // ≤5000，可选
  "location": "LT19",                  // ≤300，可选
  "start_time": "2026-08-10T14:00:00", // ISO 日期时间，必填
  "end_time": "2026-08-10T16:00:00",   // ISO 日期时间，必填，须晚于 start
  "all_day": false                     // 是否全天，可选
}
```

- 时间非法（非 ISO / end ≤ start）→ **422** `CALENDAR_VALIDATION`。
- 响应：`CalendarEvent`，`id` 为服务端生成的 32 位 hex，`source="manual"`。

#### 5.4.3 事件详情 `GET /api/mail/calendar/events/{event_id}`

- 不存在或不属于当前 user → **404** `CALENDAR_EVENT_NOT_FOUND`。
- 响应：`CalendarEvent`。

#### 5.4.4 更新事件 `PATCH /api/mail/calendar/events/{event_id}`

请求体 `CalendarEventUpdate`（字段均可选，partial update）：

```json
{ "title": "新标题", "start_time": "2026-08-11T09:00:00" }
```

- 校验规则同创建；404 语义同详情。
- 响应：更新后的 `CalendarEvent`。

#### 5.4.5 删除事件 `DELETE /api/mail/calendar/events/{event_id}`

- 成功 → **204 无响应体**；不存在 → 404。
- ⚠️ 移动端现有 `AuthenticatedHttpClient` **没有 DELETE 方法**，需要扩展（见 §6.2）。

#### 5.4.6 抽取日程 `POST /api/mail/calendar/extract`

扫描最近邮件中的时间地点信息，**返回建议日程（不写入）**，用户确认后再 import。

Query 参数：`days`（0..30，默认 0 = 仅今天；`days=2` = 最近 3 个日历日）、`max_results`（1..50，默认 20，最多扫描并解析的邮件数）。

响应 `ExtractResponse`：

```json
{
  "days": 0,
  "scanned": 12,
  "mode": "llm",            // "llm" 或 "rules"
  "events": [ ExtractedSchedule... ]
}
```

- `mode`：默认 `auto` 策略——配置了 LLM key 则优先 LLM 抽取（返回 `llm`），LLM 失败或为空自动回退规则解析器（返回 `rules`）。可用 `MAIL_CALENDAR_EXTRACT_MODE=llm|rules|auto` 强制。
- `ExtractedSchedule` 字段：`key`、`title`、`description`、`location`、`start_time`、`end_time`、`all_day`、`source_email_id`、`email_subject`。
- 规则解析器支持：ISO 日期（`2026-08-10`）、美式日期（`08/10/2026`）、月份+日（`Aug 10, 2026`）、星期几（`Monday`→最近一个）、相对词（`today`/`tomorrow`）、`14:00` / `2:00 PM` / `2pm`、地点提示（`at / in / venue / location / room / where`）。日期窗口：过去 1 天到未来 90 天。
- ⚠️ **慢接口**：要拉取邮件全文 + 可能调 LLM（每批最多 15 封、每封正文截断 1500 字符）。Web 端超时设了 **180 秒**，移动端请勿用默认 10s 超时。

#### 5.4.7 导入日程 `POST /api/mail/calendar/import`

请求体 `ImportRequest`：`{ "events": [ExtractedSchedule...] }`（≤200 条）。

响应 `ImportResponse`：

```json
{ "imported": 3, "skipped": 1, "events": [CalendarEvent...] }
```

- 去重规则：日历中已存在同 `(source_email_id, start_time)` 或同 `(title, start_time)` 的事件则跳过。
- 导入事件的 `source="mail"`，带 `source_email_id`。
- 建议移动端也设较长超时（Web 端 60s）。

### 5.5 Mail Agent（自然语言操作邮件）

**`POST /api/mail/agent/chat`** —— 通过 LLM（DeepSeek）理解自然语言并调用 7 个工具操作邮件（搜索/读/删/批量删/加星/归档/发送）。适合在聊天场景复用；独立邮件页不需要。

请求体：

```json
{ "message": "帮我找最近三天未读的考试邮件", "session_id": "任意字符串" }
```

- `message`：1..8000 字符，必填。
- `session_id`：可选（≤256），多轮对话上下文键，服务端清洗为 `[A-Za-z0-9_-]` 后用作 LangGraph thread id；不传则自动生成。

响应：

```json
{
  "response": "找到 3 封未读邮件：...",
  "status": "completed",
  "session_id": "cleaned-session",
  "actions_taken": [ { "tool": "search_mail", "args": {"unread": true} } ],
  "model": "deepseek-v4-flash"
}
```

- 未配置 LLM key → **503** `MAIL_AGENT_NOT_CONFIGURED`。
- ⚠️ **慢接口**：LLM 推理 + 多轮工具调用，Web 端超时 **300 秒**。移动端聊天场景需长超时 + 加载态。

### 5.6 日历事件字段速览

`CalendarEvent`：

```json
{
  "id": "9f3c2a1b...32hex",
  "user_id": "<验签后的用户身份，Web/移动端为邮箱>",
  "title": "CS2103 期末考试",
  "description": "",
  "location": "LT19",
  "start_time": "2026-08-10T14:00:00",
  "end_time": "2026-08-10T16:00:00",
  "all_day": false,
  "source": "mail",            // "manual" | "mail"
  "source_email_id": "19ffd146e912598c",   // 邮件来源的事件才有
  "created_at": "2026-08-01T08:00:00+00:00",
  "updated_at": "2026-08-01T08:00:00+00:00"
}
```

`MailMessage`：

```json
{
  "id": "19ffd146e912598c",
  "subject": "ISS Admissions - Payment",
  "sender": "ISS Admissions <iss-admissions@nus.edu.sg>",
  "recipients": ["student@u.nus.edu"],
  "preview": "Your tuition payment is due.",
  "body": "Your tuition payment is due.",
  "body_html": null,
  "folder": "inbox",
  "category": "campus",        // campus | career | finance | other
  "read": false,
  "starred": false,
  "created_at": "2026-07-01T08:00:00+00:00",
  "updated_at": "2026-07-01T08:00:00+00:00"
}
```

---

## 6. Android 集成指南

### 6.1 沿用现有网络层

移动端已有 `AuthenticatedHttpClient`（OkHttp + 自动带 Bearer token + 401 跳登录 + `{code,error}` 错误解析）和 `FacilitiesApi` 风格，直接照搬即可：

```kotlin
// core/network/MailApi.kt —— 参考 FacilitiesApi 的结构
class MailApi(
    private val client: AuthenticatedHttpClient,
    private val json: Json,
) {
    suspend fun listMessages(folder: String, page: Int, size: Int = 20): MailPageResponse =
        json.decodeFromString(MailPageResponse.serializer(), client.get(
            "api/mail/messages",
            listOf("folder" to folder, "page" to page.toString(), "size" to size.toString()),
        ))

    suspend fun getMessage(id: String): MailMessage =
        json.decodeFromString(MailMessage.serializer(), client.get("api/mail/messages/$id"))

    suspend fun send(request: SendMailRequest): MailMessage =
        json.decodeFromString(MailMessage.serializer(),
            client.post("api/mail/messages", json.encodeToString(request)))

    suspend fun updateMessage(id: String, patch: UpdateMailRequest): MailMessage =
        json.decodeFromString(MailMessage.serializer(),
            client.patch("api/mail/messages/$id", json.encodeToString(patch)))

    suspend fun archive(id: String): MailMessage =
        json.decodeFromString(MailMessage.serializer(), client.post("api/mail/messages/$id/archive", "{}"))

    suspend fun delete(id: String): MailMessage =
        json.decodeFromString(MailMessage.serializer(), client.post("api/mail/messages/$id/delete", "{}"))
}
```

### 6.2 需要做的三处网络层扩展

1. **DELETE 方法**：`AuthenticatedHttpClient` 目前只有 GET/POST/PATCH，日历删除（`DELETE /api/mail/calendar/events/{id}`）需要新增 DELETE（参考现有 `execute`，OkHttp 用 `Request.Builder().delete()`）。
2. **长超时**：默认 OkHttp 读超时远小于后端大接口耗时。日历抽取（建议 ≥180s）、日程导入（≥60s）、Agent 聊天（≥300s）要单独配置 OkHttp client 或逐请求覆盖超时；列表/详情等常规接口 20s 足够。
3. **204 无响应体处理**：`DELETE /api/mail/calendar/events/{id}` 成功返回 204 空 body，`execute` 的 `response.body.string()` 为空串，注意不要强行 JSON 解析。

### 6.3 Kotlin data class 对照（kotlinx.serialization）

字段名与 JSON 完全一致（snake_case），直接映射：

```kotlin
@Serializable data class MailMessage(
    val id: String, val subject: String, val sender: String,
    val recipients: List<String>, val preview: String, val body: String,
    val body_html: String? = null, val folder: String, val category: String,
    val read: Boolean, val starred: Boolean,
    val created_at: String, val updated_at: String,
)

@Serializable data class MailPageResponse(
    val content: List<MailMessage>, val page: Int, val size: Int,
    val total_elements: Int, val total_pages: Int,
    val first: Boolean, val last: Boolean,
)

@Serializable data class CalendarEvent(
    val id: String, val user_id: String, val title: String,
    val description: String = "", val location: String = "",
    val start_time: String, val end_time: String, val all_day: Boolean = false,
    val source: String = "manual", val source_email_id: String? = null,
    val created_at: String, val updated_at: String,
)
```

### 6.4 页面功能 ↔ 接口对照

| 移动端功能 | 接口 | 备注 |
|---|---|---|
| 邮箱首页（未连接提示） | `GET /api/mail/oauth/status` | 未连接时先走 §4.2 授权 |
| 收件箱/已发送/回收站/垃圾邮件/归档 | `GET /api/mail/messages?folder=...` | 下拉刷新 = 重新拉 page 0 |
| 加载更多 | 同上 + `page` 递增 | 以 `last=false` 判断还有没有 |
| 搜索 | `GET /api/mail/messages?q=...` | 中文/自然语言直接传即可 |
| 筛选未读/加星 | `?unread=true&starred=true` | |
| 邮件详情 | `GET /api/mail/messages/{id}` | 会标已读，详情页打开即调用 |
| 标记已读/未读、加星 | `PATCH /api/mail/messages/{id}` | 列表滑动操作可用 |
| 归档 / 删除 | `POST .../archive` / `POST .../delete` | 删除=进回收站 |
| 发信 | `POST /api/mail/messages` | |
| 日历月/周视图 | `GET /api/mail/calendar/events?start=&end=` | 一次拉区间内事件 |
| 新建/编辑/删除日程 | `POST/PATCH/DELETE /api/mail/calendar/events...` | |
| 从邮件导入日程 | `POST .../extract` → 用户确认 → `POST .../import` | 两段式，抽取慢需 loading |
| 聊天问邮件（可选） | `POST /api/mail/agent/chat` | 长超时 |

### 6.5 聊天场景（如需要）

App 的聊天页若想支持"帮我删掉促销邮件"这类请求，走的是**Java 聊天后端 → MCP 网关（8081）**链路，不是直连 mail REST。移动端只需调现有聊天接口（`/api/chat/stream` SSE）即可，后端编排会自动路由到 mail-agent。mail 侧的 MCP 契约见 `agent/schemas/mail-agent.json`（`invoke` 输入 `{message, conversation_context, confirmed, confirmation_id}`，输出 `{response, status: completed|needs_more_info|needs_confirmation|failed, confirmation_required, actions_taken, request_id}`）。删除类操作会返回 `status=needs_confirmation` + `confirmation_id`，需要 UI 二次确认后带 `confirmed:true` 重发。移动端若无聊天页，可忽略本节。

---

## 7. 移动端注意事项汇总（避坑清单）

1. **按用户绑定 Gmail**：每个用户操作的是**自己授权的 Gmail 账号**；未绑定前自己的邮件接口返回 409（带自己的 `auth_url`）。文案上可以放心说"我的邮箱"，但注意聊天通道与 Web 通道的绑定键不同（§4.3）。
2. **Bearer 验签**：服务端验签用户 JWT（HS256，`JWT_SECRET` 与后端一致），无效 token → 401。移动端始终传真实登录 JWT 即可。
3. **`GET` 详情有副作用**：打开详情即标已读。做"预取"或缓存时要小心。
4. **`total_elements` 是估算**：分页 UI 以 `last` 为准。
5. **0 起始页码**：`page=0` 是第一页；`page` 与 `size` 超出范围会 422。
6. **删除是软删除**（进回收站）：若产品要求"永久删除"，后端需要加 `gmail.permanentlyDelete` scope，当前不支持。
7. **慢接口超时**：extract（180s）/ import（60s）/ agent chat（300s）必须单独配长超时；nginx 已放宽到 300s。
8. **ISO 时间字符串**：接口用字符串交换时间，移动端解析/格式化用 `java.time.OffsetDateTime.parse(...)`（ISO_OFFSET_DATE_TIME），注意 `body_html` 可能为 `null`。
9. **204 无 body**：日历删除成功无响应体。
10. **未连接 409 带 `auth_url`**：所有邮件接口都可能先返回 409 `GMAIL_NOT_CONNECTED`，响应含可直接跳转的 `auth_url`——App 应拦截此错误码引导授权，而不是当成失败。
11. **CORS**：原生 App 不受 CORS 限制，无需处理；但**生产请走 HTTPS 域名**（`https://campuslink.tokeninf.xyz`），与 OAuth 注册回调同源，避免 `redirect_uri_mismatch`。
12. **本地联调**：模拟器访问宿主机 mail 服务用 `http://10.0.2.2:5000`；真机用局域网 IP，且需把 `GMAIL_REDIRECT_URI` 指到可达地址或在浏览器里完成授权后再切回 App。

---

## 8. 常见问题排查

| 现象 | 原因/解法 |
|---|---|
| 邮件接口 409 `GMAIL_NOT_CONNECTED` | **当前用户**还没做 Gmail 授权；调 `/api/mail/oauth/url` 打开自己的 `auth_url` 完成授权（每个用户各自授权一次） |
| Google 报 `redirect_uri_mismatch` | 访问域名与 Console 注册的回调不一致；生产必须走 `https://campuslink.tokeninf.xyz/callback` |
| 401 `UNAUTHORIZED` | Bearer 缺失/格式错/JWT 验签失败（`JWT_SECRET` 与后端不一致或已过期） |
| 列表接口很慢 | Gmail API 本身延迟 + 批量元数据拉取；分页缓存只在服务端进程内，重启后首屏会慢一点 |
| `q` 搜中文整句没结果 | 中文无空格，模糊匹配走子串/相似度；太长的句子建议拆关键词 |
| 日历事件"不见了" | 换了一个身份键（重新登录/不同通道）→ `user_id` 变化；见 §4.3 |
| extract 超时 | LLM 抽取慢；确认服务端已配 `MAIL_LLM_API_KEY`（否则自动走规则模式会快很多），移动端用 ≥180s 超时 |
| agent chat 503 | `MAIL_LLM_API_KEY` / `DEEPSEEK_API_KEY` 未配置 |

---

## 9. 相关文件索引

- 后端实现：`agent/mail_agent/`（入口 `mail_agent/main.py`，身份解析 `mail_agent/auth.py`）
- MCP 网关：`agent/mcp_servers/mail_server.py`、契约 `agent/schemas/mail-agent.json`
- Web 端调用范例：`frontend_web/src/api/mail.ts`、`mailCalendar.ts`、`mailAgent.ts`；类型定义 `frontend_web/src/types.ts`
- 部署：`docker-compose.yml`（`mail-service` :5000、`mail-agent-mcp` :8081）、`frontend_web/nginx.conf`（`/api/mail/` 与 `/callback` 代理）、`agent/mail_agent/Dockerfile`、`agent/mail_agent/docker-compose.yml`
- 环境变量：仓库根 `.env.example`（`GMAIL_*`、`MAIL_LLM_*` 段，以及身份密钥 `JWT_SECRET` / `MAIL_INTERNAL_SECRET`）
- 服务自述文档：`agent/mail_agent/README.md`
