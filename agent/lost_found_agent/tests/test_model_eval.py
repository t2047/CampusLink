"""真实模型批量评估（model_eval）测试。

覆盖 `lost_found_agent.model_eval`：
- run_evaluation 基于 MockTransport 模拟 LLM 响应，验证质量指标
  （意图准确率 / 字段完整率 / 误写率）、token 用量、费用估算与延迟分位；
- 未解析的模型输出应计入失败，且触发生产同款重试路径（attempts）；
- 未配置 LOST_FOUND_LLM_API_KEY 时 main() 输出 skipped 报告（不调真实模型）；
- 模型回归语料（fixtures/model_regression.jsonl）能被 load_cases 正确加载。

策略：用 httpx.MockTransport 注入可控的假 LLM 响应，避免依赖真实模型；
用 monkeypatch 清空 API Key 验证 skip 分支。
"""

import json
from pathlib import Path

import httpx
import pytest

from lost_found_agent.config import Settings, get_settings
from lost_found_agent.model_eval import main, run_evaluation, skipped_report
from lost_found_agent.nlu_eval import EvaluationCase, load_cases

# 模型回归语料：每行一条用例（message / intent / fields / must_not_write）。
FIXTURE = Path(__file__).parent / "fixtures" / "model_regression.jsonl"


def make_settings() -> Settings:
    """构造一份测试用 Settings：填充安全密钥、假 LLM Key 与明确的 token 单价。

    单价固定（1M 输入 1000 美元 / 1M 输出 2000 美元），便于断言费用的精确值。
    """
    return Settings(
        agent_shared_secret="a" * 64,  # 固定长度密钥（满足 Settings 校验）
        agent_backend_shared_secret="b" * 64,
        lost_found_confirmation_secret="c" * 64,
        lost_found_llm_api_key="mock-key",  # 非空，确保走到"真实评估"分支
        lost_found_llm_input_cost_per_1m=1000.0,  # 输入 token 单价：每 1M 记 1000 美元
        lost_found_llm_output_cost_per_1m=2000.0,  # 输出 token 单价：每 1M 记 2000 美元
    )


def valid_handler(request: httpx.Request) -> httpx.Response:
    """模拟一次"成功"的 LLM 调用：返回合法的 NLU JSON 与固定 token 用量。

    从请求体的第 2 条消息（第 1 条通常是系统提示）取出用户消息；
    消息含 "search" 时意图为 search_found_items，否则为 report_lost，
    以此驱动意图准确率断言。
    """
    body = json.loads(request.content)
    user = json.loads(body["messages"][1]["content"])  # 取用户消息（跳过系统提示）
    message = user["message"]
    # 根据消息内容选择意图，保证与测试用例的期望一致
    content = (
        {"intent": "search_found_items", "fields": {"keyword": "test"}, "language": "en"}
        if "search" in message
        else {"intent": "report_lost", "fields": {"item_name": "red key pouch"}, "language": "zh"}
    )
    return httpx.Response(
        200,
        json={
            "choices": [{"message": {"content": json.dumps(content)}}],
            "usage": {"prompt_tokens": 10, "completion_tokens": 5},  # 固定用量便于费用断言
        },
    )


async def test_run_evaluation_reports_quality_latency_and_cost() -> None:
    """run_evaluation 应产出完整的质量 / 延迟 / 费用报告。

    两条相同用例（意图 search_found_items，字段 keyword=test，且 must_not_write）
    全部被正确解析，因此意图准确率与字段完整率应为 1.0、误写率为 0。
    """
    cases = [
        # 语义重复的查询，用于验证两条用例都会被独立评估
        EvaluationCase(
            "please search for the test keyword",
            "search_found_items",
            {"keyword": "test"},
            must_not_write=True,  # 查询是只读搜索，不允许触发写操作
        ),
        EvaluationCase(
            "please search for the test keyword again",
            "search_found_items",
            {"keyword": "test"},
            must_not_write=True,
        ),
    ]
    # MockTransport 拦截所有 HTTP 请求，返回 valid_handler 的固定响应
    client = httpx.AsyncClient(transport=httpx.MockTransport(valid_handler))

    report = await run_evaluation(cases, make_settings(), client=client)
    await client.aclose()  # 显式关闭，避免异步资源泄漏

    assert report["status"] == "completed"  # 评估成功完成
    assert report["attempts"] == 3  # 默认重试次数与生产路径一致
    assert report["intent_accuracy"] == 1.0  # 两条用例意图全部命中
    assert report["field_completeness"] == 1.0  # 期望字段全部预测正确
    assert report["mistaken_write_rate"] == 0.0  # 没有误触发写操作
    assert report["failures"] == []  # 无失败用例
    # 每次调用 prompt_tokens=10、completion_tokens=5，两条用例各翻倍
    assert report["tokens"] == {"input": 20, "output": 10}
    # 费用 = 20/1e6*1000 + 10/1e6*2000 = 0.02 + 0.02 = 0.04 美元
    assert report["estimated_cost_usd"] == 0.04
    assert report["cost_configured"] is True  # 单价已配置，费用可估算
    assert report["latency_ms"]["p95"] >= 0.0  # 延迟分位至少为非负
    assert report["latency_ms"]["p50"] <= report["latency_ms"]["p99"]  # 分位顺序自洽


