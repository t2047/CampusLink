# CampusLink Mail Service

Gmail-backed REST service for the web mail module. Messages are fetched and
operated on directly through the Gmail API using OAuth2 (the latest 20 messages
are exposed per request).

## Run

```bash
cd agent/mail_agent
python -m venv .venv
.venv\Scripts\pip install -e .
.venv\Scripts\uvicorn mail_agent.main:app --host 0.0.0.0 --port 5000 --reload
```

The service runs on **port 5000** because the OAuth redirect URI registered in
Google Cloud Console is `http://localhost:5000/callback`. The Vite dev server
proxies `/api/mail` here, and the MCP mail gateway (`agent/mcp_servers/mail_server.py`)
calls this service via `MAIL_REST_URL` (default `http://127.0.0.1:5000`).

## One-time Gmail authorization

1. The Google OAuth web client is preconfigured in `mail_agent/config.py` (override
   via `GMAIL_CLIENT_ID`, `GMAIL_CLIENT_SECRET`, `GMAIL_REDIRECT_URI` env vars).
   `http://localhost:5000/callback` **must** be listed as an Authorized redirect URI
   in the Google Cloud Console.
2. Start the service (port 5000).
3. As any authenticated CampusLink user, call:

   ```bash
   curl -H "Authorization: Bearer <jwt>" http://localhost:5000/api/mail/oauth/url
   ```

   ...or click **Connect Gmail** in the web Mail page. Open the returned `auth_url`,
   grant Gmail access. Google redirects to `http://localhost:5000/callback`, which
   exchanges the code and persists a refresh token to `token.json`.
4. The mailbox's latest 20 messages are now served from `/api/mail/messages`.

To re-authorize or switch accounts: delete `token.json` (or call
`POST /api/mail/oauth/disconnect`) and repeat.

## Email classification

Every message returned by the service is automatically tagged with one of four
categories predicted by the trained model in `agent/mail_agent/ml`
(`campus`, `career`, `finance` or `other`); the tag is exposed as the
`category` field on each `MailMessage` and rendered as a chip in the web Mail
page. Classification is best-effort: if the model is missing or a single
message cannot be classified, the service keeps working and the message falls
back to `other`. The model path can be overridden with the
`MAIL_CLASSIFIER_MODEL` env var (default: `ml/models/email_classifier.joblib`).

## API

| Method | Path                              | Description                                   |
|--------|-----------------------------------|-----------------------------------------------|
| GET    | `/api/mail/oauth/url`             | Google consent URL                            |
| GET    | `/api/mail/oauth/status`         | `{connected, email}`                          |
| GET    | `/callback`                       | OAuth redirect target (code -> token)         |
| POST   | `/api/mail/oauth/disconnect`     | Forget the stored Gmail token                 |
| GET    | `/api/mail/messages`             | Latest 20 (filter `folder`, `q`, `unread`)   |
| GET    | `/api/mail/messages/{id}`        | Full message (marks read)                     |
| POST   | `/api/mail/messages`             | Send mail                                     |
| PATCH  | `/api/mail/messages/{id}`        | `read` / `starred` / `folder`                 |
| POST   | `/api/mail/messages/{id}/archive`| Remove from inbox                             |
| POST   | `/api/mail/messages/{id}/delete` | Move to trash                                 |

All `/api/mail/**` endpoints require `Authorization: Bearer <jwt>`.

### Folder mapping (Gmail labels)

| Frontend folder | Gmail                                                   |
|-----------------|---------------------------------------------------------|
| `inbox`         | `INBOX` label                                           |
| `sent`          | `SENT` label                                            |
| `trash`         | `TRASH` label                                           |
| `archived`      | not in `inbox`/`sent`/`trash`/`draft`/`spam`            |

`read` <-> `UNREAD` label, `starred` <-> `STARRED` label. Scopes requested:
`https://www.googleapis.com/auth/gmail.modify`.
