"""Gmail-backed mail operations for the CampusLink mail service.

This module owns:
  * the web OAuth2 flow (authorize URL + ``/callback`` token exchange),
  * **per-user** refresh-token persistence (one file per user under
    ``GMAIL_TOKEN_DIR``, keyed by the verified user identity),
  * Gmail API operations mapped onto the service's ``MailMessage`` model,
    always executed with the *requesting user's own* credentials.

Every public operation takes ``user_id`` as its first argument. The identity
comes from ``auth.resolve_identity`` (user JWT ``sub`` for the web path, or the
``sub`` of an internal MCP-gateway token for the chat path).
"""

from __future__ import annotations

import base64
import functools
import hashlib
import re
import secrets
import threading
import time
from datetime import datetime, timedelta, timezone
from difflib import SequenceMatcher
from typing import Any, Callable, TypeVar

from google.oauth2.credentials import Credentials
from google.auth.transport.requests import Request
from google_auth_oauthlib.flow import Flow
from googleapiclient.discovery import build
from googleapiclient.errors import HttpError

from . import config
from . import classifier
from .models import MailFolder, MailMessage, SendMailRequest, preview_of

# ---------------------------------------------------------------------------
# Thread safety
# ---------------------------------------------------------------------------
# Each user owns one Gmail client (httplib2 transport). A single client is NOT
# thread-safe: the LangChain agent executes its tools in a thread pool (parallel
# tool calls), so concurrent Gmail API requests through one client crashed the
# process with a native "Windows fatal exception: access violation" inside
# ssl/httplib2. Serialize every public Gmail operation with a *per-user*
# re-entrant lock (RLock: nested calls such as update_message -> get_message
# stay safe); different users run concurrently on their own clients.

_F = TypeVar("_F", bound=Callable[..., Any])

_module_lock = threading.Lock()
_user_locks: dict[str, threading.RLock] = {}


def _lock_for(user_id: str) -> threading.RLock:
    """Return the re-entrant lock guarding one user's Gmail client."""
    with _module_lock:
        lock = _user_locks.get(user_id)
        if lock is None:
            lock = threading.RLock()
            _user_locks[user_id] = lock
        return lock


def _serialized(func: _F) -> _F:
    """Run ``func`` while holding the calling user's Gmail API lock.

    The first positional argument must be the ``user_id``.
    """

    @functools.wraps(func)
    def wrapper(*args: Any, **kwargs: Any) -> Any:
        if not args:
            raise TypeError("serialized Gmail function needs user_id as first argument")
        with _lock_for(args[0]):
            return func(*args, **kwargs)

    return wrapper  # type: ignore[return-value]


# Gmail label ids we care about.
_LABEL_INBOX = "INBOX"
_LABEL_UNREAD = "UNREAD"
_LABEL_STARRED = "STARRED"
_LABEL_SENT = "SENT"
_LABEL_TRASH = "TRASH"
_LABEL_SPAM = "SPAM"

MAX_PAGE_SIZE = 50  # "最多 50 条" - the service only ever exposes the latest 50.

# 模糊搜索：用 Gmail OR 预筛候选 + 本地打分排序，避免把整个邮箱拉下来。
_FUZZY_MAX_CANDIDATES = 200  # 预筛阶段最多取多少封候选邮件
_FUZZY_MIN_SCORE = 0.30      # 低于该分数的结果不返回

# Gmail list pageTokens are opaque cursors tied to one query. Cache them per
# (user, query) so consecutive pagination is a single list call instead of
# re-walking from page 0. ``tokens[i]`` holds the nextPageToken returned by
# page ``i``.
_PAGE_TOKEN_CACHE: dict[tuple[Any, ...], list[str | None]] = {}
_PAGE_TOKEN_CACHE_MAX_KEYS = 200

# Full-message body cache: opening a message twice in a short window should not
# re-download the full payload. Mutations drop the affected entry. Keyed by
# (user_id, message_id).
_MESSAGE_CACHE_TTL_SECONDS = 60
_MESSAGE_CACHE: dict[tuple[str, str], tuple[float, MailMessage]] = {}


def _cache_get(user_id: str, message_id: str) -> MailMessage | None:
    entry = _MESSAGE_CACHE.get((user_id, message_id))
    if entry is None:
        return None
    cached_at, message = entry
    if time.monotonic() - cached_at > _MESSAGE_CACHE_TTL_SECONDS:
        _MESSAGE_CACHE.pop((user_id, message_id), None)
        return None
    return message


