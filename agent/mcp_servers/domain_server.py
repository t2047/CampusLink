"""Domain Agent MCP Server — 每个领域 Agent 一个独立进程。

暴露单个 ``invoke`` 工具（参数 message / conversation_context / confirmed /
trace_parent，返回原自研 REST 契约结构）。当前为关键词 mock 逻辑（真实 Agent
接入时替换 ``_handle_request`` 即可）。

运行：
    MCP_AGENT_NAME=mail-agent uvicorn mcp_servers.domain_server:app --port 8081
    MCP_AGENT_NAME=facility-agent uvicorn mcp_servers.domain_server:app --port 8082
    ...

MCP 端点：http://<host>:<port>/mcp/（streamable HTTP，走 McpSecurityMiddleware）
"""

from __future__ import annotations

import contextlib
import json
import os
import sys
import uuid
from pathlib import Path

# 允许直接运行（无需安装包）：将仓库根加入 sys.path
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from fastapi import FastAPI
from dotenv import find_dotenv, load_dotenv

try:
    from mcp.server.fastmcp import FastMCP
except ImportError as _e:  # pragma: no cover - 依赖缺失/版本错误时的清晰报错
    raise ImportError(
        "无法导入 mcp.server.fastmcp：请安装 mcp 1.x（本项目锁定 1.x API）。"
        "执行：pip install \"mcp>=1.28,<2\"。"
        "注意：若之前执行过无版本限制的 pip install mcp，会装成 2.x 并移除 fastmcp 模块，需降级。"
    ) from _e

from mcp_servers.security import McpSecurityMiddleware

# 自动加载仓库根目录 .env（向上查找；不覆盖已设置的变量）
load_dotenv(find_dotenv())

AGENT_NAME = os.environ.get("MCP_AGENT_NAME", "mail-agent")

# 启动环境检查：未配置 TOKEN_SERVICE_JWKS_URL 时无法 RS256 验签，请求将全部 401
if not os.environ.get("TOKEN_SERVICE_JWKS_URL"):
    print(
        f"[{AGENT_NAME}] WARNING: 未配置 TOKEN_SERVICE_JWKS_URL（RS256 验签必需），"
        "MCP 请求将全部返回 401。请先在仓库根目录 source .env（cd agent && set -a && "
        "source .env && set +a）。",
        file=sys.stderr,
    )

# streamable_http_path="/"：挂载到 FastAPI 的 /mcp 后端点即 /mcp/
mcp = FastMCP(f"{AGENT_NAME}-server", streamable_http_path="/")

# 必须先调用 streamable_http_app() 才能访问 mcp.session_manager
# （mcp 1.x：session manager 的 task group 由 run() 初始化）
_streamable_app = mcp.streamable_http_app()


@mcp.tool()
def invoke(
    message: str,
    conversation_context: dict | None = None,
    confirmed: bool = False,
    confirmation_id: str | None = None,
    trace_parent: dict | None = None,
) -> str:
    """处理一条用户请求（Domain Agent 主入口）。

    Args:
        message: 用户自然语言请求
        conversation_context: 跨 Agent 共享上下文（可选）
        confirmed: 用户是否已确认前一轮的待确认操作（HITL）
        confirmation_id: 待确认操作 ID（上一轮 needs_confirmation 返回，确认重调时传入）
        trace_parent: 分布式追踪信息（可选）

    Returns:
        JSON 字符串（客户端按 JSON 解析；不依赖 MCP structured_content 类型）
    """
    result = _handle_request(message, confirmed, conversation_context or {}, confirmation_id)
    return json.dumps(result, ensure_ascii=False)


# ──────────────────────────────────────────────────────────────────────
# Mock 业务逻辑（迁移自 mock_agent.py；真实 Agent 接入时替换本函数）
# ──────────────────────────────────────────────────────────────────────

# 进程内待确认操作记录：confirmation_id -> {action, target}
# （真实实现可持久化；此处仅用于校验确认重调的有效性）
_PENDING_CONFIRMATION: dict[str, dict] = {}


def _handle_request(
    message: str,
    confirmed: bool,
    context: dict,
    confirmation_id: str | None = None,
) -> dict:
    # 关键词模拟（对齐四类 Agent 的 Schema 示例）
    if "删除" in message or "归档" in message:
        if confirmed:
            # HITL 确认重调：必须携带上一轮返回的 confirmation_id
            if not confirmation_id or confirmation_id not in _PENDING_CONFIRMATION:
                return {
                    "response": "确认请求无效或已过期，请重新发起该操作。",
                    "status": "failed",
                    "actions_taken": [{"action": "manage_email.delete", "status": "failed"}],
                    "shared_context": {},
                }
            _PENDING_CONFIRMATION.pop(confirmation_id, None)
            return {
                "response": "已删除邮件「考试安排通知」。",
                "status": "completed",
                "actions_taken": [{"action": "manage_email.delete", "status": "ok"}],
                "shared_context": {},
            }
        cid = uuid.uuid4().hex
        _PENDING_CONFIRMATION[cid] = {"action": "delete", "target": "考试安排通知"}
        return {
            "response": "已确认要删除邮件「考试安排通知」。此操作不可撤销，是否继续？",
            "status": "needs_confirmation",
            "confirmation_required": {
                "confirmation_id": cid,
                "action": "delete",
                "target": "考试安排通知",
                "message": "确认删除该邮件？",
            },
            "actions_taken": [{"action": "manage_email.delete", "status": "pending_confirm"}],
            "shared_context": {},
        }
    return {
        "response": "已为你找到 3 封相关邮件：考试安排、社团活动、图书馆催还。",
        "status": "completed",
        "actions_taken": [{"action": "search_emails", "status": "ok", "result": "3 封"}],
        "shared_context": {},
    }


# ──────────────────────────────────────────────────────────────────────
# FastAPI 入口：挂载 MCP + 安全中间件
# ──────────────────────────────────────────────────────────────────────

@contextlib.asynccontextmanager
async def _lifespan(app: FastAPI):
    # 关键：mount 到 FastAPI 后，子应用的 lifespan 不执行，task group 永远为
    # None → 每个请求报 "Task group is not initialized"。必须由宿主应用
    # 手动 session_manager.run() 初始化（mcp 1.x 官方 mounting 方式）。
    async with mcp.session_manager.run():
        yield

app = FastAPI(title=f"{AGENT_NAME} MCP Server", version="1.0.0", lifespan=_lifespan)
app.add_middleware(McpSecurityMiddleware, agent_name=AGENT_NAME)
app.mount("/mcp", _streamable_app)


@app.get("/health")
async def health():
    return {"status": "ok", "service": AGENT_NAME, "mcp": True}
