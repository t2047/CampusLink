"""Mail Agent - MCP 适配层：把 8091 真实邮件 REST 服务包装成 MCP invoke 工具。

组员自研的 mail REST 服务（agent/mail_agent，端口 8091，暴露 /api/mail/**）
之上提供 MCP streamable HTTP 端点（/mcp/），编排层可经标准 MCP 调用真实
邮件能力（搜索 / 阅读 / 发送 / 归档 / 删除 / 标记），无需改动既有 REST 实现。

安全：与其它 MCP Agent 一致 -- 挂 ``McpSecurityMiddleware``（RS256
Delegation Token 验签 + aud=mail-agent + X-Timestamp 窗口）；工具内用
``identity_from_context``（mcp_servers.security 公共 helper）从 Authorization
解析身份，作为 Bearer 透传给 8091（8091 当前只校验 Bearer 前缀，不验真伪）。

运行（独立进程，端口 8081；替换原 domain_server 的 mail-agent mock 实例）：
    uvicorn mcp_servers.mail_server:app --host 0.0.0.0 --port 8081 --reload

MCP 端点：http://<host>:8081/mcp/（编排层 MAIL_AGENT_MCP_URL 指向此处）
"""

from __future__ import annotations

import contextlib
import json
import logging
import os
import re
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, AsyncIterator

# 允许直接运行（无需安装包）：把 agent/ 加入 sys.path（用于 import mcp_servers）
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from fastapi import FastAPI

try:  # 本地联调便利：自动加载仓库根 .env；缺失时由外部注入环境变量
    from dotenv import find_dotenv, load_dotenv

    load_dotenv(find_dotenv())
except ImportError:  # pragma: no cover
    pass

try:
    from mcp.server.fastmcp import Context, FastMCP
except ImportError as _e:  # pragma: no cover - 依赖缺失/版本错误时的清晰报错
    raise ImportError(
        "无法导入 mcp.server.fastmcp：请安装 mcp 1.x（本项目锁定 1.x API）。"
        "执行：pip install \"mcp>=1.28,<2\"。"
    ) from _e

import httpx

from mcp_servers.security import McpSecurityMiddleware, identity_from_context

logger = logging.getLogger(__name__)

AGENT_NAME = "mail-agent"

# 8091 REST 客户端（base_url 从环境变量读，默认本地 8091）
MAIL_REST_URL = os.environ.get("MAIL_REST_URL", "http://127.0.0.1:8091").rstrip("/")

# 确认 TTL（对齐 mail-agent.json security.confirmationTtlSeconds=600）
_CONFIRM_TTL_SECONDS = int(os.environ.get("MAIL_CONFIRM_TTL_SECONDS", "600"))

# 进程内待确认操作：confirmation_id -> {mail_id, subject, action}
_PENDING_CONFIRMATION: dict[str, dict[str, Any]] = {}


# ──────────────────────────────────────────────────────────────────────
# 8091 REST 客户端
# ──────────────────────────────────────────────────────────────────────


