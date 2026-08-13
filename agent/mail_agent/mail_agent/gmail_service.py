"""Gmail-backed mail operations for the CampusLink mail service.

This module owns:
  * the web OAuth2 flow (authorize URL + ``/callback`` token exchange),
  * refresh-token persistence (``token.json``),
  * Gmail API operations mapped onto the service's ``MailMessage`` model.

A single shared Gmail account is authorised once; every mail operation reuses
the persisted (auto-refreshing) credentials.
"""

from __future__ import annotations

import base64
import re
import secrets
import time
from datetime import datetime, timezone
from typing import Any

from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request
from google_auth_oauthlib.flow import Flow
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from . import config
from . import classifier
from .models import MailFolder, MailMessage, SendMailRequest, preview_of

# Gmail label ids we care about.
_LABEL_INBOX = "INBOX"
_LABEL_UNREAD = "UNREAD"
_LABEL_STARRED = "STARRED"
_LABEL_SENT = "SENT"
_LABEL_TRASH = "TRASH"

MAX_PAGE_SIZE = 50  # "最多 50 条" - the service only ever exposes the latest 50.

# Gmail list pageTokens are opaque cursors tied to one query. Cache them per
# query so consecutive pagination is a single list call instead of re-walking
# from page 0. ``tokens[i]`` holds the nextPageToken returned by page ``i``.
_PAGE_TOKEN_CACHE: dict[tuple[Any, ...], list[str | None]] = {}
_PAGE_TOKEN_CACHE_MAX_KEYS = 50

# Full-message body cache: opening a message twice in a short window should not
# re-download the full payload. Mutations drop the affected entry.
_MESSAGE_CACHE_TTL_SECONDS = 60
_MESSAGE_CACHE: dict[str, tuple[float, MailMessage]] = {}


def _cache_get(message_id: str) -> MailMessage | None:
    entry = _MESSAGE_CACHE.get(message_id)
    if entry is None:
        return None
    cached_at, message = entry
    if time.monotonic() - cached_at > _MESSAGE_CACHE_TTL_SECONDS:
        _MESSAGE_CACHE.pop(message_id, None)
        return None
    return message


def _cache_put(message_id: str, message: MailMessage) -> None:
    _MESSAGE_CACHE[message_id] = (time.monotonic(), message)


def _cache_drop(message_id: str) -> None:
    _MESSAGE_CACHE.pop(message_id, None)


class GmailNotConnectedError(RuntimeError):
    """Raised when no Gmail account has been authorised yet."""


# Pending OAuth states (CSRF protection) - in-memory is fine for a one-time flow.
_pending_states: set[str] = set()


_service_instance: Any | None = None


def _invalidate_service() -> None:
    """Drop the cached Gmail client (e.g. after token re-auth/reset)."""
    global _service_instance
    _service_instance = None


def _service():
    """Return a cached authenticated Gmail v1 client.

    The client owns an authorized HTTP transport whose connections (incl. the
    TLS handshake) are reused across requests; rebuilding it on every call is
    what made each request pay ~0.7s of TLS setup again.
    """
    global _service_instance
    creds = load_credentials()
    if creds is None:
        raise GmailNotConnectedError("Gmail account is not connected")
    if _service_instance is None:
        _service_instance = build(
            "gmail", "v1", credentials=creds, cache_discovery=False
        )
    return _service_instance


# ---------------------------------------------------------------------------
# OAuth2 web flow
# ---------------------------------------------------------------------------

def _build_flow(state: str | None = None) -> Flow:
    return Flow.from_client_config(
        config.client_config(),
        scopes=config.GMAIL_SCOPES,
        redirect_uri=config.GMAIL_REDIRECT_URI,
        state=state,
    )


