"""LangChain mail agent -- 7 tools over the per-user Gmail service.

The agent wraps the existing ``gmail_service`` operations in LangChain tools
and runs them through a ReAct-style agent (``create_react_agent``). The model
is OpenAI-compatible (DeepSeek by default) and configured from the repository
root ``.env`` (``MAIL_LLM_*``, falling back to ``DEEPSEEK_*``).

Tools are **bound to the requesting user**: ``make_tools(user_id)`` returns a
fresh tool set that calls ``gmail_service`` with that user's id, and the agent
cache is keyed by user so each user's conversation operates on their own
mailbox (the same per-user Gmail binding as the REST endpoints).

Tools:
  * ``search_mail``  -- search/list messages
  * ``read_mail``    -- fetch the full body of one message (marks it read)
  * ``delete_mail``  -- move a message to trash
  * ``delete_mail_batch`` -- move ALL matching messages to trash
  * ``star_mail``    -- star / unstar a message
  * ``archive_mail`` -- remove a message from the inbox
  * ``send_mail``    -- compose and send a new message

Every mutation tool accepts either an explicit ``message_id`` (as returned by
``search_mail``) or a natural-language ``query`` that resolves to the first
matching message.
"""

from __future__ import annotations

import logging
import re
import time
from datetime import datetime
from typing import Any, Optional

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.tools import tool
from langchain_openai import ChatOpenAI
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.prebuilt import create_react_agent

from . import config, gmail_service
from .models import MailFolder, SendMailRequest

logger = logging.getLogger(__name__)

MAX_LIST_SIZE = gmail_service.MAX_PAGE_SIZE

# Idempotency guard for deletions: LLMs sometimes re-issue a tool call for an
# action that already succeeded (especially delete), so remember recently
# trashed message ids and skip duplicates within a short window. Keyed by
# (user_id, message_id) so one user's deletions never suppress another's.
_TRASHED_TTL_SECONDS = 300  # 5 minutes
_recently_trashed: dict[tuple[str, str], float] = {}


def _is_recently_trashed(user_id: str, message_id: str) -> bool:
    now = time.monotonic()
    trashed_at = _recently_trashed.get((user_id, message_id))
    if trashed_at is None:
        return False
    if now - trashed_at > _TRASHED_TTL_SECONDS:
        _recently_trashed.pop((user_id, message_id), None)
        return False
    return True


def _mark_trashed(user_id: str, message_id: str) -> None:
    _recently_trashed[(user_id, message_id)] = time.monotonic()
    if len(_recently_trashed) > 500:
        now = time.monotonic()
        for stale in [
            key for key, at in _recently_trashed.items()
            if now - at > _TRASHED_TTL_SECONDS
        ]:
            _recently_trashed.pop(stale, None)


SYSTEM_PROMPT = """You are the CampusLink Mail Assistant. You help users manage their
campus Gmail account by calling the tools you have been given.

Current date (server local time): {today}

When the user mentions relative dates ("今天", "昨天", "上周", "这个月", "today",
"yesterday", "last week", "this month", "3天前"), resolve them against the current
date above and pass concrete ISO dates (YYYY-MM-DD) to the after/before parameters.
Never guess a date when you are not sure — prefer asking the user.

Available tools:
1. search_mail(query, folder, unread, starred, after, before, max_results) - find messages. Returns
   message ids, subjects, senders and previews. ALWAYS call this first when the user
   does not provide a concrete message id. Supports date filters:
   - after / before: ISO dates (YYYY-MM-DD) bounding the received date; after is
     inclusive, before is exclusive. Use them when the user asks for mail from a
     period ("上周的邮件", "8月以来的邮件", "the emails from last week", "before March").
     Convert relative periods to concrete dates relative to today.
2. read_mail(message_id, query, folder) - read the full body of a message. Reading
   automatically marks it as read.
3. delete_mail(message_id, query, folder) - move a single email to trash.
4. delete_mail_batch(query, folder, unread, starred, after, before, max_results) - move ALL
   matching emails to trash in ONE call. ALWAYS use this for bulk deletion ("删掉所有垃圾邮件",
   "delete all spam", "删除这个发件人的所有邮件", "删除上周的所有邮件"), never loop delete_mail.
   To delete everything except today, use before=<today's date>.
5. star_mail(message_id, query, folder, starred) - star or unstar a message.
6. archive_mail(message_id, query, folder) - remove a message from the inbox.
7. send_mail(recipients, subject, body) - send a new email.

Rules:
- You operate on the user's real mailbox. Only perform an action the user asked for.
- For delete/send, first confirm the exact target (subject + sender for delete, the
  full recipients/subject/body for send) with the user before calling the tool.
- NEVER call delete_mail or delete_mail_batch twice for the same email. Once a delete
  tool reports success for a message id, do not call it again for that id — move on and
  summarize. If a delete tool reports "already deleted", do NOT retry it.
- When the user refers to an email by description ("the exam email", "邮件关于考试"),
  first call search_mail to find its message id, then pass that id to the next tool.
- Reply in the same language the user wrote in (中文/English). Keep answers concise;
  for search results list subject, sender and date. For full emails include the body.
- Never invent message ids, recipients or send results. If a tool reports an error or
  finds nothing, tell the user clearly.
"""


