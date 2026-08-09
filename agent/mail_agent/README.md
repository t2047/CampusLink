# CampusLink Mail Service

Minimal FastAPI backend for the web mail module.

```bash
cd agent/mail_agent
python -m venv .venv
.venv\Scripts\pip install -e .
.venv\Scripts\uvicorn mail_agent.main:app --host 0.0.0.0 --port 8091 --reload
```

The service exposes `/api/mail/**` and expects the same `Authorization: Bearer ...`
header shape used by the CampusLink web app. This minimal version keeps messages
in memory and seeds each user with demo mail.
