"""测试 — utility_server 单位换算(实时汇率/回退/别名/错误语义) 与 search_policy 政策检索。"""

import asyncio
import json
import sys
from pathlib import Path

# 使 mcp_servers 包可导入（直接运行/CI 均可用）
_ROOT = Path(__file__).resolve().parents[2]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from mcp_servers.utility_server import (
    _is_generic_news_query,
    _is_news_query,
    _parse_bing_results,
    _parse_rss_items,
    _search_bing,
    _to_iso_code,
    search_policy,
    unit_converter,
    web_search,
)


def test_to_iso_code_supports_alias_and_iso() -> None:
    assert _to_iso_code("人民币") == "CNY"
    assert _to_iso_code("美元") == "USD"
    assert _to_iso_code("USD") == "USD"
    assert _to_iso_code("cny") == "CNY"  # 大小写归一化
    assert _to_iso_code("米") is None  # 非货币
    assert _to_iso_code("US") is None  # 非 3 字母


def test_same_currency_identity() -> None:
    r = json.loads(unit_converter(100, "人民币", "人民币"))
    assert r["result"] == 100 and r["rate"] == 1.0 and r["source"] == "identity"
    r = json.loads(unit_converter(100, "USD", "usd"))
    assert r["source"] == "identity"


def test_iso_code_input() -> None:
    r = json.loads(unit_converter(100, "USD", "CNY"))
    assert "error" not in r and r["result"] > 0


def test_chinese_alias_input() -> None:
    r = json.loads(unit_converter(100, "美元", "人民币"))
    assert "error" not in r and r["result"] > 0


def test_mixed_currency_error_semantics() -> None:
    # 一侧货币一侧非货币 → 明确的货币错误（而非落入非货币分支）
    r = json.loads(unit_converter(100, "人民币", "瑞士法郎"))
    assert "unsupported currency" in r["error"]
    r = json.loads(unit_converter(100, "米", "USD"))
    assert "unsupported currency" in r["error"]


def test_non_currency_unaffected() -> None:
    r = json.loads(unit_converter(1, "米", "公里"))
    assert r["result"] == 0.001
    r = json.loads(unit_converter(100, "摄氏度", "华氏度"))
    assert r["result"] == 212


def test_fallback_when_rates_unavailable(monkeypatch) -> None:
    """汇率 API 失败 → 回退固定汇率（source=fallback）。"""
    monkeypatch.setattr("mcp_servers.utility_server._get_rates", lambda _base: None)
    r = json.loads(unit_converter(100, "美元", "人民币"))
    assert r["source"] == "fallback"
    # 结果应等于本地固定汇率表对应值（动态读取，汇率更新时无需改测试）
    from mcp_servers.utility_server import _FALLBACK_RATES

    assert abs(r["result"] - 100 * _FALLBACK_RATES[("USD", "CNY")]) < 1


def test_unsupported_pair_returns_error(monkeypatch) -> None:
    """API 失败且无固定回退 → 错误。"""
    monkeypatch.setattr("mcp_servers.utility_server._get_rates", lambda _base: None)
    r = json.loads(unit_converter(100, "美元", "日元"))
    assert "error" in r


# ─── search_policy（政策/规章制度 RAG）───────────────────────────────


class _FakeRetriever:
    """最小 fake：模拟 policy_rag.PolicyRetriever.search 的返回结构。"""

    def __init__(self, results=None, error: Exception | None = None) -> None:
        self._results = results or []
        self._error = error
        self.calls: list[dict] = []

    def search(self, query: str, top_k: int = 5):
        self.calls.append({"query": query, "top_k": top_k})
        if self._error:
            raise self._error
        return self._results


def test_search_policy_ok(monkeypatch) -> None:
    """命中 → status=ok，结果带来源标注。"""
    fake = _FakeRetriever(
        results=[
            {
                "text": "Calculators are not permitted in closed-book exams.",
                "score": 0.91,
                "source": "Instructions to Candidates for Assessments and Examinations.pdf#p2",
                "file": "Instructions to Candidates for Assessments and Examinations.pdf",
                "page": "2",
            }
        ]
    )
    monkeypatch.setattr("mcp_servers.utility_server._get_policy_retriever", lambda: fake)
    r = json.loads(search_policy("考试可以带计算器吗"))
    assert r["status"] == "ok"
    assert len(r["results"]) == 1
    assert r["results"][0]["source"].endswith("#p2")
    assert fake.calls[0]["top_k"] == 5


def test_search_policy_no_results(monkeypatch) -> None:
    """无命中 → status=no_results（不视为失败）。"""
    fake = _FakeRetriever(results=[])
    monkeypatch.setattr("mcp_servers.utility_server._get_policy_retriever", lambda: fake)
    r = json.loads(search_policy("完全无关的内容"))
    assert r["status"] == "no_results"
    assert r["results"] == []