def _safe_folder(folder: str) -> MailFolder:
    """Validate a folder string; default to inbox on anything unknown."""
    try:
        return MailFolder(folder.strip().lower())
    except ValueError:
        return MailFolder.inbox


def _resolve_message_id(
    user_id: str,
    message_id: str,
    query: str,
    folder: str = "inbox",
) -> Optional[str]:
    """Return an explicit id or locate the first message matching ``query``."""
    if message_id.strip():
        return message_id.strip()
    if not query.strip():
        return None
    try:
        messages, _total, _has_next = gmail_service.list_messages(
            user_id, _safe_folder(folder), q=query.strip(), page=0, size=5
        )
    except Exception as exc:  # noqa: BLE001 - surfaced to the model as text
        logger.warning("resolve message failed: %s", exc)
        return None
    return messages[0].id if messages else None


def _fmt_message(message: Any) -> str:
    flags = []
    if not message.read:
        flags.append("unread")
    if message.starred:
        flags.append("starred")
    flag_text = f" ({', '.join(flags)})" if flags else ""
    # created_at is UTC; render it in the server's local timezone (with the
    # time of day) so it matches what the web UI shows in the browser timezone.
    created = message.created_at.astimezone().strftime("%Y-%m-%d %H:%M") if message.created_at else "?"
    return (
        f"- id={message.id}{flag_text} | {message.subject} | from {message.sender} | "
        f"{created} | {message.preview[:120]}"
    )


