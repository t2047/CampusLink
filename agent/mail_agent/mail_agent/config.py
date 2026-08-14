"""Gmail OAuth + runtime configuration for the CampusLink mail service.

The mail service talks to Gmail directly via the Gmail API (v1) using a web
OAuth2 flow. Credentials are read from environment variables, defaulting to the
project's Google Cloud web client so the service works out of the box.

A single shared Gmail account is authorised once (one-time ``/callback``
exchange); the resulting refresh token is persisted to ``token.json`` and reused
by every mail operation.
"""

from __future__ import annotations

import os
from pathlib import Path

try:  # 加载仓库根目录 .env（本地运行 uvicorn 时无需手动 export）
    from dotenv import find_dotenv, load_dotenv

    load_dotenv(find_dotenv())
except ImportError:  # pragma: no cover - 依赖缺失时退回环境变量
    pass

# Resolves to <repo>/agent/mail_agent
_PACKAGE_DIR = Path(__file__).resolve().parent
SERVICE_DIR = _PACKAGE_DIR.parent

# ---- Google OAuth web client -------------------------------------------------
# Defaults come from the project's Google Cloud credentials; override via env in
# production. ``redirect_uris`` must exactly match an Authorized redirect URI in
# the Google Cloud Console (here: http://localhost:5000/callback, i.e. the mail
# service runs on port 5000).
GMAIL_CLIENT_ID = os.environ.get(
    "GMAIL_CLIENT_ID",
    "263896994066-obkionr7ma7decc6ic2ovonlgokfhdqg.apps.googleusercontent.com",
)
GMAIL_CLIENT_SECRET = os.environ.get(
    "GMAIL_CLIENT_SECRET",
    "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",
)
GMAIL_PROJECT_ID = os.environ.get("GMAIL_PROJECT_ID", "river-lantern-436006-s4")
GMAIL_REDIRECT_URI = os.environ.get(
    "GMAIL_REDIRECT_URI",
    "http://localhost:5000/callback",
)

# gmail.modify = read, send, modify labels, trash (everything we need, without
# bypassing the trash bin).
GMAIL_SCOPES = [
    "https://www.googleapis.com/auth/gmail.modify",
]

# ---- Token persistence -------------------------------------------------------
TOKEN_PATH = Path(
    os.environ.get("GMAIL_TOKEN_PATH", str(SERVICE_DIR / "token.json"))
)

# Frontend origin to bounce back to after the OAuth callback completes.
FRONTEND_URL = os.environ.get("MAIL_FRONTEND_URL", "http://localhost:5173")

# ---- Mail Agent LLM (LangChain) ----------------------------------------------
# 显式配置 MAIL_LLM_*（写入仓库根 .env）；未配置时回退到 DEEPSEEK_*，保证
# Chat Core / Facility Agent 已有的 key 直接可用。
MAIL_LLM_API_KEY = (
    os.environ.get("MAIL_LLM_API_KEY", "").strip()
    or os.environ.get("DEEPSEEK_API_KEY", "").strip()
)
MAIL_LLM_BASE_URL = (
    os.environ.get("MAIL_LLM_BASE_URL", "").strip()
    or os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").strip()
)
MAIL_LLM_MODEL = (
    os.environ.get("MAIL_LLM_MODEL", "").strip()
    or os.environ.get("DEEPSEEK_MODEL", "deepseek-v4-flash").strip()
)
MAIL_AGENT_MAX_TOKENS = int(os.environ.get("MAIL_AGENT_MAX_TOKENS", "2000"))


def client_config() -> dict:
    """Build the Google ``web`` client config dict consumed by the OAuth flow."""
    return {
        "web": {
            "client_id": GMAIL_CLIENT_ID,
            "project_id": GMAIL_PROJECT_ID,
            "auth_uri": "https://accounts.google.com/o/oauth2/auth",
            "token_uri": "https://oauth2.googleapis.com/token",
            "auth_provider_x509_cert_url": "https://www.googleapis.com/oauth2/v1/certs",
            "client_secret": GMAIL_CLIENT_SECRET,
            "redirect_uris": [GMAIL_REDIRECT_URI],
        }
    }
