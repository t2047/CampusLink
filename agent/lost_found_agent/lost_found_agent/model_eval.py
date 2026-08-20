"""真实模型批量评估：质量、P95 延迟与费用估算。

本脚本用真实 LLM（由 LOST_FOUND_LLM_MODEL 指定）在 NLU 回归语料上做端到端
批量评估，一次性给出三方面结果：
  1. 质量 —— 意图准确率 / 字段完整率 / 误写率（误写 = 面对"只许读、不许写"的
     语料时模型仍发起了写类意图，如 report_lost / claim_item）；
  2. 延迟 —— 逐条顺序调用的平均 / P50 / P95 / P99 延迟（毫秒）；
  3. 成本 —— 累加每次调用的 token 用量，再按配置单价估算费用（USD）。

没有配置 `LOST_FOUND_LLM_API_KEY` 时输出 skipped 报告并退出码 0（不影响 CI）。
有 Key 时逐条顺序调用（不并发，避免并发扭曲延迟分位），复用 NLU 语料的语义
（意图准确率 / 字段完整率 / 误写率）并叠加延迟与 token 费用。
"""

from __future__ import annotations  # 延迟求值所有类型注解，兼容旧版本 Python

import argparse  # 命令行参数解析（语料路径 / 输出文件 / 重试次数）
import asyncio  # 驱动异步评估 run_evaluation
import json  # 报告序列化为 JSON
from math import ceil  # 百分位索引计算时向上取整
from pathlib import Path  # 语料与输出文件的路径类型
from time import perf_counter  # 高精度单调计时，用于测量每次 LLM 调用的延迟
from typing import Any  # 通用类型标注（语料/报告等宽松结构）

import httpx  # 异步 HTTP 客户端，可注入测试环境复用的 client

from .config import Settings, get_settings  # 配置模型与缓存化的配置读取入口
from .llm import LlmInterpreter, LlmTelemetry, LlmUnavailable, interpret_with_retry  # LLM 解释器、遥测记录、不可用异常与带重试的调用
from .nlu_eval import load_cases  # 复用 NLU 回归语料的加载函数，语义口径与规则引擎评估保持一致

# 写类意图集合：误写率评估时，若"只许读"语料被模型判成这两个意图之一即记为一次误写。
# report_lost 发布失物、claim_item 认领物品 —— 二者都会在后台产生写入，风险高于只读查询。
WRITE_INTENTS = {"report_lost", "claim_item"}


class _Collector:
    """轻量遥测收集器。

    通过 LlmInterpreter(on_complete=...) 挂钩，把每次成功模型调用的
    LlmTelemetry（含 input/output token 数）追加到 records，评估结束后
    据此汇总 token 总量与费用估算。
    """

    def __init__(self) -> None:
        self.records: list[LlmTelemetry] = []

    def record(self, telemetry: LlmTelemetry) -> None:
        # 作为 on_complete 回调，逐条收集一次调用的用量记录
        self.records.append(telemetry)