def make_tools(user_id: str) -> list[Any]:
    """Build the seven mail tools bound to ``user_id``.

    Each tool closure calls ``gmail_service`` with ``user_id`` so the agent
    always operates on the requesting user's own Gmail account.
    """
    uid = user_id

    @tool
    def search_mail(
        query: str = "",
        folder: str = "inbox",
        unread: bool | None = None,
        starred: bool | None = None,
        after: str = "",
        before: str = "",
        max_results: int = 10,
    ) -> str:
        """Search the mailbox and return a list of matching messages.

        Args:
            query: Gmail search terms (subject, sender, words, etc.). Empty means all
                messages in the folder.
            folder: inbox | sent | archived | trash.
            unread: filter to unread (True) or read (False) messages.
            starred: filter to starred (True) or unstarred (False) messages.
            after: only messages received on or after this date (ISO YYYY-MM-DD,
                e.g. "2026-08-01"). Inclusive of that day.
            before: only messages received before this date (ISO YYYY-MM-DD,
                exclusive of that day).
            max_results: how many messages to return (max 50).
        """
        size = max(1, min(int(max_results), MAX_LIST_SIZE))
        try:
            messages, total, _has_next = gmail_service.list_messages(
                uid,
                _safe_folder(folder),
                q=query,
                unread=unread,
                starred=starred,
                after=after or None,
                before=before or None,
                page=0,
                size=size,
            )
        except Exception as exc:  # noqa: BLE001
            return f"Search failed: {exc}"
        if not messages:
            return "No matching messages found."
        lines = "\n".join(_fmt_message(message) for message in messages)
        return f"Found {total} message(s):\n{lines}"

    @tool
    def read_mail(message_id: str = "", query: str = "", folder: str = "inbox") -> str:
        """Read the full content of one email.

        Args:
            message_id: the id returned by search_mail (preferred).
            query: when no message_id is given, locate the first message matching this
                description.
            folder: inbox | sent | archived | trash.
        Reading marks the message as read.
        """
        target = _resolve_message_id(uid, message_id, query, folder)
        if not target:
            return "Could not find the email to read."
        try:
            message = gmail_service.get_message(uid, target)
        except Exception as exc:  # noqa: BLE001
            return f"Failed to read email: {exc}"
        body = message.body_html or message.body
        # created_at is UTC; convert to local time so the date shown to the user
        # matches the web UI (browser-local formatting).
        local_date = message.created_at.astimezone() if message.created_at else None
        date_line = f"{local_date:%Y-%m-%d %H:%M}" if local_date else "?"
        return (
            f"Subject: {message.subject}\n"
            f"From: {message.sender}\n"
            f"To: {', '.join(message.recipients)}\n"
            f"Date: {date_line}\n\n"
            f"{body}"
        )

    @tool
    def delete_mail(message_id: str = "", query: str = "", folder: str = "inbox") -> str:
        """Move an email to trash.

        Args:
            message_id: the id returned by search_mail (preferred).
            query: when no message_id is given, locate the first message matching this
                description.
            folder: inbox | sent | archived | trash | spam.
        """
        target = _resolve_message_id(uid, message_id, query, folder)
        if not target:
            return "Could not find the email to delete."
        if _is_recently_trashed(uid, target):
            return "That email was already deleted moments ago (moved to trash); skipping duplicate deletion."
        try:
            deleted = gmail_service.trash_message(uid, target)
        except Exception as exc:  # noqa: BLE001
            return f"Failed to delete email: {exc}"
        _mark_trashed(uid, target)
        return f"Deleted email '{deleted.subject}' (moved to trash)."

    @tool
    def delete_mail_batch(
        query: str = "",
        folder: str = "inbox",
        unread: bool | None = None,
        starred: bool | None = None,
        after: str = "",
        before: str = "",
        max_results: int = 50,
    ) -> str:
        """Move ALL matching emails to trash in one call.

        Use this when the user wants to delete many emails at once (e.g. all spam,
        everything from a sender, or everything before a date) instead of calling
        delete_mail repeatedly.

        Args:
            query: Gmail search terms (subject, sender, words). Empty means all
                messages in the folder.
            folder: inbox | sent | archived | trash | spam.
            unread: filter to unread (True) or read (False) messages.
            starred: filter to starred (True) or unstarred (False) messages.
            after: only messages received on or after this date (ISO YYYY-MM-DD,
                inclusive of that day).
            before: only messages received before this date (ISO YYYY-MM-DD,
                exclusive of that day).
            max_results: maximum number of messages to delete (max 200).
        """
        size = max(1, min(int(max_results), 200))
        try:
            messages, _total, _has_next = gmail_service.list_messages(
                uid,
                _safe_folder(folder),
                q=query,
                unread=unread,
                starred=starred,
                after=after or None,
                before=before or None,
                page=0,
                size=size,
            )
        except Exception as exc:  # noqa: BLE001
            return f"Search failed: {exc}"
        if not messages:
            return "No matching emails found to delete."
        ids = [message.id for message in messages]
        fresh_ids = [
            message_id for message_id in ids
            if not _is_recently_trashed(uid, message_id)
        ]
        skipped = len(ids) - len(fresh_ids)
        if not fresh_ids:
            return "All matching emails were already deleted moments ago; nothing to do."
        try:
            done = gmail_service.trash_messages(uid, fresh_ids)
        except Exception as exc:  # noqa: BLE001
            return f"Failed to delete emails: {exc}"
        for message_id in fresh_ids:
            _mark_trashed(uid, message_id)
        suffix = f" ({skipped} already deleted skipped)" if skipped else ""
        return f"Moved {done} email(s) to trash ({', '.join(message.subject for message in messages[:3])}...){suffix}"

    @tool
    def star_mail(
        message_id: str = "",
        query: str = "",
        folder: str = "inbox",
        starred: bool = True,
    ) -> str:
        """Star or unstar an email.

        Args:
            message_id: the id returned by search_mail (preferred).
            query: when no message_id is given, locate the first message matching this
                description.
            folder: inbox | sent | archived | trash.
            starred: True to star, False to unstar.
        """
        target = _resolve_message_id(uid, message_id, query, folder)
        if not target:
            return "Could not find the email to star."
        try:
            updated = gmail_service.update_message(uid, target, starred=starred)
        except Exception as exc:  # noqa: BLE001
            return f"Failed to update email: {exc}"
        label = "starred" if starred else "unstarred"
        return f"Starred status of '{updated.subject}': {label}."

    @tool
    def archive_mail(message_id: str = "", query: str = "", folder: str = "inbox") -> str:
        """Remove an email from the inbox (archive it).

        Args:
            message_id: the id returned by search_mail (preferred).
            query: when no message_id is given, locate the first message matching this
                description.
            folder: inbox | sent | archived | trash.
        """
        target = _resolve_message_id(uid, message_id, query, folder)
        if not target:
            return "Could not find the email to archive."
        try:
            archived = gmail_service.archive_message(uid, target)
        except Exception as exc:  # noqa: BLE001
            return f"Failed to archive email: {exc}"
        return f"Archived email '{archived.subject}'."

    @tool
    def send_mail(recipients: list[str], subject: str, body: str) -> str:
        """Send a new email.

        Args:
            recipients: list of recipient email addresses.
            subject: email subject.
            body: email body text.
        """
        try:
            request = SendMailRequest(recipients=recipients, subject=subject, body=body)
        except Exception as exc:  # noqa: BLE001 - validation error -> ask the user
            return f"Invalid email request: {exc}"
        try:
            sent = gmail_service.send_message(uid, request)
        except Exception as exc:  # noqa: BLE001
            return f"Failed to send email: {exc}"
        return (
            f"Sent email '{sent.subject}' to {', '.join(sent.recipients)} "
            f"(message id: {sent.id})."
        )

    return [search_mail, read_mail, delete_mail, delete_mail_batch, star_mail, archive_mail, send_mail]


