"""LangChain mail agent -- 6 tools over the shared Gmail service.

The agent wraps the existing ``gmail_service`` operations in LangChain tools
and runs them through a ReAct-style agent (``create_react_agent``). The model
is OpenAI-compatible (DeepSeek by default) and configured from the repository
root ``.env`` (``MAIL_LLM_*``, falling back to ``DEEPSEEK_*``).

Tools:
  * ``search_mail``  -- search/list messages
  * ``read_mail``    -- fetch the full body of one message (marks it read)
  * ``delete_mail``  -- move a message to trash
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

SYSTEM_PROMPT = """You are the CampusLink Mail Assistant. You help users manage their
campus Gmail account by calling the tools you have been given.

Available tools:
1. search_mail(query, folder, unread, starred, max_results) - find messages. Returns
   message ids, subjects, senders and previews. ALWAYS call this first when the user
   does not provide a concrete message id.
2. read_mail(message_id, query, folder) - read the full body of a message. Reading
   automatically marks it as read.
3. delete_mail(message_id, query, folder) - move a message to trash.
4. star_mail(message_id, query, folder, starred) - star or unstar a message.
5. archive_mail(message_id, query, folder) - remove a message from the inbox.
6. send_mail(recipients, subject, body) - send a new email.

Rules:
- You operate on the user's real mailbox. Only perform an action the user asked for.
- For delete/send, first confirm the exact target (subject + sender for delete, the
  full recipients/subject/body for send) with the user before calling the tool.
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
            _safe_folder(folder), q=query.strip(), page=0, size=5
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
    created = message.created_at.strftime("%Y-%m-%d") if message.created_at else "?"
    return (
        f"- id={message.id}{flag_text} | {message.subject} | from {message.sender} | "
        f"{created} | {message.preview[:120]}"
    )


@tool
def search_mail(
    query: str = "",
    folder: str = "inbox",
    unread: bool | None = None,
    starred: bool | None = None,
    max_results: int = 10,
) -> str:
    """Search the mailbox and return a list of matching messages.

    Args:
        query: Gmail search terms (subject, sender, words, etc.). Empty means all
            messages in the folder.
        folder: inbox | sent | archived | trash.
        unread: filter to unread (True) or read (False) messages.
        starred: filter to starred (True) or unstarred (False) messages.
        max_results: how many messages to return (max 50).
    """
    size = max(1, min(int(max_results), MAX_LIST_SIZE))
    try:
        messages, total, _has_next = gmail_service.list_messages(
            _safe_folder(folder),
            q=query,
            unread=unread,
            starred=starred,
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
    target = _resolve_message_id(message_id, query, folder)
    if not target:
        return "Could not find the email to read."
    try:
        message = gmail_service.get_message(target)
    except Exception as exc:  # noqa: BLE001
        return f"Failed to read email: {exc}"
    body = message.body_html or message.body
    return (
        f"Subject: {message.subject}\n"
        f"From: {message.sender}\n"
        f"To: {', '.join(message.recipients)}\n"
        f"Date: {message.created_at:%Y-%m-%d %H:%M}\n\n"
        f"{body}"
    )


@tool
def delete_mail(message_id: str = "", query: str = "", folder: str = "inbox") -> str:
    """Move an email to trash.

    Args:
        message_id: the id returned by search_mail (preferred).
        query: when no message_id is given, locate the first message matching this
            description.
        folder: inbox | sent | archived | trash.
    """
    target = _resolve_message_id(message_id, query, folder)
    if not target:
        return "Could not find the email to delete."
    try:
        deleted = gmail_service.trash_message(target)
    except Exception as exc:  # noqa: BLE001
        return f"Failed to delete email: {exc}"
    return f"Deleted email '{deleted.subject}' (moved to trash)."


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
    target = _resolve_message_id(message_id, query, folder)
    if not target:
        return "Could not find the email to star."
    try:
        updated = gmail_service.update_message(target, starred=starred)
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
    target = _resolve_message_id(message_id, query, folder)
    if not target:
        return "Could not find the email to archive."
    try:
        archived = gmail_service.archive_message(target)
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
        sent = gmail_service.send_message(request)
    except Exception as exc:  # noqa: BLE001
        return f"Failed to send email: {exc}"
    return (
        f"Sent email '{sent.subject}' to {', '.join(sent.recipients)} "
        f"(message id: {sent.id})."
    )


MAIL_TOOLS = [search_mail, read_mail, delete_mail, star_mail, archive_mail, send_mail]


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


_agent_cache: dict[tuple[str, str], Any] = {}


def is_configured() -> bool:
    return bool(config.MAIL_LLM_API_KEY)


def build_agent() -> Any:
    """Build (and cache) the LangChain agent.

    Raises:
        RuntimeError: when no LLM API key is configured in the environment.
    """
    if not is_configured():
        raise RuntimeError(
            "Mail agent is not configured: set MAIL_LLM_API_KEY (or DEEPSEEK_API_KEY) "
            "in the repository .env file."
        )
    key = (config.MAIL_LLM_MODEL, config.MAIL_LLM_BASE_URL)
    if key not in _agent_cache:
        logger.info("building mail agent: model=%s base=%s", *key)
        _agent_cache[key] = create_react_agent(
            _llm(),
            MAIL_TOOLS,
            prompt=SystemMessage(content=SYSTEM_PROMPT),
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


async def run_chat(message: str, session_id: str) -> dict[str, Any]:
    """Run one chat turn against the mail agent and return the structured result.

    The session id is reused as the LangGraph thread id so multi-turn follow-ups
    ("再找一封", "那封考试邮件") keep their context.
    """
    agent = build_agent()
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