async def run_evaluation(
    cases: Any,
    settings: Settings,
    *,
    client: httpx.AsyncClient | None = None,
    attempts: int = 3,
) -> dict[str, Any]:
    """在全部语料上执行真实模型评估，返回含质量 / 延迟 / 费用指标的报告。

    入参：
        cases     —— 评估用例列表（来自 load_cases，每项含 message / intent /
                     fields / must_not_write）；
        settings  —— 应用配置（模型名、API Key、单价等）；
        client    —— 可选外部异步 HTTP 客户端，注入后由调用方管理其生命周期
                     （测试时便于 mock / 复用连接）；
        attempts  —— 单个用例的 LLM 重试次数，默认 3，与生产调用路径一致。
    返回：
        字典报告：status / model / attempts / cases / 三个质量指标 /
                 failures 明细 / latency_ms 各分位 / tokens / 费用估算。
    异常：
        每个用例内部自行捕获 LlmUnavailable 并记入 failures，不会向上抛出；
        finally 保证解释器（HTTP 连接池）被关闭。
    """
    # 遥测收集器：挂在 interpreter 的 on_complete 回调上，累计每次调用的 token 用量
    collector = _Collector()
    # 创建 LLM 解释器：可注入外部 client；on_complete 挂钩用于收集遥测
    interpreter = LlmInterpreter(settings, client, on_complete=collector.record)
    # 每次调用（含重试）的端到端延迟，单位毫秒
    latencies: list[float] = []
    # 记录失败/不合格用例的详细信息，供人工定位模型问题
    failures: list[dict[str, Any]] = []
    intent_hits = 0  # 意图判定正确的用例数
    field_hits = 0  # 字段值与期望一致的命中数
    expected_fields = 0  # 期望被抽取的字段总数（逐字段累加）
    mistaken_writes = 0  # 误写次数：被禁止写却判成写意图的用例数

    try:
        # 逐条顺序调用（刻意不并发）：并发会同时发出大量请求，既可能触发服务端
        # 限流，也会扭曲延迟分位统计 —— 本脚本测量的是单条端到端体验。
        for case in cases:
            started = perf_counter()  # 记录进入该用例的时刻，用于计算单条延迟
            try:
                # 带重试的模型调用：模型偶发输出不合规时内部自动重试 attempts 次，
                # 成功返回 LlmInterpretation（intent + fields + language）
                interpretation = await interpret_with_retry(
                    interpreter,
                    case.message,
                    {},  # 空共享上下文：评估时故意不给系统事实，避免引入外部变量
                    attempts=attempts,
                )
            except LlmUnavailable as exc:
                # 模型不可用 / 重试耗尽仍未产出可信输出：记一次失败。
                # 延迟按实际耗时计入（不能丢，否则 p95 失真）；
                # 错误原因进 failures，然后跳过本用例的质量指标统计。
                latencies.append((perf_counter() - started) * 1000.0)
                failures.append({"message": case.message, "error": str(exc)})
                continue
            # 调用成功：同样记录耗时（含重试所花时间，反映真实用户等待）
            latencies.append((perf_counter() - started) * 1000.0)

            # 取出模型预测的意图与字段；exclude_none=True 丢弃未填充的空字段
            predicted_intent = interpretation.intent
            predicted_fields = interpretation.fields.model_dump(exclude_none=True)
            # 意图比对：模型预测 == 语料期望
            intent_ok = predicted_intent == case.intent
            intent_hits += int(intent_ok)
            # 记录本次未命中 / 填错的字段名（用于失败明细）
            missing: list[str] = []
            # 字段完整性比对：逐字段比较期望值与模型输出
            for field, expected in case.fields.items():
                expected_fields += 1  # 每期望一个字段都计入分母
                if predicted_fields.get(field) == expected:
                    field_hits += 1  # 值完全一致才算命中
                else:
                    missing.append(field)
            # 误写检测：must_not_write 表示该语料"只应查询、不应写库"，
            # 若模型仍判成写类意图则记为一次误写（安全敏感，权重高）
            if case.must_not_write and predicted_intent in WRITE_INTENTS:
                mistaken_writes += 1
            # 只要意图错 / 有字段缺失或填错 / 触发误写，就把该用例的完整上下文
            # （消息原文、期望与预测意图、缺失字段、是否禁止写）记入 failures，
            # 便于事后对照语料逐条排查模型退化点。
            if (
                not intent_ok
                or missing
                or (case.must_not_write and predicted_intent in WRITE_INTENTS)
            ):
                failures.append(
                    {
                        "message": case.message,
                        "expected_intent": case.intent,
                        "predicted_intent": predicted_intent,
                        "missing_or_wrong_fields": missing,
                        "must_not_write": case.must_not_write,
                    }
                )
    finally:
        # 兜底关闭解释器（释放自建的 httpx 连接池）；外部注入的 client 不归本函数管理
        await interpreter.close()

    # 汇总所有成功调用的输入 / 输出 token 数（失败的调用不计入成本）
    input_tokens = sum(record.input_tokens for record in collector.records)
    output_tokens = sum(record.output_tokens for record in collector.records)
    total_cases = len(cases)  # 语料总用例数（含失败用例，作为质量指标分母）
    return {
        "status": "completed",
        "model": settings.lost_found_llm_model,  # 记录实际评估的模型名
        "attempts": attempts,
        "cases": total_cases,
        # 三个质量指标统一用"命中数 / 分母"计算；分母为 0 时由 _rate 兜底返回 0.0
        "intent_accuracy": _rate(intent_hits, total_cases),
        "field_completeness": _rate(field_hits, expected_fields),
        "mistaken_write_rate": _rate(mistaken_writes, total_cases),
        "failures": failures,
        # 延迟先升序排序再取分位：mean 为平均，p50 中位体验，p95 覆盖多数慢尾部，
        # p99 反映极端异常场景（LLM 超时 / 重试）。
        "latency_ms": {
            "mean": _round_mean(latencies),
            "p50": _percentile(sorted(latencies), 50),
            "p95": _percentile(sorted(latencies), 95),
            "p99": _percentile(sorted(latencies), 99),
        },
        "tokens": {"input": input_tokens, "output": output_tokens},
        # 费用估算：输入输出 token 分别折算成 USD
        "estimated_cost_usd": _estimate_cost(settings, input_tokens, output_tokens),
        # cost_configured 标记单价是否已配置：若两个单价都是 0，说明没填费用表，
        # 0 元费用只是"未计价"而非真的免费，报告里要显式告知。
        "cost_configured": (
            settings.lost_found_llm_input_cost_per_1m > 0
            or settings.lost_found_llm_output_cost_per_1m > 0
        ),
    }


def skipped_report(corpus: str, total_cases: int) -> dict[str, Any]:
    """生成"跳过"报告：未配置 LOST_FOUND_LLM_API_KEY 时使用。

    返回含 status=skipped 与原因说明的字典，调用方据此退出码 0，
    保证本地未配密钥时 CI 不会因评估失败而红。
    """
    return {
        "status": "skipped",
        "reason": "LOST_FOUND_LLM_API_KEY 未配置；未执行真实模型评估（exit 0）",
        "corpus": corpus,  # 记录语料路径，便于排查
        "cases": total_cases,  # 语料用例数
    }


