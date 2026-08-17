# CampusLink Mail Service

Gmail-backed REST service for the web mail module. Messages are fetched and
operated on directly through the Gmail API using OAuth2, and every message is
automatically tagged with a category (`campus`, `career`, `finance` or
`other`) — classified by the LLM first, with the trained ML model as fallback.

**Each CampusLink user binds their own Gmail account**: the caller identity is
resolved from the `Authorization` header (user JWT `sub` = email for the web
path, or an internal MCP-gateway token for the chat path) and every mail /
calendar operation runs against that user's own credentials.

## Layout

```text
agent/mail_agent/
├── mail_agent/                    # FastAPI service package
│   ├── main.py                    # REST routes (/api/mail/**)
│   ├── auth.py                    # Identity resolution (user JWT + internal token)
│   ├── agent.py                   # LangChain agent + 7 tools (per-user bound)
│   ├── gmail_service.py           # Per-user Gmail API operations -> MailMessage
│   ├── models.py                  # Pydantic models + MailCategory enum
│   ├── classifier.py              # Classification: LLM first, ML fallback (lazy + cache)
│   └── config.py                  # OAuth client / token dir / env config
├── gmail_tokens/                  # Per-user Gmail OAuth tokens (<sha256(user_id)>.json)
├── ml/                            # Trained email classifier
│   ├── email_classifier.py
│   └── models/email_classifier.joblib
├── tests/
├── Dockerfile                     # 自包含镜像（build context = 本目录）
├── docker-compose.yml             # 独立部署（Gmail + Calendar）
└── pyproject.toml
```

## Run

```bash
cd agent/mail_agent
python -m venv .venv
.venv\Scripts\pip install -e .
.venv\Scripts\uvicorn mail_agent.main:app --host 0.0.0.0 --port 5000 --reload
```

The install pulls the Gmail/API dependencies plus the ML stack
(`numpy<2`, `scipy`, `scikit-learn`, `joblib`) needed to load the trained
classifier.

The service runs on **port 5000** because the OAuth redirect URI registered in
Google Cloud Console is `http://localhost:5000/callback`. The Vite dev server
proxies `/api/mail` here, and the MCP mail gateway
(`agent/mcp_servers/mail_server.py`) calls this service via `MAIL_REST_URL`
(default `http://127.0.0.1:5000`).

## Run with Docker

The whole mail module backend (REST + calendar + ML classifier + LangChain
agent) is packaged as a self-contained image (`build context = agent/mail_agent`,
no repo-root context needed).

### Standalone（只起 mail 服务）

```bash
# 首次：准备仓库根 .env（含 GMAIL_CLIENT_ID / GMAIL_CLIENT_SECRET / MAIL_LLM_API_KEY 等）
cp ../../.env.example ../../.env

# 构建并启动
docker compose -f agent/mail_agent/docker-compose.yml up -d --build

# 验证
curl http://localhost:5000/health
```

### 并入整站 compose（推荐）

根 `docker-compose.yml` 已含 `mail-service`（随 `docker compose up -d` 一起起），
且 `mail-agent-mcp` 通过 `MAIL_REST_URL=http://mail-service:5000` 在容器网络内
直连 mail 服务，不再依赖宿主机上手动运行的进程。

### 容器化要点

* **宿主机端口必须保持 5000**：Google Cloud Console 注册的回调
  `http://localhost:5000/callback` 要求浏览器从宿主机访问该端口进入容器，
  因此 `ports` 映射固定为 `5000:5000`，不能改成别的宿主端口。
* **数据持久化**：每个用户的 Gmail OAuth token（`gmail_tokens/`，`GMAIL_TOKEN_DIR`）
  与 `calendar.db`（SQLite 日历库）在镜像内默认落到 `/data`（`GMAIL_TOKEN_DIR` /
  `MAIL_CALENDAR_DB`），compose 用命名卷 `mail_data:/data` 持久化，
  容器重建/升级不丢失授权与日历数据。本地开发仍落在 `agent/mail_agent/`。
* **Gmail 凭据 / LLM 配置**：从仓库根 `.env` 读取（`env_file`），
  或直接在 compose 的 `environment` 覆盖（`GMAIL_CLIENT_ID`、
  `GMAIL_CLIENT_SECRET`、`GMAIL_REDIRECT_URI`、`MAIL_LLM_API_KEY` 等）。