def authorization_url() -> tuple[str, str]:
    """Return ``(url, state)`` for the Google consent screen."""
    flow = _build_flow()
    url, state = flow.authorization_url(
        access_type="offline",
        # Force consent so a refresh token is always granted on re-auth.
        prompt="consent",
        # Disable PKCE: the callback is handled by a different Flow instance
        # than the one that generated the URL, so a code_verifier could not be
        # shared. This is a confidential web client (has a secret), so PKCE is
        # not required.
        code_challenge=None,
        code_challenge_method=None,
    )
    _pending_states.add(state)
    return url, state


def exchange_code(code: str, state: str) -> Credentials:
    """Exchange an authorization code for credentials and persist them."""
    if state not in _pending_states:
        raise ValueError("Invalid or expired OAuth state")
    _pending_states.discard(state)
    flow = _build_flow(state=state)
    flow.fetch_token(code=code)
    creds = flow.credentials
    save_credentials(creds)
    return creds


def save_credentials(creds: Credentials) -> None:
    _invalidate_service()
    config.TOKEN_PATH.write_text(creds.to_json(), encoding="utf-8")


def load_credentials() -> Credentials | None:
    if not config.TOKEN_PATH.exists():
        return None
    creds = Credentials.from_authorized_user_file(
        str(config.TOKEN_PATH), config.GMAIL_SCOPES
    )
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
            save_credentials(creds)
            return creds
        return None
    return creds


def is_connected() -> bool:
    try:
        return load_credentials() is not None
    except Exception:
        return False


def connected_email() -> str | None:
    try:
        creds = load_credentials()
    except Exception:
        return None
    if creds is None:
        return None
    try:
        # Credentials do not carry the address; resolve it from the Gmail
        # profile via the cached client so the TLS connection is reused.
        profile = _service().users().getProfile(userId="me").execute()
        return profile.get("emailAddress")
    except Exception:
        return None


# ---------------------------------------------------------------------------
# Gmail message -> MailMessage mapping
# ---------------------------------------------------------------------------

def _b64url_decode(data: str) -> bytes:
    return base64.urlsafe_b64decode(data + "=" * (-len(data) % 4))


def _walk_parts(payload: dict[str, Any]):
    stack = [payload]
    while stack:
        node = stack.pop()
        if node.get("parts"):
            stack.extend(node["parts"])
        yield node


def _header(headers: list[dict[str, str]], name: str) -> str:
    lowered = name.lower()
    for header in headers:
        if header.get("name", "").lower() == lowered:
            return header.get("value", "")
    return ""


def _extract_body(payload: dict[str, Any]) -> tuple[str, str | None]:
    """Return ``(plain_text, html)`` extracted from a message payload.

    ``html`` is the original HTML part (when present) so the frontend can render
    links/images; ``plain_text`` is the text/plain part or a tag-stripped
    fallback derived from the HTML.
    """
    plain: str | None = None
    html: str | None = None
    for node in _walk_parts(payload):
        data = (node.get("body") or {}).get("data")
        if not data:
            continue
        mime = node.get("mimeType", "")
        if mime == "text/plain" and plain is None:
            plain = _b64url_decode(data).decode("utf-8", "replace")
        elif mime == "text/html" and html is None:
            html = _b64url_decode(data).decode("utf-8", "replace")
    if plain:
        return plain, html
    if html:
        text = re.sub(
            r"<style[^>]*>.*?</style>", " ", html, flags=re.S | re.I
        )
        text = re.sub(
            r"<script[^>]*>.*?</script>", " ", text, flags=re.S | re.I
        )
        text = re.sub(r"<[^>]+>", " ", text)
        text = re.sub(r"\s+", " ", text).strip()
        return text, html
    return "", html


def _folder_from_labels(label_ids: list[str]) -> MailFolder:
    labels = set(label_ids or [])
    if _LABEL_TRASH in labels:
        return MailFolder.trash
    if _LABEL_SENT in labels:
        return MailFolder.sent
    if _LABEL_INBOX in labels:
        return MailFolder.inbox
    return MailFolder.archived