class MailRestClient:
    """调 8091 mail REST 服务的异步客户端。

    8091 当前只校验 Bearer 前缀（_user_from_auth 不验真伪），任意值都放行，
    故此处透传编排层解析出的 user_id 作为 token，便于将来 8091 接真实身份后
    直接按 sub 区分用户而无需改本适配层。
    """

    def __init__(self, base_url: str) -> None:
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=httpx.Timeout(10.0),
        )

    async def close(self) -> None:
        await self._client.aclose()

    async def _request(
        self, method: str, path: str, user_id: str, **kwargs: Any
    ) -> dict[str, Any]:
        headers = {"Authorization": f"Bearer {user_id}"}
        resp = await self._client.request(method, path, headers=headers, **kwargs)
        resp.raise_for_status()
        return resp.json()

    # 对应 8091 接口
    async def list_messages(
        self, user_id: str, folder: str = "inbox", q: str = "",
        unread: bool | None = None, starred: bool | None = None,
        page: int = 0, size: int = 20,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {"folder": folder, "page": page, "size": size}
        if q:
            params["q"] = q
        if unread is not None:
            params["unread"] = unread
        if starred is not None:
            params["starred"] = starred
        return await self._request("GET", "/api/mail/messages", user_id, params=params)

    async def get_message(self, user_id: str, message_id: str) -> dict[str, Any]:
        return await self._request("GET", f"/api/mail/messages/{message_id}", user_id)

    async def send_message(
        self, user_id: str, recipients: list[str], subject: str, body: str,
    ) -> dict[str, Any]:
        return await self._request(
            "POST", "/api/mail/messages", user_id,
            json={"recipients": recipients, "subject": subject, "body": body},
        )

    async def update_message(
        self, user_id: str, message_id: str, patch: dict[str, Any],
    ) -> dict[str, Any]:
        return await self._request(
            "PATCH", f"/api/mail/messages/{message_id}", user_id, json=patch,
        )

    async def archive_message(self, user_id: str, message_id: str) -> dict[str, Any]:
        return await self._request(
            "POST", f"/api/mail/messages/{message_id}/archive", user_id,
        )

    async def delete_message(self, user_id: str, message_id: str) -> dict[str, Any]:
        return await self._request(
            "POST", f"/api/mail/messages/{message_id}/delete", user_id,
        )


_rest = MailRestClient(MAIL_REST_URL)


# ──────────────────────────────────────────────────────────────────────
# 意图解析（关键词规则；后续可换 LLM 意图解析）
# ──────────────────────────────────────────────────────────────────────


def _parse_intent(message: str) -> tuple[str, dict[str, Any]]:
    """把自然语言映射到 mail action。

    Returns:
        (action, params) -- action 取值：
        search / read / send / archive / delete / mark
    """
    msg = message.strip()
    low = msg.lower()

    # 发送邮件（最特殊，优先判）
    if any(k in msg for k in ["发邮件", "写信", "发一封", "发送邮件"]) or "send" in low:
        return ("send", _parse_send_fields(msg))

    # 删除（destructive，需确认）
    if any(k in msg for k in ["删除", "删掉", "移到回收站", "删了"]) or "delete" in low:
        return ("delete", {"query": _strip_keywords(msg, ["删除", "删掉", "移到回收站", "删了", "delete"])})

    # 归档
    if any(k in msg for k in ["归档", "存档"]) or "archive" in low:
        return ("archive", {"query": _strip_keywords(msg, ["归档", "存档", "archive"])})

    # 标记已读/未读/加星
    if any(k in msg for k in ["标记已读", "标为已读", "设为已读"]) or "mark read" in low:
        return ("mark", {"read": True, "query": _strip_keywords(msg, ["标记已读", "标为已读", "设为已读", "mark read"])})
    if any(k in msg for k in ["标记未读", "标为未读", "设为未读"]) or "mark unread" in low:
        return ("mark", {"read": False, "query": _strip_keywords(msg, ["标记未读", "标为未读", "设为未读", "mark unread"])})
    if any(k in msg for k in ["加星", "标星", "收藏"]) or "star" in low:
        return ("mark", {"starred": True, "query": _strip_keywords(msg, ["加星", "标星", "收藏", "star"])})

    # 阅读具体邮件（"看看 X 邮件"/"X 说了什么"）
    if any(k in msg for k in ["说了什么", "内容是什么", "看看", "阅读", "查看", "详情"]) or "read" in low:
        return ("read", {"query": _strip_keywords(msg, ["说了什么", "内容是什么", "看看", "阅读", "查看", "详情", "read"])})

    # 默认搜索：剥掉常见动词，留名词关键词；空则返回全部
    cleaned = _strip_keywords(
        msg, ["帮我", "帮我查", "帮我找", "查一下", "找一下", "看看",
              "看下", "查", "找", "find", "search", "list", "show",
              "邮件", "的", "了"],
    )
    return ("search", {"query": cleaned})


def _strip_keywords(msg: str, keywords: list[str]) -> str:
    """去掉意图关键词，剩余作为搜索词。"""
    cleaned = msg
    for k in keywords:
        cleaned = cleaned.replace(k, "")
    cleaned = cleaned.strip(" 的了这那帮我把请问一下看下看看").strip()
    return cleaned


_SEND_FIELD_RE = re.compile(
    r"(?:给|发送给|收件人[:：]?)\s*(?P<rec>[^，,。给主题内容]*)"
    r"(?:.*?(?:主题|标题)[:：]?\s*(?P<subject>[^，,。内容]*))?"
    r"(?:.*?(?:内容|正文)[:：]?\s*(?P<body>.*))?",
    re.DOTALL,
)


def _parse_send_fields(msg: str) -> dict[str, Any]:
    """从自然语言提取收件人/主题/正文（尽力而为，失败则留空让调用方提示）。"""
    m = _SEND_FIELD_RE.search(msg)
    if not m:
        return {"recipients": [], "subject": "", "body": ""}
    rec = (m.group("rec") or "").strip()
    # 收件人按逗号/顿号拆分
    recipients = [r.strip() for r in re.split(r"[,，、]", rec) if r.strip() and "@" in r]
    return {
        "recipients": recipients,
        "subject": (m.group("subject") or "").strip(),
        "body": (m.group("body") or "").strip(),
    }


# ──────────────────────────────────────────────────────────────────────
# 契约构造（对齐 mail-agent.json v1.1.0）
# ──────────────────────────────────────────────────────────────────────


def _ok(response: str, request_id: str, actions: list[dict[str, Any]] | None = None,
         shared: dict[str, Any] | None = None) -> str:
    return json.dumps(
        {
            "response": response,
            "status": "completed",
            "request_id": request_id,
            "actions_taken": actions or [],
            "shared_context": shared or {},
        },
        ensure_ascii=False,
    )


def _failed(response: str, request_id: str) -> str:
    return json.dumps(
        {
            "response": response,
            "status": "failed",
            "request_id": request_id,
            "actions_taken": [],
            "shared_context": {},
        },
        ensure_ascii=False,
    )


def _needs_confirmation(
    response: str, request_id: str, confirmation_id: str, action: str, summary: str,
) -> str:
    expires_at = datetime.now(timezone.utc) + timedelta(seconds=_CONFIRM_TTL_SECONDS)
    return json.dumps(
        {
            "response": response,
            "status": "needs_confirmation",
            "request_id": request_id,
            "confirmation_required": {
                "confirmation_id": confirmation_id,
                "action": action,
                "summary": summary,
                "expires_at": expires_at.isoformat(),
            },
            "shared_context": {},
        },
        ensure_ascii=False,
    )


def _fmt_message(brief: dict[str, Any]) -> str:
    """把一条邮件摘要格式化为可读文本。"""
    flag = ""
    if not brief.get("read", True):
        flag = "[未读]"
    if brief.get("starred"):
        flag += "[★]"
    return "- {flag}{subject}（来自 {sender}，{date}）".format(
        flag=flag + " " if flag else "",
        subject=brief.get("subject", "(无主题)"),
        sender=brief.get("sender", "?"),
        date=(brief.get("created_at", "") or "")[:10],
    )


# ──────────────────────────────────────────────────────────────────────
# MCP invoke 工具
# ──────────────────────────────────────────────────────────────────────

mcp = FastMCP(f"{AGENT_NAME}-server", streamable_http_path="/")
_streamable_app = mcp.streamable_http_app()


@mcp.tool()
async def invoke(
    message: str,
    conversation_context: dict | None = None,
    confirmed: bool = False,
    confirmation_id: str | None = None,
    trace_parent: dict | None = None,
    context: Context | None = None,
) -> str:
    """处理一条用户邮件请求（Mail Agent 主入口，MCP 适配）。

    Args:
        message: 用户自然语言请求（搜索 / 阅读 / 发送 / 归档 / 删除 / 标记）
        conversation_context: 跨 Agent 共享上下文（可选）
        confirmed: 用户是否已确认前一轮的待确认操作（HITL，删除用）
        confirmation_id: 待确认操作 ID（上一轮 needs_confirmation 返回）
        trace_parent: 分布式追踪信息（可选）

    Returns:
        JSON 字符串（对齐 mail-agent.json v1.1.0 invokeOutput 契约）
    """
    if context is None:
        return _failed("缺少请求上下文", "")

    request_id = str(uuid.uuid4())
    t0 = time.monotonic()

    # 从 RS256 Delegation Token 解析身份
    try:
        claims = identity_from_context(context, AGENT_NAME)
    except ValueError as exc:
        logger.warning("mail invoke auth failed: %s", exc)
        return _failed(f"鉴权失败：{exc}", request_id)
    user_id = str(claims.get("sub", ""))

    logger.info(
        "mail invoke start: request_id=%s user_id=%s message=%.60r",
        request_id, user_id, message,
    )

    try:
        return await _dispatch(message, confirmed, confirmation_id, user_id, request_id)
    except httpx.HTTPStatusError as exc:
        logger.warning("mail invoke REST error: %s %s", exc.response.status_code, exc)
        return _failed("邮件服务返回错误，请稍后重试。", request_id)
    except httpx.HTTPError as exc:
        logger.warning("mail invoke transport error: %s", exc)
        return _failed("邮件服务暂时不可用，请稍后重试。", request_id)
    except Exception as exc:
        logger.exception("mail invoke internal error: request_id=%s", request_id)
        return _failed("处理邮件请求时发生内部错误。", request_id)
    finally:
        logger.info(
            "mail invoke exit: request_id=%s elapsed=%.1fs",
            request_id, time.monotonic() - t0,
        )


async def _dispatch(
    message: str, confirmed: bool, confirmation_id: str | None,
    user_id: str, request_id: str,
) -> str:
    action, params = _parse_intent(message)

    # 确认重调（删除）
    if confirmed and confirmation_id:
        return await _handle_delete_confirm(user_id, confirmation_id, request_id)

    if action == "search":
        return await _handle_search(user_id, params, request_id)
    if action == "read":
        return await _handle_read(user_id, params, request_id)
    if action == "send":
        return await _handle_send(user_id, params, request_id)
    if action == "archive":
        return await _handle_archive(user_id, params, request_id)
    if action == "delete":
        return await _handle_delete(user_id, params, request_id)
    if action == "mark":
        return await _handle_mark(user_id, params, request_id)
    return _failed("无法理解你的邮件请求。", request_id)


# ── 搜索 ───────────────────────────────────────────────────────────────


async def _search_first(user_id: str, query: str, size: int = 5) -> dict[str, Any] | None:
    """按 query 定位第一封邮件。多词时拆词逐个尝试，取第一个有结果的。

    8091 用单子串匹配，多词查询（如 'the exam email'）需拆词后取最可能命中
    的实义词（最长词优先），否则整串匹配不到。
    """
    if not query:
        result = await _rest.list_messages(user_id, folder="inbox", q="", size=size)
        content = result.get("content", [])
        return content[0] if content else None
    words = sorted(query.split(), key=len, reverse=True)
    for word in words:
        if len(word) < 2:
            continue
        result = await _rest.list_messages(user_id, folder="inbox", q=word, size=size)
        if result.get("total_elements", 0) > 0:
            content = result.get("content", [])
            if content:
                return content[0]
    # 兜底：整串
    result = await _rest.list_messages(user_id, folder="inbox", q=query, size=size)
    content = result.get("content", [])
    return content[0] if content else None


async def _handle_search(user_id: str, params: dict[str, Any], request_id: str) -> str:
    query = params.get("query", "")
    # 8091 用单子串匹配，多词查询拆词逐个尝试，取第一个有结果的
    messages = []
    total = 0
    used_query = query
    if query:
        words = sorted(query.split(), key=len, reverse=True)
        for word in words:
            if len(word) < 2:
                continue
            result = await _rest.list_messages(user_id, folder="inbox", q=word, size=20)
            if result.get("total_elements", 0) > 0:
                messages = result.get("content", [])
                total = result.get("total_elements", 0)
                used_query = word
                break
        if not messages:
            result = await _rest.list_messages(user_id, folder="inbox", q=query, size=20)
            messages = result.get("content", [])
            total = result.get("total_elements", 0)
    else:
        result = await _rest.list_messages(user_id, folder="inbox", q="", size=20)
        messages = result.get("content", [])
        total = result.get("total_elements", 0)
    if not messages:
        return _ok(
            f"没有找到相关邮件。" + (f"（搜索词：{used_query}）" if used_query else ""),
            request_id,
            [{"action": "search_emails", "status": "success", "result_summary": "0 封"}],
        )
    lines = "\n".join(_fmt_message(m) for m in messages[:10])
    return _ok(
        f"找到 {total} 封邮件：\n{lines}",
        request_id,
        [{"action": "search_emails", "status": "success",
          "result_summary": f"{total} 封"}],
        {"query": used_query, "folder": "inbox"},
    )


# ── 阅读 ───────────────────────────────────────────────────────────────


async def _handle_read(user_id: str, params: dict[str, Any], request_id: str) -> str:
    query = params.get("query", "")
    target = await _search_first(user_id, query)
    if not target:
        return _ok(
            "没有找到相关邮件可阅读。",
            request_id,
            [{"action": "read_email", "status": "skipped", "result_summary": "未找到"}],
        )
    # 取详情（会自动标记已读）
    detail = await _rest.get_message(user_id, target["id"])
    body = detail.get("body", "")
    return _ok(
        f"邮件「{detail.get('subject', '(无主题)')}」\n"
        f"来自：{detail.get('sender', '?')}\n\n{body}",
        request_id,
        [{"action": "read_email", "status": "success",
          "result_summary": detail.get("subject", "")}],
        {"email_id": detail.get("id")},
    )


# ── 发送 ───────────────────────────────────────────────────────────────


async def _handle_send(user_id: str, params: dict[str, Any], request_id: str) -> str:
    recipients = params.get("recipients", [])
    subject = params.get("subject", "")
    body = params.get("body", "")
    if not recipients or not subject or not body:
        return _ok(
            "发送邮件需要收件人、主题和正文。"
            "请按「给 xxx@campus.edu 发邮件，主题：xxx，内容：xxx」的格式说明。",
            request_id,
            [{"action": "manage_email.send", "status": "skipped",
              "result_summary": "缺少字段"}],
        )
    sent = await _rest.send_message(user_id, recipients, subject, body)
    return _ok(
        f"已发送邮件「{sent.get('subject', '')}」给 {', '.join(recipients)}。",
        request_id,
        [{"action": "manage_email.send", "status": "success",
          "result_summary": sent.get("subject", "")}],
        {"sent_email_id": sent.get("id")},
    )


# ── 归档 ───────────────────────────────────────────────────────────────


async def _handle_archive(user_id: str, params: dict[str, Any], request_id: str) -> str:
    query = params.get("query", "")
    target = await _search_first(user_id, query)
    if not target:
        return _ok(
            "没有找到可归档的邮件。",
            request_id,
            [{"action": "manage_email.archive", "status": "skipped",
              "result_summary": "未找到"}],
        )
    archived = await _rest.archive_message(user_id, target["id"])
    return _ok(
        f"已归档邮件「{archived.get('subject', '')}」。",
        request_id,
        [{"action": "manage_email.archive", "status": "success",
          "result_summary": archived.get("subject", "")}],
        {"archived_email_id": archived.get("id")},
    )


# ── 删除（带 HITL 确认）────────────────────────────────────────────────


async def _handle_delete(user_id: str, params: dict[str, Any], request_id: str) -> str:
    query = params.get("query", "")
    target = await _search_first(user_id, query)
    if not target:
        return _ok(
            "没有找到可删除的邮件。",
            request_id,
            [{"action": "manage_email.delete", "status": "skipped",
              "result_summary": "未找到"}],
        )
    cid = uuid.uuid4().hex
    _PENDING_CONFIRMATION[cid] = {
        "mail_id": target["id"],
        "subject": target.get("subject", ""),
        "user_id": user_id,
    }
    subject = target.get("subject", "(无主题)")
    return _needs_confirmation(
        f"将删除邮件「{subject}」，此操作不可撤销，是否继续？",
        request_id, cid, "delete", f"删除邮件「{subject}」",
    )


async def _handle_delete_confirm(
    user_id: str, confirmation_id: str, request_id: str,
) -> str:
    pending = _PENDING_CONFIRMATION.pop(confirmation_id, None)
    if not pending:
        return _failed("确认请求无效或已过期，请重新发起该操作。", request_id)
    # 所有权校验：确认必须由同一用户完成
    if pending.get("user_id") != user_id:
        return _failed("确认请求无效或已过期，请重新发起该操作。", request_id)
    deleted = await _rest.delete_message(user_id, pending["mail_id"])
    subject = deleted.get("subject", "")
    return _ok(
        f"已删除邮件「{subject}」。",
        request_id,
        [{"action": "manage_email.delete", "status": "success",
          "result_summary": subject}],
        {"deleted_email_id": deleted.get("id")},
    )


# ── 标记（已读/未读/加星）────────────────────────────────────────────────


async def _handle_mark(user_id: str, params: dict[str, Any], request_id: str) -> str:
    query = params.get("query", "")
    patch: dict[str, Any] = {}
    if "read" in params:
        patch["read"] = params["read"]
    if "starred" in params:
        patch["starred"] = params["starred"]
    target = await _search_first(user_id, query)
    if not target:
        return _ok(
            "没有找到可标记的邮件。",
            request_id,
            [{"action": "manage_email.mark", "status": "skipped",
              "result_summary": "未找到"}],
        )
    updated = await _rest.update_message(user_id, target["id"], patch)
    label = []
    if "read" in patch:
        label.append("已读" if patch["read"] else "未读")
    if "starred" in patch:
        label.append("加星" if patch["starred"] else "取消星标")
    return _ok(
        f"已将邮件「{updated.get('subject', '')}」标记为{'、'.join(label)}。",
        request_id,
        [{"action": "manage_email.mark", "status": "success",
          "result_summary": updated.get("subject", "")}],
        {"marked_email_id": updated.get("id")},
    )


# ──────────────────────────────────────────────────────────────────────
# FastAPI 入口：挂载 MCP + 安全中间件
# ──────────────────────────────────────────────────────────────────────


@contextlib.asynccontextmanager
async def _lifespan(app: FastAPI) -> AsyncIterator[None]:
    # mount 到 FastAPI 后子应用 lifespan 不执行，task group 永远为 None ->
    # 必须由宿主应用手动 session_manager.run() 初始化（mcp 1.x 官方方式）
    async with mcp.session_manager.run():
        yield
    await _rest.close()


app = FastAPI(title=f"{AGENT_NAME} MCP Gateway", version="1.0.0", lifespan=_lifespan)
app.add_middleware(McpSecurityMiddleware, agent_name=AGENT_NAME)
app.mount("/mcp", _streamable_app)


@app.get("/health")
async def health() -> dict[str, object]:
    return {"status": "ok", "service": f"{AGENT_NAME}-mcp", "mail_rest_url": MAIL_REST_URL}
