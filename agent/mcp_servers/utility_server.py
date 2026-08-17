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
import logging
import os
import re
import sys
from html import unescape as html_unescape
from pathlib import Path
from zoneinfo import ZoneInfo

_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from dotenv import find_dotenv, load_dotenv
from fastapi import FastAPI

try:
    from mcp.server.fastmcp import FastMCP
except ImportError as _e:  # pragma: no cover - 依赖缺失/版本错误时的清晰报错
    raise ImportError(
        "无法导入 mcp.server.fastmcp：请安装 mcp 1.x（本项目锁定 1.x API）。"
        '执行：pip install "mcp>=1.28,<2"。'
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


# 政策/规章制度 RAG（LlamaIndex + Qdrant，复用 lost-found-embedding）。
# 延迟 import + 懒加载：llama-index 为可选依赖，缺失时仅 search_policy 不可用，
# 不影响 calculator / unit_converter / web_search 等既有工具。
_policy_retriever: object | None = None


def _get_policy_retriever():
    global _policy_retriever
    if _policy_retriever is None:
        from mcp_servers.policy_rag.config import PolicyRagSettings
        from mcp_servers.policy_rag.retriever import PolicyRetriever

        _policy_retriever = PolicyRetriever(PolicyRagSettings())
    return _policy_retriever


@mcp.tool()
def search_policy(query: str, top_k: int = 5) -> str:
    """检索学校政策/规章制度文档（NUS 学生守则、考试条例、评估规则等 PDF）。

    返回最相关的条款段落及来源（文件名+页码）。

    Args:
        query: 政策/规则相关的问题，如"考试可以带计算器吗""学生行为守则对抄袭的规定"
        top_k: 返回段落数（1-10，默认 5）

    低风险读操作，fail-open：Qdrant/embedding 服务不可用时返回 status=failed，
    绝不抛异常中断调用链。
    """
    top_k = max(1, min(10, int(top_k)))
    try:
        results = _get_policy_retriever().search(query, top_k=top_k)
    except Exception as exc:
        return json.dumps(
            {
                "query": query,
                "results": [],
                "error": f"policy search failed: {exc}",
                "status": "failed",
            },
            ensure_ascii=False,
        )
    return json.dumps(
        {
            "query": query,
            "results": results,
            "status": "ok" if results else "no_results",
        },
        ensure_ascii=False,
    )


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


# 货币代码映射（用户常用中文货币名 → ISO 4217）
_CURRENCY_ALIASES = {
    "人民币": "CNY",
    "美元": "USD",
    "欧元": "EUR",
    "英镑": "GBP",
    "日元": "JPY",
    "港币": "HKD",
    "新币": "SGD",
    "新加坡元": "SGD",
    "澳元": "AUD",
    "加元": "CAD",
    "韩元": "KRW",
    "卢布": "RUB",
    "泰铢": "THB",
    "马来西亚令吉": "MYR",
    "林吉特": "MYR",
}

# 实时汇率 API（免费、无需 key、JSON；货币换算优先实时，失败回退固定汇率）
_EXCHANGE_RATE_API = os.environ.get("EXCHANGE_RATE_API_URL", "https://open.er-api.com/v6/latest")

# 固定汇率兜底（API 不可用时使用，2026-08-15 起仅作降级）
_FALLBACK_RATES: dict[tuple[str, str], float] = {
    ("人民币", "美元"): 1 / 7.2,
    ("美元", "人民币"): 7.2,
    ("人民币", "欧元"): 1 / 7.8,
    ("欧元", "人民币"): 7.8,
}

# 货币换算结果缓存（TTL 1 小时，避免每次调用打汇率 API）
_rate_cache: dict[str, tuple[float, dict[str, float] | None]] = {}
_RATE_CACHE_TTL = 3600.0
# 失败负缓存 TTL（API 故障时短缓存，避免每请求阻塞 5s 重试）
_RATE_FAIL_TTL = 60.0

logger = logging.getLogger(__name__)


def _to_iso_code(name: str) -> str | None:
    """货币名 → ISO 4217 代码。支持中文别名与直接输入 ISO 代码（USD/CNY 等）。"""
    upper = name.strip().upper()
    if re.fullmatch(r"[A-Z]{3}", upper):
        return upper
    return _CURRENCY_ALIASES.get(name)


def _get_rates(base: str) -> dict[str, float] | None:
    """获取 base 货币对全货币汇率（带 1h 缓存）。失败返回 None（调用方回退固定汇率）。"""
    import time

    cached = _rate_cache.get(base)
    if cached and time.monotonic() - cached[0] < (_RATE_CACHE_TTL if cached[1] else _RATE_FAIL_TTL):
        return cached[1]
    try:
        import httpx

        response = httpx.get(
            f"{_EXCHANGE_RATE_API}/{base}",
            timeout=5.0,
            headers={"User-Agent": "CampusLink/1.0"},
        )
        response.raise_for_status()
        payload = response.json()
        rates = payload.get("rates") if isinstance(payload, dict) else None
        if not isinstance(rates, dict) or not rates:
            raise ValueError(f"empty rates payload: {str(payload)[:200]}")
        parsed = {k: float(v) for k, v in rates.items() if isinstance(v, (int, float))}
        _rate_cache[base] = (time.monotonic(), parsed)
        return parsed
    except Exception as exc:
        # 失败负缓存 + 日志：避免每请求阻塞 5s 重试且无迹可查
        logger.warning("exchange rate fetch failed: base=%s err=%s", base, exc)
        _rate_cache[base] = (time.monotonic(), None)
        return None


# 货币代码映射（用户常用中文货币名 → ISO 4217）
_CURRENCY_ALIASES = {
    "人民币": "CNY",
    "美元": "USD",
    "欧元": "EUR",
    "英镑": "GBP",
    "日元": "JPY",
    "港币": "HKD",
    "新币": "SGD",
    "新加坡元": "SGD",
    "澳元": "AUD",
    "加元": "CAD",
    "韩元": "KRW",
    "卢布": "RUB",
    "泰铢": "THB",
    "马来西亚令吉": "MYR",
    "林吉特": "MYR",
}

# 实时汇率 API（免费、无需 key、JSON；货币换算优先实时，失败回退固定汇率）
_EXCHANGE_RATE_API = os.environ.get("EXCHANGE_RATE_API_URL", "https://open.er-api.com/v6/latest")

# 固定汇率兜底（API 不可用时使用，2026-08-15 起仅作降级）
_FALLBACK_RATES: dict[tuple[str, str], float] = {
    ("人民币", "美元"): 1 / 7.2,
    ("美元", "人民币"): 7.2,
    ("人民币", "欧元"): 1 / 7.8,
    ("欧元", "人民币"): 7.8,
}

# 货币换算结果缓存（TTL 1 小时，避免每次调用打汇率 API）
_rate_cache: dict[str, tuple[float, dict[str, float] | None]] = {}
_RATE_CACHE_TTL = 3600.0
# 失败负缓存 TTL（API 故障时短缓存，避免每请求阻塞 5s 重试）
_RATE_FAIL_TTL = 60.0

logger = logging.getLogger(__name__)


def _to_iso_code(name: str) -> str | None:
    """货币名 → ISO 4217 代码。支持中文别名与直接输入 ISO 代码（USD/CNY 等）。"""
    upper = name.strip().upper()
    if re.fullmatch(r"[A-Z]{3}", upper):
        return upper
    return _CURRENCY_ALIASES.get(name)


def _get_rates(base: str) -> dict[str, float] | None:
    """获取 base 货币对全货币汇率（带 1h 缓存）。失败返回 None（调用方回退固定汇率）。"""
    import time

    cached = _rate_cache.get(base)
    if cached and time.monotonic() - cached[0] < (_RATE_CACHE_TTL if cached[1] else _RATE_FAIL_TTL):
        return cached[1]
    try:
        import httpx

        response = httpx.get(
            f"{_EXCHANGE_RATE_API}/{base}",
            timeout=5.0,
            headers={"User-Agent": "CampusLink/1.0"},
        )
        response.raise_for_status()
        payload = response.json()
        rates = payload.get("rates") if isinstance(payload, dict) else None
        if not isinstance(rates, dict) or not rates:
            raise ValueError(f"empty rates payload: {str(payload)[:200]}")
        parsed = {k: float(v) for k, v in rates.items() if isinstance(v, (int, float))}
        _rate_cache[base] = (time.monotonic(), parsed)
        return parsed
    except Exception as exc:
        # 失败负缓存 + 日志：避免每请求阻塞 5s 重试且无迹可查
        logger.warning("exchange rate fetch failed: base=%s err=%s", base, exc)
        _rate_cache[base] = (time.monotonic(), None)
        return None


@mcp.tool()
def unit_converter(value: float, from_unit: str, to_unit: str) -> str:
    """单位换算（长度/重量/温度/货币）。

    货币换算使用实时汇率（open.er-api.com，免费无 key，1 小时缓存）；
    API 不可用时回退固定汇率。非货币换算为固定比率。
    """
    # 货币换算：中文别名/ISO 代码 → 实时汇率
    from_code = _to_iso_code(from_unit)
    to_code = _to_iso_code(to_unit)
    # 涉及货币：任一侧是货币 → 走货币逻辑（含同币种恒 1 与错误语义）
    if from_code or to_code:
        if from_code and to_code:
            if from_code == to_code:  # 同币种恒为 1，无需查 API
                return json.dumps(
                    {
                        "value": value,
                        "from_unit": from_unit,
                        "to_unit": to_unit,
                        "result": value,
                        "rate": 1.0,
                        "source": "identity",
                    },
                    ensure_ascii=False,
                )
            rates = _get_rates(from_code)
            if rates and to_code in rates:
                result = value * rates[to_code]
                return json.dumps(
                    {
                        "value": value,
                        "from_unit": from_unit,
                        "to_unit": to_unit,
                        "result": result,
                        "rate": rates[to_code],
                        "source": "live",
                    },
                    ensure_ascii=False,
                )
            # API 失败 → 回退固定汇率
            fallback = _FALLBACK_RATES.get((from_unit, to_unit))
            if fallback is not None:
                return json.dumps(
                    {
                        "value": value,
                        "from_unit": from_unit,
                        "to_unit": to_unit,
                        "result": value * fallback,
                        "rate": fallback,
                        "source": "fallback",
                    },
                    ensure_ascii=False,
                )
        return json.dumps(
            {
                "value": value,
                "from_unit": from_unit,
                "to_unit": to_unit,
                "error": f"unsupported currency: {from_unit} → {to_unit}",
            },
            ensure_ascii=False,
        )

    conversions = {
        # 长度（米基准）
        ("米", "公里"): 0.001,
        ("公里", "米"): 1000,
        ("米", "英里"): 1 / 1609.344,
        ("英里", "米"): 1609.344,
        ("米", "英尺"): 3.28084,
        ("英尺", "米"): 1 / 3.28084,
        # 重量（千克基准）
        ("千克", "斤"): 2.0,
        ("斤", "千克"): 0.5,
        ("千克", "磅"): 2.20462,
        ("磅", "千克"): 1 / 2.20462,
        # 温度
        ("摄氏度", "华氏度"): None,  # 特殊处理
        ("华氏度", "摄氏度"): None,
    }
    key = (from_unit, to_unit)
    if key not in conversions:
        return json.dumps(
            {
                "value": value,
                "from_unit": from_unit,
                "to_unit": to_unit,
                "error": f"unsupported conversion: {from_unit} → {to_unit}",
            },
            ensure_ascii=False,
        )
    factor = conversions[key]
    if factor is None:  # 温度特殊换算
        if key == ("摄氏度", "华氏度"):
            result = value * 9 / 5 + 32
        else:
            result = (value - 32) * 5 / 9
    else:
        result = value * factor
    return json.dumps(
        {"value": value, "from_unit": from_unit, "to_unit": to_unit, "result": result},
        ensure_ascii=False,
    )


def _parse_ddg_results(html_text: str, max_results: int = 5) -> list[dict[str, str]]:
    """从 DuckDuckGo HTML 响应提取搜索结果（标题/链接/摘要），纯标准库。

    DDG html 端点每个结果块含 ``result__a``（标题+链接）与 ``result__snippet``
    （摘要）。解析失败/无结果时返回空列表，绝不抛异常（搜索为低风险读操作）。
    """
    results: list[dict[str, str]] = []

    def _clean(text: str) -> str:
        text = re.sub(r"<[^>]+>", "", text)  # 去内嵌标签
        return html_unescape(text).strip()

    titles = re.findall(
        r'<a[^>]*class="[^"]*result__a[^"]*"[^>]*href="([^"]+)"[^>]*>(.*?)</a>',
        html_text,
        re.DOTALL,
    )
    snippets = re.findall(
        r'<a[^>]*class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>',
        html_text,
        re.DOTALL,
    )
    for index, (url, title) in enumerate(titles[:max_results]):
        results.append(
            {
                "title": _clean(title),
                "url": url,
                "snippet": _clean(snippets[index]) if index < len(snippets) else "",
            }
        )
    return results


def _parse_bing_results(html_text: str, max_results: int = 5) -> list[dict[str, str]]:
    """从 Bing 搜索结果页提取结果（标题/链接/摘要），纯标准库。

    Bing 每个结果块为 ``<li class="b_algo">``，标题在 ``<h2><a href>``，
    摘要在 ``<p>``。解析失败/无结果时返回空列表，绝不抛异常。
    """
    results: list[dict[str, str]] = []

    def _clean(text: str) -> str:
        text = re.sub(r"<[^>]+>", "", text)
        return html_unescape(text).strip()

    for block in re.findall(r'<li class="b_algo".*?</li>', html_text, re.DOTALL)[:max_results]:
        title_m = re.search(r'<h2[^>]*>\s*<a[^>]*href="([^"]+)"[^>]*>(.*?)</a>', block, re.DOTALL)
        if not title_m:
            continue
        snip_m = re.search(r"<p[^>]*>(.*?)</p>", block, re.DOTALL)
        results.append(
            {
                "title": _clean(title_m.group(2)),
                "url": html_unescape(title_m.group(1)).strip(),
                "snippet": _clean(snip_m.group(1)) if snip_m else "",
            }
        )
    return results


# ─── 新闻查询专用路径（2026-08-17）───────────────────────────────────────
# 背景：通用网页搜索（DDG/Bing）对"有什么新闻 / What's the news?"这类查询
# 返回的是新闻网站首页（snippet 是站点介绍）而非具体新闻条目；且 DDG HTML
# 端点常被反爬（HTTP 202）导致英文查询空结果。新闻类查询改走 Google News
# RSS（免 key、稳定）：
#   - 无具体主题的泛新闻请求 → Top Stories 头条 RSS（返回真实头条条目）
#   - 含主题的新闻查询       → 搜索 RSS（query 命中相关报道）
# 结果统一为 {title, url, snippet, date}，供编排层 LLM 重述。

# 新闻意图检测：中英文新闻关键词（命中则把查询路由到新闻 RSS）
_NEWS_QUERY_RE = re.compile(
    r"(?:新闻|头条|最新消息|时事|热点|资讯|快讯|"
    r"news|headline|breaking|top\s*stories|what'?s\s+the\s+news|latest\s+news)",
    re.IGNORECASE,
)

# 泛新闻请求（整句即"要新闻"，无具体主题 → 头条 RSS）
_GENERIC_NEWS_RE = re.compile(
    r"^(?:今天|现在|最近|当前|今日)?(?:的)?(?:有什么|有啥|看下|看看|搜下|搜一下|"
    r"查一下|查询|有没有|来点|要|求)?(?:新闻|头条|最新消息|时事|热点|资讯|快讯)[?？。]?$"
    r"|^(?:what'?s\s+the\s+(?:latest\s+)?news|latest\s+news(?:\s+headlines)?(?:\s+today)?|"
    r"top\s+(?:news\s+)?headlines|(?:news|breaking\s+news|headlines?)(?:\s+today)?)[?？。]?$",
    re.IGNORECASE,
)

# Google News RSS 语言/地区参数（按查询语言选择新闻地区）
_NEWS_LOCALES = {
    "zh": {"hl": "zh-CN", "gl": "CN", "ceid": "CN:zh-Hans"},
    "en": {"hl": "en-US", "gl": "US", "ceid": "US:en"},
}


def _looks_chinese(text: str) -> bool:
    """启发式：文本是否含中文字符（决定新闻 RSS 语言/地区参数）。"""
    return any("\u4e00" <= ch <= "\u9fff" for ch in text[:200])


def _is_news_query(query: str) -> bool:
    """是否新闻类查询（命中即走 Google News RSS，避免返回新闻站首页）。"""
    return bool(_NEWS_QUERY_RE.search(query or ""))


def _is_generic_news_query(query: str) -> bool:
    """是否无具体主题的泛新闻请求（如"有什么新闻 / What's the news?"）。"""
    return bool(_GENERIC_NEWS_RE.match((query or "").strip()))


def _parse_rss_items(xml_text: str, max_results: int = 5) -> list[dict[str, str]]:
    """从 RSS XML 提取条目（标题/链接/摘要/日期），纯标准库。失败返回空列表。"""
    items: list[dict[str, str]] = []

    def _clean(text: str) -> str:
        # GNews RSS 的 description 中链接为 HTML 实体编码，须先 unescape 再去标签
        text = html_unescape(text or "")
        return re.sub(r"<[^>]+>", "", text).strip()

    for item in re.findall(r"<item>(.*?)</item>", xml_text, re.DOTALL)[:max_results]:
        title = re.search(r"<title>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</title>", item, re.DOTALL)
        if not title:
            continue
        link = re.search(r"<link>(.*?)</link>", item, re.DOTALL)
        pub = re.search(r"<pubDate>(.*?)</pubDate>", item, re.DOTALL)
        desc = re.search(r"<description>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</description>", item, re.DOTALL)
        items.append(
            {
                "title": _clean(title.group(1)),
                "url": (link.group(1).strip() if link else ""),
                "snippet": _clean(desc.group(1)) if desc else "",
                "date": (pub.group(1).strip() if pub else ""),
            }
        )
    return items


async def _fetch_news_headlines(client, query: str, max_results: int) -> list[dict[str, str]]:
    """Google News Top Stories RSS（泛新闻请求：返回真实头条条目）。

    中文查询优先中文头条；无结果时回退英文头条（部分内容只有英文源，
    2026-08-17 按用户反馈补充英文兜底；最终语言由编排层 LLM 重述统一）。
    """
    langs = ["zh", "en"] if _looks_chinese(query) else ["en"]
    for lang in langs:
        resp = await client.get("https://news.google.com/rss", params=_NEWS_LOCALES[lang])
        resp.raise_for_status()
        results = _parse_rss_items(resp.text, max_results)
        if results:
            return results
    return []


async def _fetch_news_search(client, query: str, max_results: int) -> list[dict[str, str]]:
    """Google News 搜索 RSS（含主题的新闻查询：命中相关报道）。

    中文查询优先中文源；无结果时回退英文源（部分内容只有英文有）。
    """
    langs = ["zh", "en"] if _looks_chinese(query) else ["en"]
    for lang in langs:
        resp = await client.get(
            "https://news.google.com/rss/search",
            params={"q": query, **_NEWS_LOCALES[lang]},
        )
        resp.raise_for_status()
        results = _parse_rss_items(resp.text, max_results)
        if results:
            return results
    return []


async def _search_duckduckgo(client, query: str, max_results: int) -> list[dict[str, str]]:
    """DuckDuckGo HTML 搜索（主后端）。

    注意：DDG 被反爬时返回 HTTP 202 挑战页（2xx，raise_for_status 不抛异常），
    解析结果为空——此时须抛异常让调用方降级 Bing，否则会静默返回空结果。
    """
    resp = await client.get("https://html.duckduckgo.com/html/", params={"q": query})
    resp.raise_for_status()
    results = _parse_ddg_results(resp.text, max_results)
    if not results:
        raise RuntimeError("duckduckgo returned no parseable results (challenge page?)")
    return results


async def _search_bing(client, query: str, max_results: int) -> list[dict[str, str]]:
    """Bing 搜索（DDG 降级后端；再失败则 fail-open 返回 failed）。

    按查询语言指定市场（mkt），避免无 locale 时对中文查询返回无关语言的
    垃圾结果（2026-08-17 实测：不带 mkt 的中文查询返回日文/英文无关页）。
    中文市场无结果时回退英文市场（部分内容只有英文源；最终语言由编排层
    LLM 重述统一）。
    """
    markets = ["zh-CN", "en-US"] if _looks_chinese(query) else ["en-US"]
    for mkt in markets:
        resp = await client.get("https://www.bing.com/search", params={"q": query, "mkt": mkt})
        resp.raise_for_status()
        results = _parse_bing_results(resp.text, max_results)
        if results:
            return results
    return []


@mcp.tool()
async def web_search(query: str, max_results: int = 5) -> str:
    """联网搜索（2026-08-15 接入；2026-08-17 修复新闻查询与反爬降级）。

    新闻类查询（含"新闻/头条/latest news/What's the news?"等）走 Google News
    RSS：无具体主题的泛新闻请求返回 Top Stories 真实头条，含主题的新闻查询
    返回相关报道（标题/链接/时间/摘要），避免通用搜索返回新闻站首页。
    通用查询走 DuckDuckGo HTML，被反爬（HTTP 202/异常）时降级 Bing。
    返回最多 max_results 条结果。搜索失败时返回 status=failed 并附错误信息
    （低风险读操作，fail-open：绝不抛异常中断调用链）。
    """
    max_results = max(1, min(10, int(max_results)))
    try:
        import httpx

        async with httpx.AsyncClient(
            timeout=10.0,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
                )
            },
        ) as client:
            if _is_news_query(query):
                # 新闻类：泛请求 → Top Stories 头条；含主题 → 新闻搜索 RSS
                if _is_generic_news_query(query):
                    results = await _fetch_news_headlines(client, query, max_results)
                else:
                    results = await _fetch_news_search(client, query, max_results)
            else:
                try:
                    results = await _search_duckduckgo(client, query, max_results)
                except Exception:
                    # DDG 反爬/异常 → 降级 Bing；Bing 再失败则 fail-open
                    try:
                        results = await _search_bing(client, query, max_results)
                    except Exception as exc:
                        return json.dumps(
                            {
                                "query": query,
                                "results": [],
                                "error": f"search failed: {exc}",
                                "status": "failed",
                            },
                            ensure_ascii=False,
                        )
    except Exception as exc:
        return json.dumps(
            {
                "query": query,
                "results": [],
                "error": f"search failed: {exc}",
                "status": "failed",
            },
            ensure_ascii=False,
        )
    return json.dumps(
        {
            "query": query,
            "results": results,
            "status": "ok" if results else "no_results",
        },
        ensure_ascii=False,
    )


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
