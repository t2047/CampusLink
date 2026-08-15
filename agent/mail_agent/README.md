# CampusLink Mail Service

Gmail-backed REST service for the web mail module. Messages are fetched and
operated on directly through the Gmail API using OAuth2, and every message is
automatically tagged with an ML-predicted category (`campus`, `career`,
`finance` or `other`).

## Layout

```text
agent/mail_agent/
├── mail_agent/                    # FastAPI service package
│   ├── main.py                    # REST routes (/api/mail/**)
│   ├── agent.py                   # LangChain agent + 6 tools
│   ├── gmail_service.py           # Gmail API operations -> MailMessage
│   ├── models.py                  # Pydantic models + MailCategory enum
│   ├── classifier.py              # ML classifier wrapper (lazy load + cache)
│   └── config.py                  # OAuth client / token path / env config
├── ml/                            # Trained email classifier
│   ├── email_classifier.py
│   └── models/email_classifier.joblib
├── tests/
├── Dockerfile
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

## One-time Gmail authorization

1. The Google OAuth web client is preconfigured in `mail_agent/config.py`
   (override via `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, `GMAIL_REDIRECT_URI`
   env vars). `http://localhost:5000/callback` **must** be listed as an
   Authorized redirect URI in the Google Cloud Console.
2. Start the service (port 5000).
3. As any authenticated CampusLink user, call:

   ```bash
   curl -H "Authorization: Bearer <jwt>" http://localhost:5000/api/mail/oauth/url
   ```

   ...or click **Connect Gmail** in the web Mail page. Open the returned
   `auth_url`, grant Gmail access. Google redirects to
   `http://localhost:5000/callback`, which exchanges the code and persists a
   refresh token to `token.json`.
4. The mailbox is now served from `/api/mail/messages`.

To re-authorize or switch accounts: delete `token.json` (or call
`POST /api/mail/oauth/disconnect`) and repeat.

## Email classification

Every message returned by the service is automatically tagged with one of four
categories predicted by the trained model in `agent/mail_agent/ml`:

| Category | Meaning                                    |
|----------|--------------------------------------------|
| `campus` | University/campus life, courses, events     |
| `career` | Jobs, internships, career fairs             |
| `finance`| Payments, invoices, financial matters       |
| `other`  | Anything else (personal, promo, social, …)  |

The tag is exposed as the `category` field on each `MailMessage` and rendered
as a colored chip in the web Mail page (list and detail view); the MCP mail
gateway also shows it in chat summaries.

Classification is best-effort:

* the model is loaded lazily once per process and predictions are cached per
  message id for a bounded window;
* if the model is missing or a single message cannot be classified, the
  service keeps working and that message falls back to `other`;
* the model path can be overridden with the `MAIL_CLASSIFIER_MODEL` env var
  (default: `ml/models/email_classifier.joblib`).

## Mail agent (LangChain)

The service exposes a natural-language mail agent built with LangChain
(`create_react_agent`). It runs an OpenAI-compatible chat model (DeepSeek by
default) and can call the following tools against the connected Gmail account:

| Tool            | Description                                    |
|-----------------|------------------------------------------------|
| `search_mail`   | Search / list messages                         |
| `read_mail`     | Read the full body (marks the message read)    |
| `delete_mail`   | Move a message to trash                        |
| `star_mail`     | Star / unstar a message                        |
| `archive_mail`  | Remove a message from the inbox                |
| `send_mail`     | Compose and send a new message                 |

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
