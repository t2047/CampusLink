"""Mock Utility Tool Server — 集成测试 / 本地联调用。

模拟 Utility Tool Provider（Paradigm A）的 /tools/list + /tools/call，
集成完整安全中间件，用于在 Utility 组开发完成前打通编排层 Utility 路径。

运行：
    uvicorn mock_utility:app --port 8090
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

import time

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field

from shared.security import AgentSecurityMiddleware, get_security_config_from_env

app = FastAPI(title="Mock Utility Tools", version="0.1.0")

AGENT_NAME = "utility-tools"
security = AgentSecurityMiddleware(get_security_config_from_env(AGENT_NAME))


class ToolCallRequest(BaseModel):
    jsonrpc: str = "2.0"
    id: str = Field(..., description="请求 ID")
    method: str = Field(..., description="方法名，应为 tools/call")
    params: dict = Field(default_factory=dict)


TOOLS = [
    {
        "name": "calculator",
        "description": "执行数学表达式计算（支持 + - * / 开方 幂）",
        "inputSchema": {
            "type": "object",
            "properties": {
                "expression": {"type": "string", "description": "数学表达式"}
            },
            "required": ["expression"],
        },
    },
    {
        "name": "get_current_time",
        "description": "获取当前日期时间",
        "inputSchema": {
            "type": "object",
            "properties": {
                "timezone": {"type": "string", "default": "Asia/Shanghai"},
                "format": {"type": "string", "enum": ["datetime", "date", "time", "iso8601"]},
            },
        },
    },
    {
        "name": "unit_converter",
        "description": "单位换算",
        "inputSchema": {
            "type": "object",
            "properties": {
                "value": {"type": "number"},
                "from_unit": {"type": "string"},
                "to_unit": {"type": "string"},
            },
            "required": ["value", "from_unit", "to_unit"],
        },
    },
]


@app.post("/tools/call")
async def tools_call(request: Request):
    """执行 Utility Tool（JSON-RPC 2.0）。"""
    verified = await security.verify(request)
    try:
        body = await request.json()
        payload = ToolCallRequest(**body)
    except Exception:
        raise HTTPException(status_code=400, detail="invalid JSON-RPC request")

    if payload.method != "tools/call":
        return {"jsonrpc": "2.0", "id": payload.id, "error": {"code": -32601, "message": "method not found"}}

    name = payload.params.get("name", "")
    args = payload.params.get("arguments", {})

    if name == "calculator":
        expr = args.get("expression", "0")
        # 安全：仅允许数学字符（防代码注入）
        import re
        if not re.fullmatch(r"[0-9+\-*/().\s^sqrt]*", expr):
            return {"jsonrpc": "2.0", "id": payload.id, "error": {"code": -32000, "message": "unsafe expression"}}
        try:
            # 用安全求值：先替换 ^ 为 **，sqrt 为 math.sqrt
            safe_expr = expr.replace("^", "**").replace("sqrt", "math.sqrt")
            import math
            result = eval(safe_expr, {"__builtins__": {}}, {"math": math})
            return {"jsonrpc": "2.0", "id": payload.id, "result": {"expression": expr, "result": result}}
        except Exception as e:
            return {"jsonrpc": "2.0", "id": payload.id, "error": {"code": -32000, "message": f"eval error: {e}"}}

    if name == "get_current_time":
        import datetime
        now = datetime.datetime.now()
        tz = args.get("timezone", "Asia/Shanghai")
        fmt = args.get("format", "datetime")
        if fmt == "date":
            dt = now.strftime("%Y-%m-%d")
        elif fmt == "time":
            dt = now.strftime("%H:%M:%S")
        elif fmt == "iso8601":
            dt = now.isoformat()
        else:
            dt = now.strftime("%Y-%m-%d %H:%M:%S")
        weekday = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"][now.weekday()]
        return {
            "jsonrpc": "2.0",
            "id": payload.id,
            "result": {
                "datetime": dt,
                "iso8601": now.isoformat(),
                "timezone": tz,
                "timestamp": int(now.timestamp()),
                "day_of_week": weekday,
            },
        }

    if name == "unit_converter":
        # 简化实现：仅长度 km<->mile，kg<->lb
        value = float(args.get("value", 0))
        frm = args.get("from_unit", "")
        to = args.get("to_unit", "")
        factor_map = {
            ("km", "mile"): 0.621371,
            ("mile", "km"): 1.609344,
            ("kg", "lb"): 2.204623,
            ("lb", "kg"): 0.453592,
            ("celsius", "fahrenheit"): None,  # 特殊公式
        }
        key = (frm, to)
        if key in factor_map:
            factor = factor_map[key]
            result = value * factor if factor is not None else value * 9 / 5 + 32
            return {
                "jsonrpc": "2.0",
                "id": payload.id,
                "result": {
                    "result": round(result, 4),
                    "formula": f"{frm} → {to}",
                    "from_unit": frm,
                    "to_unit": to,
                },
            }
        return {"jsonrpc": "2.0", "id": payload.id, "error": {"code": -32000, "message": "unsupported conversion"}}

    return {"jsonrpc": "2.0", "id": payload.id, "error": {"code": -32601, "message": f"tool {name} not found"}}


@app.get("/tools/list")
async def tools_list():
    """返回所有 Tool 定义（对齐 MCP Tool Schema 契约）。"""
    return {"server": AGENT_NAME, "version": "1.0.0", "tools": TOOLS}


@app.get("/health")
async def health():
    return {"status": "ok", "service": AGENT_NAME}
