"""CampusLink Mail Service - Gmail-backed.

Exposes ``/api/mail/**`` used by the web frontend (and the MCP mail gateway),
backed by the real Gmail API via OAuth2. The shapes match the previous in-memory
mock so existing clients keep working.

OAuth flow:
  * ``GET  /api/mail/oauth/url``     -> Google consent URL.
  * ``GET  /api/mail/oauth/status``  -> {connected, email}.
  * ``GET  /callback``               -> Google redirect target; exchanges the
                                       code for a token and redirects to the app.
"""

from __future__ import annotations

import math

from fastapi import FastAPI, Header, Query, Request, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse, RedirectResponse

from . import config, gmail_service
from .models import (
    MailFolder,
    MailMessage,
    OAuthStatusResponse,
    OAuthUrlResponse,
    PageResponse,
    SendMailRequest,
    UpdateMailRequest,
)


class MailApiError(Exception):
    """Domain error rendered as a flat ``{code, error, ...}`` JSON body."""

    def __init__(self, status_code: int, code: str, message: str, **extra) -> None:
        super().__init__(message)
        self.status_code = status_code
        self.code = code
        self.message = message
        self.extra = extra


def _unauthorized(message: str) -> MailApiError:
    return MailApiError(status.HTTP_401_UNAUTHORIZED, "UNAUTHORIZED", message)


def _not_connected() -> MailApiError:
    url, _state = gmail_service.authorization_url()
    return MailApiError(
        status.HTTP_409_CONFLICT,
        "GMAIL_NOT_CONNECTED",
        "Gmail is not connected. Authorize it first.",
        auth_url=url,
    )


def _map_gmail_error(exc: Exception) -> MailApiError:
    if isinstance(exc, gmail_service.GmailNotConnectedError):
        return _not_connected()
    upstream_status = getattr(exc, "status_code", None)
    reason = getattr(exc, "reason", None) or str(exc)
    if isinstance(upstream_status, int) and upstream_status:
        return MailApiError(upstream_status, "GMAIL_ERROR", reason)
    return MailApiError(
        status.HTTP_502_BAD_GATEWAY,
        "GMAIL_ERROR",
        f"Gmail request failed: {reason}",
    )


def _user_from_auth(authorization: str | None) -> str:
    """Validate the bearer shape (mirrors the previous mock contract)."""
    if not authorization:
        raise _unauthorized("Missing Authorization")
    if not authorization.lower().startswith("bearer "):
        raise _unauthorized("Invalid Authorization")
    return authorization[len("bearer "):].strip()


app = FastAPI(title="CampusLink Mail Service", version="0.2.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["http://localhost:5173", "http://localhost:8080", "http://localhost:5000"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.exception_handler(MailApiError)
async def _handle_mail_api_error(request: Request, exc: MailApiError) -> JSONResponse:
    body = {"code": exc.code, "error": exc.message}
    body.update(exc.extra)
    return JSONResponse(status_code=exc.status_code, content=body)


@app.get("/health")
async def health() -> dict[str, object]:
    return {
        "status": "ok",
        "service": "mail-agent",
        "gmail_connected": gmail_service.is_connected(),
    }


# ---------------------------------------------------------------------------
# OAuth2
# ---------------------------------------------------------------------------

@app.get("/api/mail/oauth/url", response_model=OAuthUrlResponse)
async def oauth_url(authorization: str | None = Header(default=None)) -> OAuthUrlResponse:
    _user_from_auth(authorization)
    url, _state = gmail_service.authorization_url()
    return OAuthUrlResponse(auth_url=url, connected=gmail_service.is_connected())


@app.get("/api/mail/oauth/status", response_model=OAuthStatusResponse)
async def oauth_status(authorization: str | None = Header(default=None)) -> OAuthStatusResponse:
    _user_from_auth(authorization)
    return OAuthStatusResponse(
        connected=gmail_service.is_connected(),
        email=gmail_service.connected_email(),
    )


@app.get("/callback")
async def oauth_callback(code: str | None = None, state: str | None = None, error: str | None = None):
    if error:
        raise MailApiError(status.HTTP_400_BAD_REQUEST, "OAUTH_ERROR", error)
    if not code or not state:
        raise MailApiError(
            status.HTTP_400_BAD_REQUEST,
            "OAUTH_ERROR",
            "Missing code or state",
        )
    try:
        gmail_service.exchange_code(code, state)
    except ValueError as exc:
        raise MailApiError(status.HTTP_400_BAD_REQUEST, "OAUTH_ERROR", str(exc)) from exc
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc
    return RedirectResponse(url=f"{config.FRONTEND_URL}/mail?connected=1")


@app.post("/api/mail/oauth/disconnect", response_model=OAuthStatusResponse)
async def oauth_disconnect(authorization: str | None = Header(default=None)) -> OAuthStatusResponse:
    _user_from_auth(authorization)
    gmail_service.reset_connection()
    return OAuthStatusResponse(connected=False, email=None)


# ---------------------------------------------------------------------------
# Mail operations
# ---------------------------------------------------------------------------

@app.get("/api/mail/messages", response_model=PageResponse)
def list_messages(
    authorization: str | None = Header(default=None),
    folder: MailFolder = MailFolder.inbox,
    q: str = "",
    unread: bool | None = None,
    starred: bool | None = None,
    page: int = Query(default=0, ge=0),
    size: int = Query(default=20, ge=1, le=gmail_service.MAX_PAGE_SIZE),
) -> PageResponse:
    _user_from_auth(authorization)
    try:
        messages, total, has_next = gmail_service.list_messages(
            folder, q, unread, starred, page, size
        )
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc
    total_pages = max(1, math.ceil(total / size)) if total else 0
    return PageResponse(
        content=messages,
        page=page,
        size=size,
        total_elements=total,
        total_pages=total_pages,
        first=page == 0,
        last=not has_next or page >= total_pages - 1,
    )


@app.get("/api/mail/messages/{message_id}", response_model=MailMessage)
def get_message(
    message_id: str,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    _user_from_auth(authorization)
    try:
        return gmail_service.get_message(message_id, mark_read=True)
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc


@app.post(
    "/api/mail/messages",
    response_model=MailMessage,
    status_code=status.HTTP_201_CREATED,
)
def send_message(
    request: SendMailRequest,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    _user_from_auth(authorization)
    try:
        return gmail_service.send_message(request)
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc


@app.patch("/api/mail/messages/{message_id}", response_model=MailMessage)
def update_message(
    message_id: str,
    request: UpdateMailRequest,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    _user_from_auth(authorization)
    try:
        return gmail_service.update_message(
            message_id, request.read, request.starred, request.folder
        )
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc


@app.post("/api/mail/messages/{message_id}/archive", response_model=MailMessage)
def archive_message(
    message_id: str,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    _user_from_auth(authorization)
    try:
        return gmail_service.archive_message(message_id)
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc


@app.post("/api/mail/messages/{message_id}/delete", response_model=MailMessage)
def delete_message(
    message_id: str,
    authorization: str | None = Header(default=None),
) -> MailMessage:
    _user_from_auth(authorization)
    try:
        return gmail_service.trash_message(message_id)
    except Exception as exc:  # noqa: BLE001
        raise _map_gmail_error(exc) from exc