def _iso(internal_date: str | None) -> datetime:
    if not internal_date:
        return datetime.now(timezone.utc)
    return datetime.fromtimestamp(int(internal_date) / 1000, tz=timezone.utc)


def _to_message(msg: dict[str, Any], include_body: bool) -> MailMessage:
    payload = msg.get("payload") or {}
    headers = payload.get("headers") or []
    label_ids = msg.get("labelIds") or []
    snippet = msg.get("snippet", "")
    body_html: str | None = None
    if include_body:
        body, body_html = _extract_body(payload)
    else:
        body = snippet
    to_header = _header(headers, "To")
    subject = _header(headers, "Subject") or "(no subject)"
    sender = _header(headers, "From")
    return MailMessage(
        id=str(msg["id"]),
        subject=subject,
        sender=sender,
        recipients=[r.strip() for r in to_header.split(",") if r.strip()],
        preview=snippet[:140] if snippet else preview_of(body),
        body=body,
        body_html=body_html,
        folder=_folder_from_labels(label_ids),
        category=classifier.classify(
            str(msg["id"]),
            subject=subject,
            body=body,
            sender=sender,
        ),
        read=_LABEL_UNREAD not in label_ids,
        starred=_LABEL_STARRED in label_ids,
        created_at=_iso(msg.get("internalDate")),
        updated_at=_iso(msg.get("internalDate")),
    )


def _build_query(
    folder: MailFolder,
    q: str,
    unread: bool | None,
    starred: bool | None,
) -> str:
    parts: list[str] = []
    if q:
        parts.append(q)
    if unread is True:
        parts.append("is:unread")
    elif unread is False:
        parts.append("is:read")
    if starred is True:
        parts.append("is:starred")
    elif starred is False:
        parts.append("-is:starred")
    # "archived" has no dedicated label: everything not in inbox/sent/trash/etc.
    if folder == MailFolder.archived:
        parts.extend(["-in:inbox", "-in:sent", "-in:trash", "-in:draft", "-in:spam"])
    return " ".join(parts)


def _folder_label_ids(folder: MailFolder) -> list[str] | None:
    if folder == MailFolder.inbox:
        return [_LABEL_INBOX]
    if folder == MailFolder.sent:
        return [_LABEL_SENT]
    if folder == MailFolder.trash:
        return [_LABEL_TRASH]
    return None  # archived uses a search query


# ---------------------------------------------------------------------------
# Operations
# ---------------------------------------------------------------------------

def _fetch_metadata(
    service: Any, ids: list[str]
) -> list[MailMessage]:
    """Fetch message metadata for ``ids`` via Gmail's API-specific batch endpoint.

    Gmail API v1 has no ``users.messages.batchGet``, so the metadata fetches are
    batched over a single multipart HTTP request per chunk. Gmail also caps
    concurrent requests per user, so each batch is kept modest (10 requests).
    """
    if not ids:
        return []
    fetched: dict[str, dict[str, Any]] = {}
    failures: list[Exception] = []

    def _collect(
        request_id: str,
        response: dict[str, Any] | None,
        exception: Exception | None,
    ) -> None:
        if exception is not None:
            failures.append(exception)
        elif response is not None:
            fetched[request_id] = response

    for start in range(0, len(ids), 10):
        batch = service.new_batch_http_request(callback=_collect)
        for message_id in ids[start : start + 10]:
            batch.add(
                service.users()
                .messages()
                .get(
                    userId="me",
                    id=message_id,
                    format="metadata",
                    metadataHeaders=["Subject", "From", "To", "Date"],
                ),
                request_id=message_id,
            )
        batch.execute()
    if failures:
        raise failures[0]
    return [
        _to_message(fetched[message_id], include_body=False)
        for message_id in ids
        if message_id in fetched
    ]