def _cache_put(user_id: str, message_id: str, message: MailMessage) -> None:
    _MESSAGE_CACHE[(user_id, message_id)] = (time.monotonic(), message)


def _cache_drop(user_id: str, message_id: str) -> None:
    _MESSAGE_CACHE.pop((user_id, message_id), None)


class GmailNotConnectedError(RuntimeError):
    """Raised when the requesting user has not authorised a Gmail account yet."""

    def __init__(self, message: str, user_id: str | None = None) -> None:
        super().__init__(message)
        self.user_id = user_id


# Pending OAuth states (CSRF protection) - in-memory is fine for a one-shot
# flow. Maps state -> (user_id, redirect_uri) so the token exchange reuses the
# exact URI the consent URL was built with and stores the token under the user
# who started the flow (the browser callback carries no JWT).
_pending_states: dict[str, tuple[str, str]] = {}


_service_instances: dict[str, Any] = {}


def _invalidate_service(user_id: str) -> None:
    """Drop the cached Gmail client for one user (e.g. after token re-auth)."""
    _service_instances.pop(user_id, None)


def _service(user_id: str):
    """Return a cached authenticated Gmail v1 client for ``user_id``.

    The client owns an authorized HTTP transport whose connections (incl. the
    TLS handshake) are reused across requests; rebuilding it on every call is
    what made each request pay ~0.7s of TLS setup again.
    """
    creds = load_credentials(user_id)
    if creds is None:
        raise GmailNotConnectedError("Gmail account is not connected", user_id)
    instance = _service_instances.get(user_id)
    if instance is None:
        instance = build(
            "gmail", "v1", credentials=creds, cache_discovery=False
        )
        _service_instances[user_id] = instance
    return instance


# ---------------------------------------------------------------------------
# OAuth2 web flow (per user)
# ---------------------------------------------------------------------------

def _effective_redirect_uri(redirect_uri: str | None = None) -> str:
    """Resolve the redirect URI to use, falling back to the dev default."""
    return (
        redirect_uri
        or config.GMAIL_REDIRECT_URI
        or config.DEFAULT_GMAIL_REDIRECT_URI
    )


def _build_flow(
    state: str | None = None, redirect_uri: str | None = None
) -> Flow:
    return Flow.from_client_config(
        config.client_config(),
        scopes=config.GMAIL_SCOPES,
        redirect_uri=_effective_redirect_uri(redirect_uri),
        state=state,
    )


@_serialized
def authorization_url(user_id: str, redirect_uri: str | None = None) -> tuple[str, str]:
    """Return ``(url, state)`` for the Google consent screen for ``user_id``."""
    flow = _build_flow(redirect_uri=redirect_uri)
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
    _pending_states[state] = (user_id, _effective_redirect_uri(redirect_uri))
    return url, state


def exchange_code(code: str, state: str) -> Credentials:
    """Exchange an authorization code for credentials and persist them for the
    user who started the flow (recovered from ``state``)."""
    pending = _pending_states.pop(state, None)
    if pending is None:
        raise ValueError("Invalid or expired OAuth state")
    user_id, redirect_uri = pending
    with _lock_for(user_id):
        flow = _build_flow(state=state, redirect_uri=redirect_uri)
        flow.fetch_token(code=code)
        creds = flow.credentials
        save_credentials(user_id, creds)
        return creds


