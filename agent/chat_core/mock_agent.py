"""Mock Agent 服务 — 集成测试 / 本地联调用。

模拟 Domain Agent（Paradigm B）的 /agent/invoke + /agent/stream + /health，
集成完整安全中间件（HMAC + Nonce + Delegation Token 验签），
用于在真实 Agent 组开发完成前打通编排层端到端链路。

运行：
    MOCK_AGENT_NAME=mail-agent uvicorn mock_agent:app --port 8081
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

# 允许直接运行（无需安装包）：将仓库根加入 sys.path
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

# 复用共享安全中间件（agent/shared/security.py）
from shared.security import AgentSecurityMiddleware, get_security_config_from_env

app = FastAPI(title="Mock Domain Agent", version="0.1.0")

AGENT_NAME = os.environ.get("MOCK_AGENT_NAME", "mail-agent")
security = AgentSecurityMiddleware(get_security_config_from_env(AGENT_NAME))


class InvokeRequest(BaseModel):
    message: str = Field(..., description="用户自然语言请求")
    conversation_context: dict = Field(default_factory=dict)
    confirmed: bool = False
    trace_parent: dict = Field(default_factory=dict)


@app.post("/agent/invoke")
async def agent_invoke(request: Request):
    """接收编排层调用（需通过安全验证链）。"""
    # 安全验证（HMAC + Nonce + Delegation Token）
    verified = await security.verify(request)
    try:
        body = await request.json()
        payload = InvokeRequest(**body)
    except Exception:
        raise HTTPException(status_code=400, detail="invalid body")

    msg = payload.message

    # 简单的关键词模拟（对齐四类 Agent 的 Schema 示例）
    if "删除" in msg or "归档" in msg:
        return {
            "response": "已确认要删除邮件「考试安排通知」。此操作不可撤销，是否继续？",
            "status": "needs_confirmation",
            "confirmation_required": {
                "action": "delete",
                "target": "考试安排通知",
                "message": "确认删除该邮件？",
            },
            "actions_taken": [{"action": "manage_email.delete", "status": "pending_confirm"}],
            "shared_context": {},
        }
    if "预订" in msg or "预约" in msg:
        return {
            "response": "为你找到 301 研讨室，明天 15:00-17:00 可用。是否确认预订？",
            "status": "needs_confirmation",
            "confirmation_required": {
                "action": "book_room",
                "room": "301 研讨室",
                "slot": "2026-08-08 15:00-17:00",
                "message": "确认预订该时段？",
            },
            "actions_taken": [{"action": "search_rooms", "status": "ok", "result": "1 间可用"}],
            "shared_context": {"room_name": "301", "date": "2026-08-08"},
        }
    if "丢失" in msg or "丢" in msg:
        return {
            "response": "已登记你的失物信息，系统正在匹配。匹配到 1 件相似物品：黑色双肩包（图书馆）。",
            "status": "completed",
            "actions_taken": [{"action": "report_lost", "status": "ok"}, {"action": "search_found_items", "status": "ok"}],
            "shared_context": {},
        }
    # 邮件搜索默认
    return {
        "response": f"（Mock {AGENT_NAME}）找到 3 封相关邮件：\n[1] 张三 - 考试安排\n[2] 李四 - 项目进度\n[3] 王五 - 会议纪要",
        "status": "completed",
        "actions_taken": [{"action": "search_emails", "status": "ok", "result": "3 封"}],
        "shared_context": {},
    }


@app.get("/agent/stream")
async def agent_stream(request: Request):
    """流式端点（Sprint 1 mock：一次性返回事件序列）。"""
    verified = await security.verify(request)
    events = (
        "event:agent_start\ndata:{\"agent\":\"" + AGENT_NAME + "\"}\n\n"
        "event:agent_step\ndata:{\"action\":\"search_emails\",\"status\":\"ok\"}\n\n"
        "event:token\ndata:{\"content\":\"正在处理...\"}\n\n"
        "event:agent_done\ndata:{\"agent\":\"" + AGENT_NAME + "\"}\n\n"
    )
    return StreamingResponse(iter([events]), media_type="text/event-stream")


@app.get("/agent/capabilities")
async def agent_capabilities():
    """能力声明（对齐 agent/schemas/*.json）。"""
    return {
        "agent": AGENT_NAME,
        "version": "1.0.0",
        "capabilities": {
            "domains": ["email"],
            "description": "Mock Agent — 搜索/阅读/管理邮件",
            "examples": ["帮我找张三的邮件", "删除促销邮件"],
        },
    }


@app.get("/health")
async def health():
    return {"status": "ok", "service": AGENT_NAME}