def _cached_tokens(key: tuple[Any, ...]) -> list[str | None]:
    """Return the page-token cache for a query key, creating it if needed."""
    tokens = _PAGE_TOKEN_CACHE.get(key)
    if tokens is None:
        if len(_PAGE_TOKEN_CACHE) >= _PAGE_TOKEN_CACHE_MAX_KEYS:
            _PAGE_TOKEN_CACHE.pop(next(iter(_PAGE_TOKEN_CACHE)))
        tokens = []
        _PAGE_TOKEN_CACHE[key] = tokens
    return tokens


def _list_page(
    service: Any,
    size: int,
    query: str,
    label_ids: list[str] | None,
    token: str | None,
) -> dict[str, Any]:
    return (
        service.users()
        .messages()
        .list(
            userId="me",
            maxResults=size,
            q=query or None,
            labelIds=label_ids,
            pageToken=token,
        )
        .execute()
    )


def _fetch_page_ids(
    service: Any,
    key: tuple[Any, ...],
    page: int,
    size: int,
    query: str,
    label_ids: list[str] | None,
) -> tuple[list[str], int, bool]:
    """Return ``(ids, estimate, has_next)`` for one page, extending the cache."""
    tokens = _cached_tokens(key)
    # Build the cache up to the page before the requested one.
    while len(tokens) < page:
        prev = tokens[-1] if tokens else None
        if prev is None and tokens:
            return [], 0, False  # exhausted before reaching this page
        tokens.append(_list_page(service, size, query, label_ids, prev).get("nextPageToken"))
    token = tokens[page - 1] if page > 0 else None
    if page > 0 and token is None:
        return [], 0, False
    listed = _list_page(service, size, query, label_ids, token)
    ids = [ref["id"] for ref in (listed.get("messages") or [])]
    estimate = int(listed.get("resultSizeEstimate", 0))
    has_next = bool(listed.get("nextPageToken"))
    if len(tokens) == page:
        tokens.append(listed.get("nextPageToken"))
    return ids, estimate, has_next


def list_messages(
    folder: MailFolder,
    q: str = "",
    unread: bool | None = None,
    starred: bool | None = None,
    page: int = 0,
    size: int = MAX_PAGE_SIZE,
) -> tuple[list[MailMessage], int, bool]:
    """Fetch one page of matching messages (capped at MAX_PAGE_SIZE per page).

    Gmail paginates with opaque ``pageToken`` cursors; page tokens are cached
    per query so consecutive pages cost one list call each. Metadata is
    batch-fetched only for the requested page. Returns
    ``(messages, total_estimate, has_next)``; ``total_estimate`` comes from
    Gmail's ``resultSizeEstimate`` and is not an exact count.
    """
    size = max(1, min(size, MAX_PAGE_SIZE))
    page = max(0, page)
    service = _service()
    query = _build_query(folder, q, unread, starred)
    label_ids = _folder_label_ids(folder)
    key = (folder.value, query, unread, starred, size)
    try:
        ids, estimate, has_next = _fetch_page_ids(
            service, key, page, size, query, label_ids
        )
    except HttpError as exc:
        # A stale cached pageToken is rejected with 400; drop it and retry once.
        if exc.resp.status == 400 and _PAGE_TOKEN_CACHE.pop(key, None) is not None:
            ids, estimate, has_next = _fetch_page_ids(
                service, key, page, size, query, label_ids
            )
        else:
            raise

    messages = _fetch_metadata(service, ids)
    return messages, estimate, has_next


def _fetch_full_marked_read(service: Any, message_id: str) -> dict[str, Any]:
    """Mark ``message_id`` read and fetch it in a single batched round trip."""
    fetched: dict[str, dict[str, Any]] = {}
    failures: list[Exception] = []

    def _collect(
        request_id: str,
        response: dict[str, Any] | None,
        exception: Exception | None,
    ) -> None:
        if exception is not None:
            failures.append(exception)
        elif response is not None:
            fetched[request_id] = response

    batch = service.new_batch_http_request(callback=_collect)
    batch.add(
        service.users()
        .messages()
        .modify(
            userId="me",
            id=message_id,
            body={"removeLabelIds": [_LABEL_UNREAD]},
        ),
        request_id="modify",
    )
    batch.add(
        service.users()
        .messages()
        .get(userId="me", id=message_id, format="full"),
        request_id="get",
    )
    batch.execute()
    if failures:
        raise failures[0]
    return fetched["get"]