@_serialized
def save_credentials(user_id: str, creds: Credentials) -> None:
    _invalidate_service(user_id)
    path = _token_path(user_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(creds.to_json(), encoding="utf-8")


def _token_path(user_id: str) -> Any:
    """Per-user token file: ``<GMAIL_TOKEN_DIR>/<sha256(user_id)>.json``."""
    digest = hashlib.sha256(user_id.encode("utf-8")).hexdigest()
    return config.GMAIL_TOKEN_DIR / f"{digest}.json"


@_serialized
def load_credentials(user_id: str) -> Credentials | None:
    path = _token_path(user_id)
    if not path.exists():
        return None
    creds = Credentials.from_authorized_user_file(
        str(path), config.GMAIL_SCOPES
    )
    if not creds or not creds.valid:
        if creds and creds.expired and creds.refresh_token:
            creds.refresh(Request())
            save_credentials(user_id, creds)
            return creds
        return None
    return creds


@_serialized
def is_connected(user_id: str) -> bool:
    try:
        return load_credentials(user_id) is not None
    except Exception:
        return False


@_serialized
def connected_email(user_id: str) -> str | None:
    try:
        creds = load_credentials(user_id)
    except Exception:
        return None
    if creds is None:
        return None
    try:
        # Credentials do not carry the address; resolve it from the Gmail
        # profile via the cached client so the TLS connection is reused.
        profile = _service(user_id).users().getProfile(userId="me").execute()
        return profile.get("emailAddress")
    except Exception:
        return None


def connected_user_count() -> int:
    """Number of users who have stored a Gmail token (for the health check)."""
    token_dir = config.GMAIL_TOKEN_DIR
    if not token_dir.exists():
        return 0
    return len(list(token_dir.glob("*.json")))


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
    if _LABEL_SPAM in labels:
        return MailFolder.spam
    return MailFolder.archived


def _iso(internal_date: str | None) -> datetime:
    if not internal_date:
        return datetime.now(timezone.utc)
    return datetime.fromtimestamp(int(internal_date) / 1000, tz=timezone.utc)


def _to_message(
    msg: dict[str, Any],
    include_body: bool,
    category: str | None = None,
) -> MailMessage:
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
        category=(
            category
            if category is not None
            else classifier.classify(
                str(msg["id"]),
                subject=subject,
                body=body,
                sender=sender,
            )
        ),
        read=_LABEL_UNREAD not in label_ids,
        starred=_LABEL_STARRED in label_ids,
        created_at=_iso(msg.get("internalDate")),
        updated_at=_iso(msg.get("internalDate")),
    )


def _classify_record(msg: dict[str, Any]) -> dict[str, Any]:
    """Build a classifier input record from a raw Gmail message dict.

    Uses the same header/body extraction as ``_to_message`` on the metadata
    path (body = snippet) so batch classification and the per-message fallback
    agree on the inputs.
    """
    payload = msg.get("payload") or {}
    headers = payload.get("headers") or []
    return {
        "message_id": str(msg["id"]),
        "subject": _header(headers, "Subject") or "(no subject)",
        "body": msg.get("snippet", ""),
        "sender": _header(headers, "From"),
    }


def _stamp_categories(messages: list[MailMessage]) -> None:
    """Batch-classify ``messages`` (LLM first, ML fallback) and stamp them.

    One LLM round trip for the whole batch; anything the LLM does not answer
    falls back to the ML model inside :func:`classifier.classify_many`.
    """
    if not messages:
        return
    categories = classifier.classify_many(
        {
            "message_id": message.id,
            "subject": message.subject,
            "body": message.body,
            "sender": message.sender,
        }
        for message in messages
    )
    for message in messages:
        message.category = categories.get(
            message.id, classifier.FALLBACK_CATEGORY
        )


def _build_query(
    folder: MailFolder,
    q: str,
    unread: bool | None,
    starred: bool | None,
    after: str | None = None,
    before: str | None = None,
) -> str:
    parts: list[str] = []
    if q:
        parts.append(q)
    if after:
        parts.append(f"after:{after}")
    if before:
        parts.append(f"before:{before}")
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


def _normalize_date_arg(value: str | None) -> str | None:
    """Normalize an ISO date (``2026-08-01`` or ``2026/08/01``) to Gmail syntax.

    Note: Gmail's ``after:``/``before:`` operators match on the message *header
    Date* (the send time), not the received time the UI shows. Date-range
    filtering therefore uses ``_date_arg_to_utc_bound`` + local filtering on
    ``internalDate`` instead; this helper is kept only for legacy callers.
    """
    if not value or not value.strip():
        return None
    text = value.strip().replace("-", "/")
    match = re.fullmatch(r"(\d{4})/(\d{1,2})/(\d{1,2})", text)
    if not match:
        raise ValueError(f"Invalid date: {value!r} (expected YYYY-MM-DD)")
    year, month, day = (int(part) for part in match.groups())
    return f"{year:04d}/{month:02d}/{day:02d}"