async def test_run_evaluation_uses_production_retry_path() -> None:
    """模型首次返回非法 JSON 时应走生产同款重试，第二次成功并计入结果。

    验证 run_evaluation 的重试逻辑：attempts=2 意味着首次失败后还会重试一次。
    """
    calls = 0  # 统计 handler 被调用的次数

    # 第一次调用返回非法 JSON（触发重试），此后交给 valid_handler
    def flaky_handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(
                200,
                json={"choices": [{"message": {"content": "not valid json"}}]},
            )
        return valid_handler(request)

    cases = [
        EvaluationCase(
            "please search for the test keyword",
            "search_found_items",
            {"keyword": "test"},
            must_not_write=True,
        )
    ]
    client = httpx.AsyncClient(transport=httpx.MockTransport(flaky_handler))

    # attempts=2：首次非法输出失败后重试 1 次，第二次成功
    report = await run_evaluation(cases, make_settings(), client=client, attempts=2)
    await client.aclose()

    assert calls == 2  # 恰好调用两次（1 次失败 + 1 次成功）
    assert report["intent_accuracy"] == 1.0  # 重试成功后意图判定正确
    assert report["failures"] == []  # 最终重试成功，不算失败


async def test_run_evaluation_counts_unparseable_model_output_as_failure() -> None:
    """模型持续返回无法解析的输出时，应记为失败并清空质量指标。"""
    # handler 始终返回非法 JSON，重试也不会成功
    def failing_handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"choices": [{"message": {"content": "not valid json"}}]})

    cases = [
        EvaluationCase(
            "search the test keyword",
            "search_found_items",
            {"keyword": "test"},
            must_not_write=True,
        )
    ]
    client = httpx.AsyncClient(transport=httpx.MockTransport(failing_handler))

    report = await run_evaluation(cases, make_settings(), client=client)
    await client.aclose()

    assert report["intent_accuracy"] == 0.0  # 无法解析则意图命中率为 0
    assert len(report["failures"]) == 1  # 该用例进入失败清单
    assert "error" in report["failures"][0]  # 失败项记录错误信息


def test_model_regression_corpus_loads() -> None:
    """模型回归语料应被完整加载，且意图类型都在允许集合内。"""
    cases = load_cases(FIXTURE)

    assert len(cases) == 8  # 语料共 8 条用例
    assert all(
        case.intent in {"report_lost", "search_found_items", "get_item_detail", "claim_item"}
        for case in cases
    )  # 每条用例的意图都必须是四种合法意图之一


def test_skipped_report_shape() -> None:
    """skipped_report 应产出标准的"跳过"报告结构（status=skipped + 用例数）。"""
    report = skipped_report("corpus.jsonl", 8)

    assert report["status"] == "skipped"  # 标记为跳过状态
    assert report["cases"] == 8  # 保留语料用例数量，便于 CI 统计


def test_main_skips_without_api_key(
    capsys: pytest.CaptureFixture[str], monkeypatch: pytest.MonkeyPatch
) -> None:
    """未配置 LOST_FOUND_LLM_API_KEY 时，main() 应输出 skipped 而不调真实模型。

    capsys：pytest 内建夹具，用于捕获 print 输出；
    monkeypatch：pytest 内建夹具，用于临时替换环境变量与 sys.argv。
    关键点：必须在调用前清空 get_settings 的缓存，否则旧 env 仍可能被读到。
    """
    monkeypatch.setattr("sys.argv", ["model_eval", str(FIXTURE)])  # 伪造命令行参数
    monkeypatch.setenv("LOST_FOUND_LLM_API_KEY", "")  # 模拟未配置 API Key
    get_settings.cache_clear()  # 清掉 settings 缓存，让新 env 生效

    main()

    output = capsys.readouterr().out  # 读取 main() 打印的内容
    assert "skipped" in output  # 输出应包含 skipped 标记