* **身份密钥**：`JWT_SECRET` 必须与后端 Java 一致（用户 JWT 验签）；聊天/MCP
  通道经 `MAIL_INTERNAL_SECRET`（回退 `AGENT_SHARED_SECRET`）代签内部令牌。
* **非 root 运行**：镜像内以 `app` 用户运行；`/data` 属主为 `app`，
  命名卷会自动继承该属主。若改用 bind mount，请确保目录权限可写。
* 健康检查：`GET /health`（`gmail_connected` = 是否有用户已绑定 / `agent_configured`）。

### 构建 / 推送镜像（CI 已接入）

```bash
docker build -t campuslink-mail-agent agent/mail_agent
# CD workflow（.github/workflows/cd-deploy.yml）会在 push main 时构建并推送
# ghcr.io/<owner>/campuslink-mail-agent:<sha>，生产 override（docker-compose.prod.yml）
# 中 mail-service 引用该镜像。
```

## Per-user Gmail authorization

1. Configure the Google OAuth web client via **required** env vars
   `GMAIL_CLIENT_ID` / `GMAIL_CLIENT_SECRET` (no built-in client ships anymore —
   the service fails closed if they are missing; get them from Google Cloud
   Console → APIs & Services → Credentials → OAuth 2.0 Client IDs).
   `http://localhost:5000/callback` **must** be listed as an
   Authorized redirect URI in the Google Cloud Console.
2. Start the service (port 5000).
3. Each user authorises **their own** Gmail account. As an authenticated
   CampusLink user, call:

   ```bash
   curl -H "Authorization: Bearer <jwt>" http://localhost:5000/api/mail/oauth/url
   ```

   ...or click **Connect Gmail** in the web Mail page. Open the returned
   `auth_url`, grant Gmail access. Google redirects to
   `http://localhost:5000/callback`, which exchanges the code and stores a
   refresh token for **that user** (keyed by their identity) under
   `gmail_tokens/`.
4. The user's mailbox is now served from `/api/mail/messages`. Other users are
   not affected and see `409 GMAIL_NOT_CONNECTED` with their own `auth_url`
   until they authorize.

