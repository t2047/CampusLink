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

All `/api/mail/**` endpoints require `Authorization: Bearer <jwt>`. Listing is
paginated: `page` (0-based) and `size` (default 20, max 50); the response is a
`PageResponse` with `content`, `total_elements`, `total_pages`, `first` and
`last`.

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