def _thread_id(session_id: str) -> str:
    """Sanitize a client-supplied session id into a safe LangGraph thread id."""
    if not session_id:
        return ""
    return re.sub(r"[^A-Za-z0-9_-]", "", session_id)[:128]


def _llm() -> ChatOpenAI:
    return ChatOpenAI(
        model=config.MAIL_LLM_MODEL,
        api_key=config.MAIL_LLM_API_KEY,
        base_url=config.MAIL_LLM_BASE_URL,
        temperature=0,
        max_tokens=config.MAIL_AGENT_MAX_TOKENS,
        timeout=30,
        streaming=True,
    )


_agent_cache: dict[tuple[str, str, str, str], Any] = {}


def is_configured() -> bool:
    return bool(config.MAIL_LLM_API_KEY)


def _today_stamp() -> str:
    """Local date for the prompt, e.g. ``2026-08-15 (Saturday)``."""
    return datetime.now().astimezone().strftime("%Y-%m-%d (%A)")


def build_agent(user_id: str) -> Any:
    """Build (and cache) the LangChain agent for ``user_id``.

    The system prompt embeds the current date so the model can resolve relative
    dates ("昨天", "last week") into concrete after/before values. The cache is
    keyed by (model, base url, date, user) so a long-running process picks up a
    new date at midnight and each user gets tools bound to their own mailbox.

    Raises:
        RuntimeError: when no LLM API key is configured in the environment.
    """
    if not is_configured():
        raise RuntimeError(
            "Mail agent is not configured: set MAIL_LLM_API_KEY (or DEEPSEEK_API_KEY) "
            "in the repository .env file."
        )
    today = _today_stamp()
    key = (config.MAIL_LLM_MODEL, config.MAIL_LLM_BASE_URL, today, user_id)
    if key not in _agent_cache:
        logger.info("building mail agent: model=%s base=%s date=%s user=%s", *key)
        _agent_cache[key] = create_react_agent(
            _llm(),
            make_tools(user_id),
            prompt=SystemMessage(content=SYSTEM_PROMPT.format(today=today)),
            checkpointer=InMemorySaver(),
        )
    return _agent_cache[key]


def _actions_from_messages(messages: list[Any]) -> list[dict[str, Any]]:
    """Extract the tool calls the model made, for the response trace."""
    actions: list[dict[str, Any]] = []
    for message in messages:
        tool_calls = getattr(message, "tool_calls", None) or []
        for call in tool_calls:
            actions.append(
                {
                    "tool": call.get("name", ""),
                    "args": call.get("args", {}),
                }
            )
    return actions


async def run_chat(message: str, session_id: str, user_id: str) -> dict[str, Any]:
    """Run one chat turn against the mail agent for ``user_id``.

    The agent and its tools are bound to ``user_id`` so every Gmail operation
    uses that user's own credentials. The session id is reused as the LangGraph
    thread id so multi-turn follow-ups ("再找一封", "那封考试邮件") keep their
    context (thread ids are already user-scoped by the caller).
    """
    agent = build_agent(user_id)
    thread = _thread_id(session_id)
    if not thread:
        import uuid

        thread = f"anon-{uuid.uuid4().hex}"
    thread_config = {"configurable": {"thread_id": thread}}
    result = await agent.ainvoke(
        {"messages": [HumanMessage(content=message)]},
        config=thread_config,
    )
    messages = result.get("messages", [])
    last = messages[-1] if messages else None
    content = getattr(last, "content", "") or ""
    if isinstance(content, list):  # multi-part content -> join text parts
        content = " ".join(
            str(part.get("text", ""))
            for part in content
            if isinstance(part, dict) and part.get("type") == "text"
        ).strip()
    return {
        "response": str(content),
        "session_id": thread,
        "actions_taken": _actions_from_messages(messages),
        "model": config.MAIL_LLM_MODEL,
    }