def get_message(message_id: str, mark_read: bool = True) -> MailMessage:
    service = _service()
    cached = _cache_get(message_id)
    if cached is not None and not mark_read:
        return cached
    if cached is not None:
        if cached.read:
            return cached  # already read, nothing to persist
        # Body is already cached; only persist the read flag.
        service.users().messages().modify(
            userId="me",
            id=message_id,
            body={"removeLabelIds": [_LABEL_UNREAD]},
        ).execute()
        message = cached.model_copy(update={"read": True})
        _cache_put(message_id, message)
        return message
    if mark_read:
        # One round trip instead of two: mark read + fetch in a single batch.
        message = _to_message(
            _fetch_full_marked_read(service, message_id), include_body=True
        ).model_copy(update={"read": True})
    else:
        msg = (
            service.users()
            .messages()
            .get(userId="me", id=message_id, format="full")
            .execute()
        )
        message = _to_message(msg, include_body=True)
    _cache_put(message_id, message)
    return message


def send_message(request: SendMailRequest) -> MailMessage:
    import email.message

    service = _service()
    profile = service.users().getProfile(userId="me").execute()
    sender = profile.get("emailAddress", "")
    message = email.message.EmailMessage()
    message["From"] = sender
    message["To"] = ", ".join(request.recipients)
    message["Subject"] = request.subject.strip()
    message.set_content(request.body.strip())
    raw = base64.urlsafe_b64encode(message.as_bytes()).decode()
    sent = (
        service.users()
        .messages()
        .send(userId="me", body={"raw": raw})
        .execute()
    )
    return get_message(str(sent["id"]), mark_read=False)


def update_message(
    message_id: str,
    read: bool | None = None,
    starred: bool | None = None,
    folder: MailFolder | None = None,
) -> MailMessage:
    service = _service()
    if folder == MailFolder.trash:
        service.users().messages().trash(userId="me", id=message_id).execute()
        _cache_drop(message_id)
        return get_message(message_id, mark_read=False)
    add_labels: list[str] = []
    remove_labels: list[str] = []
    if read is True:
        remove_labels.append(_LABEL_UNREAD)
    elif read is False:
        add_labels.append(_LABEL_UNREAD)
    if starred is True:
        add_labels.append(_LABEL_STARRED)
    elif starred is False:
        remove_labels.append(_LABEL_STARRED)
    if folder == MailFolder.archived:
        remove_labels.append(_LABEL_INBOX)
    elif folder == MailFolder.inbox:
        add_labels.append(_LABEL_INBOX)
    if add_labels or remove_labels:
        service.users().messages().modify(
            userId="me",
            id=message_id,
            body={
                "addLabelIds": add_labels,
                "removeLabelIds": remove_labels,
            },
        ).execute()
    _cache_drop(message_id)
    return get_message(message_id, mark_read=False)


def archive_message(message_id: str) -> MailMessage:
    return update_message(message_id, folder=MailFolder.archived)


def trash_message(message_id: str) -> MailMessage:
    service = _service()
    service.users().messages().trash(userId="me", id=message_id).execute()
    _cache_drop(message_id)
    return get_message(message_id, mark_read=False)


def reset_connection() -> None:
    """Remove the persisted Gmail token (e.g. to re-authorize)."""
    _MESSAGE_CACHE.clear()
    _PAGE_TOKEN_CACHE.clear()
    _invalidate_service()
    try:
        config.TOKEN_PATH.unlink()
    except FileNotFoundError:
        pass


def new_state() -> str:
    """Generate and remember a fresh CSRF state token."""
    state = secrets.token_urlsafe(16)
    _pending_states.add(state)
    return state