def _date_arg_to_utc_bound(
    value: str | None, end_of_day: bool = False
) -> datetime | None:
    """Parse ``YYYY-MM-DD`` as a local calendar day and return the UTC instant.

    ``after`` maps to the start of the day (00:00 local), ``before`` to the
    start of the following day, so the range covers the whole local day —
    matching what the UI displays (browser-local receive time).
    """
    if not value or not value.strip():
        return None
    text = value.strip().replace("-", "/")
    match = re.fullmatch(r"(\d{4})/(\d{1,2})/(\d{1,2})", text)
    if not match:
        raise ValueError(f"Invalid date: {value!r} (expected YYYY-MM-DD)")
    year, month, day = (int(part) for part in match.groups())
    hour, minute = (23, 59) if end_of_day else (0, 0)
    # Naive local datetime -> aware local -> UTC, so comparisons against
    # ``created_at`` (UTC internalDate) are correct.
    return datetime(year, month, day, hour, minute).astimezone(timezone.utc)


# Upper bound on candidates pulled for a local date-range filter. Gmail lists
# newest-first, so pulling this many recent messages covers a week+ of mail.
_DATE_FILTER_MAX_CANDIDATES = 500

# Calendar extraction pre-filter: Gmail's ``after:``/``before:`` match the
# header send date, so widen the bound by this buffer and still filter exactly
# on ``internalDate`` locally. Newest-first list order guarantees every
# in-window message is among the first candidates.
_DATE_PREFILTER_BUFFER_DAYS = 7
# Calendar extraction never needs more than a handful of candidates (it only
# keeps ``max_results``), so cap the walk instead of scanning 500 messages.
_DATE_PREFILTER_MIN_CANDIDATES = 50


def _folder_label_ids(folder: MailFolder) -> list[str] | None:
    if folder == MailFolder.inbox:
        return [_LABEL_INBOX]
    if folder == MailFolder.sent:
        return [_LABEL_SENT]
    if folder == MailFolder.trash:
        return [_LABEL_TRASH]
    if folder == MailFolder.spam:
        return [_LABEL_SPAM]
    return None  # archived uses a search query


# ---------------------------------------------------------------------------
# Operations (all take user_id first)
# ---------------------------------------------------------------------------