def test_search_policy_fail_open(monkeypatch) -> None:
    """Qdrant/embedding 服务不可用 → status=failed + error，绝不抛异常。"""
    fake = _FakeRetriever(error=RuntimeError("qdrant connection refused"))
    monkeypatch.setattr("mcp_servers.utility_server._get_policy_retriever", lambda: fake)
    r = json.loads(search_policy("校规"))
    assert r["status"] == "failed"
    assert "error" in r and r["results"] == []


def test_search_policy_top_k_clamped(monkeypatch) -> None:
    """top_k 钳制到 1-10。"""
    fake = _FakeRetriever()
    monkeypatch.setattr("mcp_servers.utility_server._get_policy_retriever", lambda: fake)
    search_policy("q", top_k=99)
    assert fake.calls[0]["top_k"] == 10
    search_policy("q", top_k=0)
    assert fake.calls[1]["top_k"] == 1


# ─── web_search（联网搜索：新闻路径 + DDG→Bing 降级）──────────────────────


def test_news_query_classification() -> None:
    """新闻意图/泛新闻检测：中英文命中，非新闻查询不误伤。"""
    # 泛新闻（无具体主题 → 头条 RSS）
    assert _is_generic_news_query("What's the news?")
    assert _is_generic_news_query("What's the latest news?")
    assert _is_generic_news_query("latest news headlines today")
    assert _is_generic_news_query("最近有什么新闻")
    assert _is_generic_news_query("今天的新闻")
    # 含主题的新闻查询（→ 新闻搜索 RSS）
    assert _is_news_query("关于人工智能的新闻") and not _is_generic_news_query("关于人工智能的新闻")
    assert _is_news_query("2026 奥运会的新闻") and not _is_generic_news_query("2026 奥运会的新闻")
    # 非新闻查询不误伤
    assert not _is_news_query("明天天气怎么样")
    assert not _is_news_query("考试可以带计算器吗")
    assert not _is_news_query("帮我预约研讨室")
    assert not _is_news_query("1+1等于几")


_RSS_SAMPLE = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel><title>t</title>
<item>
  <title>First headline - BBC News</title>
  <link>https://example.com/a</link>
  <pubDate>Mon, 17 Aug 2026 08:28:00 GMT</pubDate>
  <description>&lt;ol&gt;&lt;li&gt;&lt;a href="https://example.com/a"&gt;First headline&lt;/a&gt;
  &lt;/li&gt;&lt;/ol&gt; BBC News</description>
</item>
<item>
  <title>Second story - CNN</title>
  <link>https://example.com/b</link>
  <pubDate>Sun, 16 Aug 2026 23:55:00 GMT</pubDate>
  <description>Plain snippet text</description>