def _rate(hits: int, total: int) -> float:
    """命中率：hits / total，保留 4 位小数；total 为 0（无样本）时返回 0.0 避免除零。"""
    return round(hits / total, 4) if total else 0.0


def _round_mean(values: list[float]) -> float:
    """平均值：保留 2 位小数；空列表返回 0.0。"""
    return round(sum(values) / len(values), 2) if values else 0.0


def _percentile(sorted_values: list[float], percentile: int) -> float:
    """对已升序排序的列表取分位值。

    索引取 ceil(p/100 * n) - 1（向上取整）而非四舍五入：
      - 能保证 p=100 时索引不越界（ceil(n)-1 = n-1，即最后一个元素）；
      - 对 p50 恰好居中，p95/p99 反映慢尾部。
    空列表返回 0.0。
    """
    if not sorted_values:
        return 0.0
    index = max(0, ceil(percentile / 100 * len(sorted_values)) - 1)
    return round(sorted_values[index], 2)


def _estimate_cost(settings: Settings, input_tokens: int, output_tokens: int) -> float:
    """费用估算（USD）：输入 / 输出 token 各自"除以 1M × 单价"后相加。

    单价字段 lost_found_llm_input_cost_per_1m / ..._output_cost_per_1m 表示
    "每百万 token 的价格"；未配置（0）时该项计为 0，结果保留 6 位小数。
    """
    cost = (
        input_tokens / 1_000_000 * settings.lost_found_llm_input_cost_per_1m
        + output_tokens / 1_000_000 * settings.lost_found_llm_output_cost_per_1m
    )
    return round(cost, 6)


def main() -> None:
    """CLI 入口：python -m ...model_eval <corpus.jsonl> [--output out.json] [--attempts N]。

    流程：解析参数 → 读取配置与语料 → 有 Key 则真实评估、无 Key 则输出 skipped 报告。
    """
    parser = argparse.ArgumentParser(
        description="Run real-model NLU batch evaluation (quality, latency, cost)"
    )
    parser.add_argument("corpus", type=Path)  # 必填：JSONL 评估语料路径
    parser.add_argument("--output", type=Path)  # 可选：报告 JSON 输出文件
    parser.add_argument(
        "--attempts",
        type=int,
        default=3,  # 默认 3，与生产调用路径（interpret_with_retry 默认值）保持一致
        help="LLM retry attempts per case; default matches production invoke path",
    )
    args = parser.parse_args()

    settings = get_settings()  # 读取配置（含 API Key / 模型名 / 单价）
    cases = load_cases(args.corpus)  # 加载 NLU 回归语料
    # 未配置 API Key：跳过真实评估，输出 skipped 报告并正常返回（exit 0）
    if not settings.lost_found_llm_api_key.strip():
        report = skipped_report(str(args.corpus), len(cases))
        _emit(report, args.output)
        return

    # 有 Key：asyncio.run 驱动异步评估；attempts 至少为 1，避免 0 导致一个用例都不尝试
    report = asyncio.run(run_evaluation(cases, settings, attempts=max(1, args.attempts)))
    _emit(report, args.output)


def _emit(report: dict[str, Any], output: Path | None) -> None:
    """把报告输出到 stdout（可选同时写文件）。

    stdout 侧：skipped 只打印原因；completed 先打印一行质量概览 + 一行延迟/费用
    概览（便于 grep 关键数字），再打印完整 JSON。全 JSON 始终打印，方便管道落盘。
    """
    # 序列化：ensure_ascii=False 保留中文，indent=2 便于人读
    text = json.dumps(report, ensure_ascii=False, indent=2)
    # 若指定 --output，把完整 JSON 追加换行后写入文件（UTF-8）
    if output:
        output.write_text(text + "\n", encoding="utf-8")
    if report["status"] == "skipped":
        print(report["reason"])
    else:
        # 质量指标一行概览：模型 / 用例数 / 意图准确率 / 字段完整率 / 误写率
        print(
            f"model={report['model']} cases={report['cases']} "
            f"intent_acc={report['intent_accuracy']:.4f} "
            f"field={report['field_completeness']:.4f} "
            f"write={report['mistaken_write_rate']:.4f}"
        )
        latency = report["latency_ms"]
        # 延迟分位 + token 用量 + 费用一行概览（configured 标记单价是否已配置）
        print(
            f"latency(ms) mean={latency['mean']:.2f} p50={latency['p50']:.2f} "
            f"p95={latency['p95']:.2f} p99={latency['p99']:.2f} "
            f"tokens in={report['tokens']['input']} out={report['tokens']['output']} "
            f"cost=${report['estimated_cost_usd']:.6f} (configured={report['cost_configured']})"
        )
    print(text)


# 仅当作为脚本直接运行时才执行 CLI 入口（被 import 时不做任何事，避免副作用）
if __name__ == "__main__":
    main()