def _fetch_metadata(
    service: Any, ids: list[str], classify: bool = True
) -> list[MailMessage]:
    """Fetch message metadata for ``ids`` via Gmail's API-specific batch endpoint.

    Gmail API v1 has no ``users.messages.batchGet``, so the metadata fetches are
    batched over a single multipart HTTP request per chunk. Gmail also caps
    concurrent requests per user, so each batch is kept modest (10 requests).

    When ``classify`` is true (default) the whole page is classified in one
    batch call (LLM first, ML fallback); when false the messages come back
    tagged ``other`` as a placeholder and the caller is responsible for
    stamping categories with :func:`_stamp_categories` on the messages it
    actually returns (large candidate walks should not pay per-message LLM
    calls for messages that never reach the page).
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
    raw_messages = [
        fetched[message_id] for message_id in ids if message_id in fetched
    ]
    categories: dict[str, str] = {}
    if classify:
        categories = classifier.classify_many(
            _classify_record(msg) for msg in raw_messages
        )
    return [
        _to_message(
            msg,
            include_body=False,
            category=categories.get(
                str(msg["id"]), classifier.FALLBACK_CATEGORY
            ),
        )
        for msg in raw_messages
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


def _fetch_recent_ids(
    service: Any,
    query: str,
    label_ids: list[str] | None,
    max_results: int = _DATE_FILTER_MAX_CANDIDATES,
) -> list[str]:
    """Walk pages newest-first and collect up to ``max_results`` message ids.

    Gmail returns list results newest-first, so this covers the most recent
    ``max_results`` matching messages (used for the local date-range filter).
    """
    ids: list[str] = []
    page_token: str | None = None
    while len(ids) < max_results:
        listed = _list_page(
            service,
            min(50, max_results - len(ids)),
            query,
            label_ids,
            page_token,
        )
        ids.extend(ref["id"] for ref in (listed.get("messages") or []))
        page_token = listed.get("nextPageToken")
        if not page_token:
            break
    return ids[:max_results]


# ---------------------------------------------------------------------------
# Fuzzy search (本地模糊匹配)
# ---------------------------------------------------------------------------


def _split_tokens(text: str) -> list[str]:
    """按空白分词；中文等无空格语言整体保留（交给子串/相似度匹配）。"""
    return [token for token in text.lower().split() if token]


def _fuzzy_score(query: str, subject: str, sender: str, preview: str) -> float:
    """0..1 的模糊匹配分，综合子串命中、词元覆盖率和编辑距离相似度。

    * 完整子串命中（大小写不敏感）-> 1.0
    * 查询每个空格分词都在字段中出现 -> 1.0
    * 单词查询没命中时，用与字段词元的编辑距离相似度容忍拼写错误（exma -> exam）
    """
    q = query.strip().lower()
    if not q:
        return 1.0
    fields = [subject.lower(), sender.lower(), preview.lower()]
    q_tokens = _split_tokens(q)
    best = 0.0
    for field in fields:
        if not field:
            continue
        if q in field:
            best = 1.0
            continue
        if q_tokens:
            hits = sum(1 for token in q_tokens if len(token) >= 2 and token in field)
            score = hits / len(q_tokens)
        else:
            score = 0.0
        if len(q_tokens) == 1:
            field_tokens = _split_tokens(field)
            token_ratio = max(
                (SequenceMatcher(None, q, field_token).ratio()
                 for field_token in field_tokens if len(field_token) >= 3),
                default=0.0,
            )
            # 编辑距离分支只用于拼写纠错（exma -> exam），要求足够相似，
            # 避免 "exam" 和 "career" 这种偶发 0.4 相似度造成误匹配。
            if token_ratio >= 0.6:
                score = max(score, token_ratio)
        best = max(best, score)
    return round(best, 4)


def _fuzzy_candidate_ids(
    service: Any,
    folder: MailFolder,
    q: str,
    unread: bool | None,
    starred: bool | None,
    label_ids: list[str] | None,
) -> list[str]:
    """先用 Gmail 的 OR 语法预筛候选（命中任一词元即可），避免全量拉取。

    预筛为空（例如中文整句或特殊字符导致 Gmail 检索不到）时，兜底拉取最近
    ``_FUZZY_MAX_CANDIDATES`` 封邮件交给本地模糊打分。
    """
    tokens = _split_tokens(q)
    candidates: list[str] = []
    if tokens:
        or_query = " OR ".join(tokens)
        page_token: str | None = None
        try:
            while len(candidates) < _FUZZY_MAX_CANDIDATES:
                listed = _list_page(
                    service,
                    min(50, _FUZZY_MAX_CANDIDATES - len(candidates)),
                    _build_query(folder, or_query, unread, starred),
                    label_ids,
                    page_token,
                )
                candidates.extend(ref["id"] for ref in (listed.get("messages") or []))
                page_token = listed.get("nextPageToken")
                if not page_token:
                    break
        except HttpError:
            candidates = []  # OR 语法失败（特殊字符等），走兜底
    if not candidates:
        page_token = None
        while len(candidates) < _FUZZY_MAX_CANDIDATES:
            listed = _list_page(
                service,
                min(50, _FUZZY_MAX_CANDIDATES - len(candidates)),
                _build_query(folder, "", unread, starred),
                label_ids,
                page_token,
            )
            candidates.extend(ref["id"] for ref in (listed.get("messages") or []))
            page_token = listed.get("nextPageToken")
            if not page_token:
                break
    return candidates


def _fuzzy_search(
    service: Any,
    folder: MailFolder,
    q: str,
    unread: bool | None,
    starred: bool | None,
    label_ids: list[str] | None,
    page: int,
    size: int,
) -> tuple[list[MailMessage], int, bool]:
    """本地模糊检索：拉候选 -> 打分 -> 过滤/排序 -> 分页。"""
    candidate_ids = _fuzzy_candidate_ids(
        service, folder, q, unread, starred, label_ids
    )
    # classify=False: the candidate walk can pull far more messages than the
    # returned page; classify only the page that is actually returned.
    messages = _fetch_metadata(service, candidate_ids, classify=False)
    ranked: list[tuple[float, MailMessage]] = []
    for message in messages:
        score = _fuzzy_score(q, message.subject, message.sender, message.preview)
        if score >= _FUZZY_MIN_SCORE:
            ranked.append((score, message))
    ranked.sort(
        key=lambda item: (-item[0], -item[1].created_at.timestamp()),
    )
    total = len(ranked)
    start = page * size
    page_messages = [message for _score, message in ranked[start : start + size]]
    has_next = start + size < total
    _stamp_categories(page_messages)
    return page_messages, total, has_next


@_serialized
def list_recent_messages(
    user_id: str,
    days: int = 0,
    max_results: int = 20,
) -> list[MailMessage]:
    """Fetch full messages *received* within the last ``days`` days.

    ``days=0`` means only today's mail; ``days=2`` means today and the two
    previous days (a window of ``days + 1`` calendar days). Used by the calendar
    schedule extraction. Returns full bodies so callers can parse schedules.

    The window is applied locally on the received date (internalDate), because
    Gmail's ``after:``/``before:`` match the header send date and would miss
    forwarded messages; the Gmail query only pre-filters with a widened
    ``after:`` bound so the candidate walk stays small.
    """
    if days < 0:
        days = 0
    service = _service(user_id)
    today = datetime.now().astimezone()
    start_local = today - timedelta(days=days)
    after_utc = datetime(
        start_local.year, start_local.month, start_local.day
    ).astimezone(timezone.utc)
    before_utc = datetime(
        today.year, today.month, today.day
    ).astimezone(timezone.utc) + timedelta(days=1)
    # Pre-filter with Gmail's ``after:`` (header send date) widened by a buffer,
    # so "today only" stops walking the whole mailbox; the exact window is still
    # applied locally on internalDate below.
    prefetch_after = after_utc - timedelta(
        days=max(_DATE_PREFILTER_BUFFER_DAYS, days)
    )
    ids = _fetch_recent_ids(
        service,
        f"after:{int(prefetch_after.timestamp())}",
        [_LABEL_INBOX],
        max(max_results * 3, _DATE_PREFILTER_MIN_CANDIDATES),
    )
    if not ids:
        return []
    # classify=False: only the messages inside the window get classified
    # (below), not every candidate walked by the pre-filter.
    fetched = _fetch_metadata(service, ids, classify=False)
    in_window = [
        message
        for message in fetched
        if after_utc <= message.created_at < before_utc
    ]
    in_window.sort(key=lambda message: message.created_at, reverse=True)
    in_window = in_window[:max_results]
    if not in_window:
        return []
    _stamp_categories(in_window)
    # Metadata-only messages carry the snippet as body; upgrade them to full
    # bodies so schedule parsing sees the real content.
    full: list[MailMessage] = []
    for message in in_window:
        try:
            full.append(get_message(user_id, message.id, mark_read=False))
        except Exception:  # noqa: BLE001 - keep going on per-message failures
            full.append(message)
    return full


@_serialized
def list_messages(
    user_id: str,
    folder: MailFolder,
    q: str = "",
    unread: bool | None = None,
    starred: bool | None = None,
    page: int = 0,
    size: int = MAX_PAGE_SIZE,
    after: str | None = None,
    before: str | None = None,
) -> tuple[list[MailMessage], int, bool]:
    """Fetch one page of matching messages for ``user_id`` (capped at MAX_PAGE_SIZE per page).

    Gmail paginates with opaque ``pageToken`` cursors; page tokens are cached
    per query so consecutive pages cost one list call each. Metadata is
    batch-fetched only for the requested page. Returns
    ``(messages, total_estimate, has_next)``; ``total_estimate`` comes from
    Gmail's ``resultSizeEstimate`` and is not an exact count.

    ``after`` / ``before`` filter by *received* date (ISO ``YYYY-MM-DD``, or
    ``YYYY/MM/DD``); ``after`` is inclusive, ``before`` exclusive. The filter is
    applied locally on ``internalDate`` (the receive time the UI shows) rather
    than Gmail's ``after:``/``before:`` operators, which match the header
    *send* date — forwarded mail would otherwise be invisible to date queries.
    """
    size = max(1, min(size, MAX_PAGE_SIZE))
    page = max(0, page)
    service = _service(user_id)
    after_utc = _date_arg_to_utc_bound(after)
    before_utc = _date_arg_to_utc_bound(before, end_of_day=True)
    if after_utc or before_utc:
        return _list_by_received_date(
            service, folder, q, unread, starred, page, size, after_utc, before_utc
        )
    # 模糊匹配：普通自然语言词走 OR 预筛 + 本地打分；带 Gmail 语法（from:/subject: 等）
    # 的查询仍按精确语法透传，保留高级检索能力。
    if q.strip() and ":" not in q:
        label_ids = _folder_label_ids(folder)
        return _fuzzy_search(
            service, folder, q, unread, starred, label_ids, page, size
        )
    query = _build_query(folder, q, unread, starred)
    label_ids = _folder_label_ids(folder)
    key = (user_id, folder.value, query, unread, starred, size)
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


def _list_by_received_date(
    service: Any,
    folder: MailFolder,
    q: str,
    unread: bool | None,
    starred: bool | None,
    page: int,
    size: int,
    after_utc: datetime | None,
    before_utc: datetime | None,
) -> tuple[list[MailMessage], int, bool]:
    """Filter by the *received* date (internalDate) locally.

    Gmail's ``after:``/``before:`` operators match the header send date, so a
    forwarded message (sent days earlier, received today) would be missed. Here
    we pull the newest candidates, keep only those whose ``created_at``
    (internalDate) lands in ``[after_utc, before_utc)``, then paginate locally.
    """
    query = _build_query(folder, q, unread, starred)
    label_ids = _folder_label_ids(folder)
    ids = _fetch_recent_ids(service, query, label_ids, _DATE_FILTER_MAX_CANDIDATES)
    if not ids:
        return [], 0, False
    # classify=False: the candidate walk can cover far more messages than the
    # returned page; classify only the page that is actually returned.
    messages = _fetch_metadata(service, ids, classify=False)
    filtered = [
        message
        for message in messages
        if (after_utc is None or message.created_at >= after_utc)
        and (before_utc is None or message.created_at < before_utc)
    ]
    # Newest first (matches Gmail list order and the web UI).
    filtered.sort(key=lambda message: message.created_at, reverse=True)
    total = len(filtered)
    start = page * size
    page_messages = filtered[start : start + size]
    has_next = start + size < total
    _stamp_categories(page_messages)
    return page_messages, total, has_next


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


@_serialized
def get_message(user_id: str, message_id: str, mark_read: bool = True) -> MailMessage:
    service = _service(user_id)
    cached = _cache_get(user_id, message_id)
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
        _cache_put(user_id, message_id, message)
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
    _cache_put(user_id, message_id, message)
    return message


@_serialized
def send_message(user_id: str, request: SendMailRequest) -> MailMessage:
    import email.message

    service = _service(user_id)
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
    return get_message(user_id, str(sent["id"]), mark_read=False)


@_serialized
def update_message(
    user_id: str,
    message_id: str,
    read: bool | None = None,
    starred: bool | None = None,
    folder: MailFolder | None = None,
) -> MailMessage:
    service = _service(user_id)
    if folder == MailFolder.trash:
        service.users().messages().trash(userId="me", id=message_id).execute()
        _cache_drop(user_id, message_id)
        return get_message(user_id, message_id, mark_read=False)
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
    _cache_drop(user_id, message_id)
    return get_message(user_id, message_id, mark_read=False)


@_serialized
def archive_message(user_id: str, message_id: str) -> MailMessage:
    return update_message(user_id, message_id, folder=MailFolder.archived)


@_serialized
def trash_message(user_id: str, message_id: str) -> MailMessage:
    service = _service(user_id)
    service.users().messages().trash(userId="me", id=message_id).execute()
    _cache_drop(user_id, message_id)
    return get_message(user_id, message_id, mark_read=False)


@_serialized
def trash_messages(user_id: str, message_ids: list[str]) -> int:
    """Move many messages to trash in batched requests; returns the count done."""
    if not message_ids:
        return 0
    service = _service(user_id)
    done = 0
    for start in range(0, len(message_ids), 10):
        batch = service.new_batch_http_request()
        chunk = message_ids[start : start + 10]
        for message_id in chunk:
            batch.add(
                service.users().messages().trash(userId="me", id=message_id)
            )
        batch.execute()
        done += len(chunk)
        for message_id in chunk:
            _cache_drop(user_id, message_id)
    return done


@_serialized
def reset_connection(user_id: str) -> None:
    """Remove one user's persisted Gmail token (e.g. to re-authorize)."""
    _invalidate_service(user_id)
    for key in [k for k in _MESSAGE_CACHE if k[0] == user_id]:
        _MESSAGE_CACHE.pop(key, None)
    for key in [k for k in _PAGE_TOKEN_CACHE if k and k[0] == user_id]:
        _PAGE_TOKEN_CACHE.pop(key, None)
    try:
        _token_path(user_id).unlink()
    except FileNotFoundError:
        pass


def new_state(user_id: str, redirect_uri: str | None = None) -> str:
    """Generate and remember a fresh CSRF state token bound to ``user_id``."""
    state = secrets.token_urlsafe(16)
    _pending_states[state] = (user_id, _effective_redirect_uri(redirect_uri))
    return state
