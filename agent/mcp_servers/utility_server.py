"""Utility Tool MCP Server — 通用小工具集合。

暴露 4 个工具：
- calculator / get_current_time / unit_converter：已有实现（迁移自 mock_utility.py）
- web_search：占位实现（真实接入搜索 API 为 Sprint 3+ 待办）
- text_translator 已移除（2026-08-08：LLM 直答即可，无需专用翻译工具）

运行：
    uvicorn mcp_servers.utility_server:app --port 8090

MCP 端点：http://<host>:<port>/mcp/（streamable HTTP，走 McpSecurityMiddleware）
"""

from __future__ import annotations

import contextlib
import datetime
import json
import os
import re
import sys
from pathlib import Path
from zoneinfo import ZoneInfo

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

AGENT_NAME = "utility-tools"

# 启动环境检查：未配置 TOKEN_SERVICE_JWKS_URL 时无法 RS256 验签，请求将全部 401
if not os.environ.get("TOKEN_SERVICE_JWKS_URL"):
    print(
        f"[{AGENT_NAME}] WARNING: 未配置 TOKEN_SERVICE_JWKS_URL（RS256 验签必需），"
        "MCP 请求将全部返回 401。请先在仓库根目录 source .env（cd agent && set -a && "
        "source .env && set +a）。",
        file=sys.stderr,
    )
mcp = FastMCP(
    f"{AGENT_NAME}-server",
    streamable_http_path="/",
    # Docker 容器间使用服务名访问，需允许非 localhost Host 头。
    host=os.environ.get("FASTMCP_HOST", "127.0.0.1"),
)

# 必须先调用 streamable_http_app() 才能访问 mcp.session_manager
# （mcp 1.x：session manager 的 task group 由 run() 初始化）
_streamable_app = mcp.streamable_http_app()


# ──────────────────────────────────────────────────────────────────────
# 工具实现
# ──────────────────────────────────────────────────────────────────────

@mcp.tool()
def calculator(expression: str) -> str:
    """执行数学表达式计算（支持 + - * / 幂 开方）。"""
    # 安全：仅允许数学字符（防代码注入）
    if not re.fullmatch(r"[0-9+\-*/().\s^sqrt]*", expression):
        return json.dumps({"expression": expression, "error": "unsafe expression"})
    try:
        safe_expr = expression.replace("^", "**").replace("sqrt", "math.sqrt")
        import math
        result = eval(safe_expr, {"__builtins__": {}}, {"math": math})
        return json.dumps({"expression": expression, "result": result})
    except Exception as e:
        return json.dumps({"expression": expression, "error": f"eval error: {e}"})


@mcp.tool()
def get_current_time(timezone: str = "Asia/Singapore", format: str = "datetime") -> str:
    """获取指定时区的当前日期时间。format: datetime | date | time | iso8601。

    timezone 缺省 Asia/Singapore（项目部署地，与编排层 system_facts 一致；
    2026-08-15 修复：此前默认 Asia/Shanghai 且未做时区转换，服务器 UTC 时
    返回的时间值与标注时区不符）。
    """
    try:
        # 真正按 timezone 转换（容器内系统时区默认 UTC，不能依赖 datetime.now()）
        now = datetime.datetime.now(ZoneInfo(timezone))
    except Exception:
        # 时区名无效或时区数据库缺失 → 回退服务器本地时间（UTC），标注实际时区
        now = datetime.datetime.now()
        timezone = now.astimezone().tzinfo.tzname(None) or timezone
    if format == "date":
        return json.dumps({"timezone": timezone, "value": now.strftime("%Y-%m-%d")})
    if format == "time":
        return json.dumps({"timezone": timezone, "value": now.strftime("%H:%M:%S")})
    if format == "iso8601":
        return json.dumps({"timezone": timezone, "value": now.isoformat()})
    return json.dumps({"timezone": timezone, "value": now.strftime("%Y-%m-%d %H:%M:%S")})


@mcp.tool()
def unit_converter(value: float, from_unit: str, to_unit: str) -> str:
    """单位换算（长度/重量/温度/货币的基础换算）。"""
    conversions = {
        # 长度（米基准）
        ("米", "公里"): 0.001, ("公里", "米"): 1000,
        ("米", "英里"): 1 / 1609.344, ("英里", "米"): 1609.344,
        ("米", "英尺"): 3.28084, ("英尺", "米"): 1 / 3.28084,
        # 重量（千克基准）
        ("千克", "斤"): 2.0, ("斤", "千克"): 0.5,
        ("千克", "磅"): 2.20462, ("磅", "千克"): 1 / 2.20462,
        # 温度
        ("摄氏度", "华氏度"): None,  # 特殊处理
        ("华氏度", "摄氏度"): None,
        # 货币（简化固定汇率）
        ("人民币", "美元"): 1 / 7.2, ("美元", "人民币"): 7.2,
        ("人民币", "欧元"): 1 / 7.8, ("欧元", "人民币"): 7.8,
    }
    key = (from_unit, to_unit)
    if key not in conversions:
        return json.dumps({"value": value, "from_unit": from_unit, "to_unit": to_unit,
                           "error": f"unsupported conversion: {from_unit} → {to_unit}"})
    factor = conversions[key]
    if factor is None:  # 温度特殊换算
        if key == ("摄氏度", "华氏度"):
            result = value * 9 / 5 + 32
        else:
            result = (value - 32) * 5 / 9
    else:
        result = value * factor
    return json.dumps({"value": value, "from_unit": from_unit, "to_unit": to_unit, "result": result})


@mcp.tool()
def web_search(query: str) -> str:
    """联网搜索（Sprint 3+ 接入搜索 API；当前返回占位）。"""
    return json.dumps({
        "query": query,
        "results": [],
        "note": "联网搜索需接入搜索 API（Sprint 3+ 待办）",
        "status": "placeholder",
    })


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

app = FastAPI(title="Utility Tools MCP Server", version="1.0.0", lifespan=_lifespan)
app.add_middleware(McpSecurityMiddleware, agent_name=AGENT_NAME)
app.mount("/mcp", _streamable_app)


@app.get("/health")
async def health():
    return {"status": "ok", "service": AGENT_NAME, "mcp": True}