The identity is verified with the CampusLink user JWT (HS256, `JWT_SECRET` must
match the Java backend's `JWT_SECRET`; `sub` = email). The web frontend already
sends this JWT as `Authorization: Bearer <jwt>`.

The chat / MCP path cannot carry the user JWT (it never leaves the chat
backend), so `mail-agent-mcp` mints a short-lived **internal token** (HS256,
`MAIL_INTERNAL_SECRET` or `AGENT_SHARED_SECRET`, `aud=mail-service`, 30s TTL).
The Chat Backend resolves the numeric delegation subject to the user's email and
adds it as `user_email`; `mail-agent-mcp` uses that value as the internal token
subject, so chat operations and the native Mail page use the same Gmail binding.

To re-authorize or switch accounts: call `POST /api/mail/oauth/disconnect`
(removes **only your own** token) and repeat.

## Email classification

Every message returned by the service is automatically tagged with one of four
categories:

| Category | Meaning                                    |
|----------|--------------------------------------------|
| `campus` | University/campus life, courses, events     |
| `career` | Jobs, internships, career fairs             |
| `finance`| Payments, invoices, financial matters       |
| `other`  | Anything else (personal, promo, social, …)  |

The tag is exposed as the `category` field on each `MailMessage` and rendered
as a colored chip in the web Mail page (list and detail view); the MCP mail
gateway also shows it in chat summaries.

Classification is **LLM-first with ML fallback**:

1. the LLM (DeepSeek, configured via `MAIL_LLM_*` / `DEEPSEEK_*`) classifies
   each page of messages in a single call (batches of up to 50);
2. messages the LLM does not answer (missing entry, invalid category, call
   failure) fall back to the trained ML model in `agent/mail_agent/ml`;
3. anything still unclassified falls back to `other`.

Classification is best-effort:

* the LLM/model are used lazily and predictions are cached per message id for a
  bounded window, so repeated page loads cost nothing;
* if the LLM and the model are both unavailable, the service keeps working and
  every message falls back to `other`;
* the strategy can be pinned with `MAIL_CLASSIFIER_MODE` (`auto` = LLM first,
  ML fallback, default; `llm` = LLM only; `ml` = ML only);
* the ML model path can be overridden with the `MAIL_CLASSIFIER_MODEL` env var
  (default: `ml/models/email_classifier.joblib`).

## Mail agent (LangChain)

The service exposes a natural-language mail agent built with LangChain
(`create_react_agent`). It runs an OpenAI-compatible chat model (DeepSeek by
default) and can call the following tools against the connected Gmail account:

| Tool               | Description                                    |
|--------------------|------------------------------------------------|
| `search_mail`      | Search / list messages                         |
| `read_mail`        | Read the full body (marks the message read)    |
| `delete_mail`      | Move a message to trash                        |
| `delete_mail_batch`| Move ALL matching messages to trash            |
| `star_mail`        | Star / unstar a message                        |
| `archive_mail`     | Remove a message from the inbox                |
| `send_mail`        | Compose and send a new message                 |

Every mutation tool accepts either an explicit `message_id` (returned by
`search_mail`) or a natural-language `query` that resolves to the first
matching message. Multi-turn conversation is kept per `session_id` (reused as
the LangGraph thread id).

Model configuration comes from the repository root `.env`:

| Env var                 | Default / fallback                                  |
|-------------------------|-----------------------------------------------------|
| `MAIL_LLM_API_KEY`      | falls back to `DEEPSEEK_API_KEY`                    |
| `MAIL_LLM_BASE_URL`     | falls back to `DEEPSEEK_BASE_URL` (api.deepseek.com)|
| `MAIL_LLM_MODEL`        | falls back to `DEEPSEEK_MODEL` (deepseek-v4-flash)  |
| `MAIL_AGENT_MAX_TOKENS` | 2000                                                |
| `MAIL_CLASSIFIER_MODE`  | `auto` (LLM first, ML fallback); `llm` / `ml` to pin |

Chat endpoint (same `Authorization: Bearer <jwt>` contract as the rest of the
service):

```bash
curl -X POST http://localhost:5000/api/mail/agent/chat \
  -H "Authorization: Bearer <jwt>" \
  -H "Content-Type: application/json" \
  -d '{"message": "帮我找最近的未读邮件", "session_id": "session-1"}'
```

Response:

```json
{
  "response": "找到 3 封未读邮件：...",
  "status": "completed",
  "session_id": "session-1",
  "actions_taken": [{"tool": "search_mail", "args": {"query": "", "unread": true}}],
  "model": "deepseek-v4-flash"
}
```

### Chat 通信（chat 模块 → mail agent）

聊天里的邮件请求由 **mail 模块自己的 agent** 直接回复：编排层（chat agent）把消息
经 MCP 转发给 `mail-agent-mcp`（`agent/mcp_servers/mail_server.py`），其 `invoke`
主路径调用本服务 `POST /api/mail/agent/chat`，把编排层的消息与多轮会话（`session_id`，
按 `mcp-<user>-<chat-session>` 派生，避免跨用户串记忆）透传给本 agent，再把 agent
的回复按 MCP 契约返回给编排层 → SSE → 前端。

```
用户 → chat-backend(/api/chat/stream) → 编排层(意图路由 → mail-agent)
     → mail-agent-mcp (MCP invoke)
     → 本服务 /api/mail/agent/chat（LangChain agent 回复）
     → 编排层 → SSE → 用户
```

* **agent 未配置（503）/ 调用失败**时，`mail-agent-mcp` 自动回退到关键词规则分派
  （直接调 `/api/mail/messages` 等 REST 端点），无 LLM 凭据的环境仍可用。
* agent 的删除/发送确认是**对话式**的（agent 先与你确认再执行），不触发编排层的
  结构化 HITL 确认按钮流。
* 相关配置：`MAIL_AGENT_CHAT_TIMEOUT_SECONDS`（默认 60，agent 多轮回复的超时上限）。

## API

| Method | Path                              | Description                                    |
|--------|-----------------------------------|------------------------------------------------|
| GET    | `/api/mail/oauth/url`             | Google consent URL                             |
| GET    | `/api/mail/oauth/status`          | `{connected, email}`                           |
| GET    | `/callback`                       | OAuth redirect target (code -> token)          |
| POST   | `/api/mail/oauth/disconnect`      | Forget the stored Gmail token                  |
| GET    | `/api/mail/messages`              | Page of messages (`folder`, `q`, `unread`, `starred`, `page`, `size`) |
| GET    | `/api/mail/messages/{id}`         | Full message (marks read)                      |
| POST   | `/api/mail/messages`              | Send mail                                      |
| PATCH  | `/api/mail/messages/{id}`         | `read` / `starred` / `folder`                  |
| POST   | `/api/mail/messages/{id}/archive` | Remove from inbox                              |
| POST   | `/api/mail/messages/{id}/delete`  | Move to trash                                  |
| POST   | `/api/mail/agent/chat`            | LangChain agent chat (search/read/delete/star/archive/send) |
| GET    | `/api/mail/calendar/events`       | List events (`start`, `end` ISO bounds)        |
| POST   | `/api/mail/calendar/events`       | Create a manual event                          |
| GET    | `/api/mail/calendar/events/{id}`  | Get one event                                  |
| PATCH  | `/api/mail/calendar/events/{id}`  | Update an event                                |
| DELETE | `/api/mail/calendar/events/{id}`  | Delete an event                                |
| POST   | `/api/mail/calendar/extract`      | Scan recent mail and propose schedules (`days`, `max_results`) |
| POST   | `/api/mail/calendar/import`       | Import confirmed schedules (dedupes)           |

All `/api/mail/**` endpoints require `Authorization: Bearer <jwt>`. Listing is
paginated: `page` (0-based) and `size` (default 20, max 50); the response is a
`PageResponse` with `content`, `total_elements`, `total_pages`, `first` and
`last`.

### Calendar

Events are stored per user (keyed by the bearer token) in a small SQLite
database at `agent/mail_agent/calendar.db` (override with `MAIL_CALENDAR_DB`).
Fields: `title`, `description`, `location`, `start_time` / `end_time` (ISO
datetimes), `all_day`, `source` (`manual` or `mail`) and `source_email_id`.

Schedule extraction from mail is a two-step flow so nothing is written without
user confirmation:

1. `POST /api/mail/calendar/extract?days=0` scans recent emails (0 = today only;
   `days=2` scans the last three calendar days) and returns proposed schedules
   parsed from subject/body date-time-location mentions — nothing is written.
   Extraction is **LLM-powered by default**: the DeepSeek model (configured via
   `MAIL_LLM_API_KEY` / `MAIL_LLM_BASE_URL` / `MAIL_LLM_MODEL`, falling back to
   `DEEPSEEK_*`) reads each email and returns structured schedules. The response
   includes a `mode` field (`llm` or `rules`) so the UI can show which strategy
   produced the proposals. Set `MAIL_CALENDAR_EXTRACT_MODE` to `llm` (LLM only),
   `rules` (built-in pattern parser only) or `auto` (default: LLM, falling back
   to rules on any failure).
2. The frontend shows the proposals; after the user confirms, `POST
   /api/mail/calendar/import` saves them, skipping any that are already in the
   calendar (same `source_email_id` + start time, or same title + start time).

### Search (`q` parameter)

`q` uses **fuzzy matching** for natural-language terms: Gmail first pre-filters
candidates with an `OR` query (up to 200 messages), then each message is scored
locally against subject / sender / preview using substring hits, token coverage
and edit-distance similarity (typo tolerance, e.g. `exma` matches `exam`).
Results are ranked by score (newest first on ties) and paginated locally.

Queries that contain Gmail search syntax (e.g. `from:prof@nus.edu.sg`,
`subject:exam`) are passed through unchanged as exact Gmail queries.

Message shape (each message includes the predicted category):

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
  "category": "campus",
  "read": false,
  "starred": false,
  "created_at": "2026-07-01T08:00:00+00:00",
  "updated_at": "2026-07-01T08:00:00+00:00"
}
```

### Folder mapping (Gmail labels)

| Frontend folder | Gmail                                                   |
|-----------------|---------------------------------------------------------|
| `inbox`         | `INBOX` label                                           |
| `sent`          | `SENT` label                                            |
| `trash`         | `TRASH` label                                           |
| `archived`      | not in `inbox`/`sent`/`trash`/`draft`/`spam`            |

`read` <-> `UNREAD` label, `starred` <-> `STARRED` label. Scopes requested:
`https://www.googleapis.com/auth/gmail.modify`.