</item>
</channel></rss>"""


def test_parse_rss_items_cleans_encoded_description() -> None:
    """RSS 解析：实体编码的 description 先 unescape 再去标签，snippet 无残留。"""
    items = _parse_rss_items(_RSS_SAMPLE, 5)
    assert len(items) == 2
    assert items[0]["title"] == "First headline - BBC News"
    assert items[0]["date"].startswith("Mon, 17 Aug 2026")
    assert "<a" not in items[0]["snippet"] and "<ol>" not in items[0]["snippet"]
    assert "First headline" in items[0]["snippet"]
    assert items[1]["snippet"] == "Plain snippet text"


_BING_SAMPLE = """<html><body>
<li class="b_algo"><h2><a href="https://example.com/1">Bing Result One</a></h2>
<p>First snippet text</p></li>
<li class="b_algo"><h2><a href="https://example.com/2">Bing Result Two</a></h2>
<p>Second snippet</p></li>
<li class="b_no">no results placeholder</li>
</body></html>"""


def test_parse_bing_results_extracts_algo_blocks() -> None:
    """Bing HTML 解析：只取 b_algo 结果块（标题/链接/摘要）。"""
    results = _parse_bing_results(_BING_SAMPLE, 5)
    assert len(results) == 2
    assert results[0]["title"] == "Bing Result One"
    assert results[0]["url"] == "https://example.com/1"
    assert results[0]["snippet"] == "First snippet text"


class _FakeResp:
    def __init__(self, text: str, status: int = 200) -> None:
        self.text = text
        self.status_code = status

    def raise_for_status(self) -> None:
        if self.status_code >= 400:
            raise RuntimeError(f"HTTP {self.status_code}")


class _FakeHttpClient:
    """替换 httpx.AsyncClient：按调用顺序返回预置响应，记录请求参数。"""

    def __init__(self, responses) -> None:
        self._responses = responses if isinstance(responses, list) else [responses]
        self.calls: list[tuple[str, dict | None]] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc) -> bool:
        return False

    async def get(self, url: str, params: dict | None = None) -> _FakeResp:
        self.calls.append((url, params))
        return self._responses.pop(0)


def _run_web_search(query: str, responses) -> tuple[dict, _FakeHttpClient]:
    """用假 httpx 跑 web_search，返回 (解析后的结果, 假 client 调用记录)。"""
    fake = _FakeHttpClient(responses)
    import httpx

    orig = httpx.AsyncClient
    httpx.AsyncClient = lambda *a, **k: fake  # type: ignore[assignment]
    try:
        raw = asyncio.run(web_search(query, max_results=5))
    finally:
        httpx.AsyncClient = orig  # type: ignore[assignment]
    return json.loads(raw), fake


def test_web_search_generic_news_hits_top_stories_rss(monkeypatch) -> None:
    """泛新闻请求（日志场景 "What's the news?"）→ Google News 头条 RSS。"""
    result, client = _run_web_search("What's the news?", [_FakeResp(_RSS_SAMPLE)])
    assert result["status"] == "ok"
    assert len(result["results"]) == 2
    # 只请求了头条 RSS（en-US），未走通用搜索
    assert len(client.calls) == 1
    url, params = client.calls[0]
    assert url == "https://news.google.com/rss"
    assert params["hl"] == "en-US"


def test_web_search_topic_news_hits_search_rss() -> None:
    """含主题的新闻查询 → Google News 搜索 RSS（中文走 zh-CN）。"""
    result, client = _run_web_search("关于人工智能的新闻", [_FakeResp(_RSS_SAMPLE)])
    assert result["status"] == "ok"
    url, params = client.calls[0]
    assert url == "https://news.google.com/rss/search"
    assert params["q"] == "关于人工智能的新闻"
    assert params["hl"] == "zh-CN"


def test_web_search_ddg_challenge_falls_back_to_bing() -> None:
    """DDG 返回 202 反爬页（2xx 不抛异常但解析为空）→ 降级 Bing。"""
    challenge = "<html>anomaly challenge page without results</html>"
    result, client = _run_web_search(
        "新加坡国立大学 2026 开学时间",
        [_FakeResp(challenge, status=202), _FakeResp(_BING_SAMPLE)],
    )
    assert result["status"] == "ok"
    assert len(result["results"]) == 2
    urls = [url for url, _ in client.calls]
    assert urls == ["https://html.duckduckgo.com/html/", "https://www.bing.com/search"]
    # 中文查询降级 Bing 时指定中文市场
    assert client.calls[1][1]["mkt"] == "zh-CN"


def test_web_search_ddg_ok_no_fallback() -> None:
    """DDG 正常返回 → 不降级 Bing。"""
    ddg_html = (
        '<a class="result__a" href="https://example.com/x">Title X</a>'
        '<a class="result__snippet">Snippet X</a>'
    )
    result, client = _run_web_search("what is a campus", [_FakeResp(ddg_html)])
    assert result["status"] == "ok"
    assert len(client.calls) == 1
    assert client.calls[0][0] == "https://html.duckduckgo.com/html/"


def test_web_search_all_backends_fail_fail_open() -> None:
    """DDG 与 Bing 均失败 → status=failed，绝不抛异常（fail-open）。"""
    result, client = _run_web_search(
        "query",
        [_FakeResp("", status=500), _FakeResp("", status=500)],
    )
    assert result["status"] == "failed"
    assert result["results"] == []
    assert "error" in result


def test_search_bing_sets_mkt_by_language() -> None:
    """Bing 降级按查询语言设置市场参数。"""

    async def go():
        fake = _FakeHttpClient([_FakeResp(_BING_SAMPLE)])
        await _search_bing(fake, "北京天气", 3)
        return fake.calls[0]

    url, params = asyncio.run(go())
    assert url == "https://www.bing.com/search"
    assert params["mkt"] == "zh-CN"


def test_news_zh_empty_falls_back_to_en() -> None:
    """中文新闻源无结果 → 回退英文源（部分内容只有英文有）。"""
    empty_rss = '<?xml version="1.0"?><rss><channel></channel></rss>'
    result, client = _run_web_search(
        "某个冷门话题的新闻",
        [_FakeResp(empty_rss), _FakeResp(_RSS_SAMPLE)],
    )
    assert result["status"] == "ok"
    assert len(result["results"]) == 2
    hls = [params["hl"] for _, params in client.calls]
    assert hls == ["zh-CN", "en-US"]


def test_bing_zh_empty_falls_back_to_en() -> None:
    """Bing 中文市场无结果 → 回退英文市场。"""
    empty_html = "<html><body><li class='b_no'>no results</li></body></html>"
    result, client = _run_web_search(
        "某个冷门话题",
        [_FakeResp(empty_html, status=202), _FakeResp(empty_html), _FakeResp(_BING_SAMPLE)],
    )
    assert result["status"] == "ok"
    assert len(result["results"]) == 2
    # DDG 反爬 → Bing zh-CN 空 → Bing en-US 命中
    markets = [params["mkt"] for _, params in client.calls[1:]]
    assert markets == ["zh-CN", "en-US"]
