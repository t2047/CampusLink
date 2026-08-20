"""失物招领 NLU 意图解析的回归评测（可复用脚本）。

背景
----
意图识别与字段抽取是规则层（rules.py）的两大核心能力，它们的改动可能在不经意间
破坏历史用例（例如“捡到”被误判为搜索、字段抽取丢值等）。本模块把一组
“输入消息 → 期望意图 + 期望字段”的回归语料加载进来，逐条用规则层真实预测
并对比，产出可读的量化指标报告。

用法
----
    python -m lost_found_agent.nlu_eval <corpus.jsonl> [--output report.json]

语料为每行一个 JSON 的 JSONL 文件；# 开头的行与空行被忽略。

产出的关键指标
--------------
- intent_accuracy：意图识别正确率
- field_completeness：字段抽取完整性（命中字段数 / 期望字段数）
- mistaken_write_rate：误触发写操作比例（must_not_write 用例被判成
  report_lost / claim_item 的占比，防止“纯查询被误报失”）
- failures：全部失败用例明细，方便定位回归点
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from pathlib import Path
from typing import Any

# 复用规则层的公开接口做“真实预测”：ALLOWED_CONTEXT_FIELDS 是允许进入上下文的
# 字段白名单，detect_explicit_intent 判意图、extract_fields 抽字段
from .rules import ALLOWED_CONTEXT_FIELDS, detect_explicit_intent, extract_fields


# frozen=True 使每条用例不可变（可哈希化），避免评测过程中被意外改写
@dataclass(frozen=True)
class EvaluationCase:
    """一条回归用例：给定消息，期望模型识别出的意图与抽取出的字段。

    message：用户输入消息（与真实对话一致）
    intent：期望命中的意图（rules.detect_explicit_intent 的返回值之一；
            规则层未识别时会在预测端回退到 search_found_items）
    fields：期望抽取出的字段名 → 期望值（与 rules.extract_fields 的返回对齐）
    must_not_write：True 表示“绝不能触发写操作”——用于守卫纯查询/纯搜索场景，
                    防止把查询误判成报失（report_lost）或认领（claim_item）
    """

    message: str
    intent: str
    fields: dict[str, Any]
    must_not_write: bool = False


def load_cases(path: Path) -> list[EvaluationCase]:
    """从 JSONL 语料文件加载全部回归用例。

    文件约定：每行一个 JSON 对象；空行或 # 开头的行（注释）被跳过。
    期望 JSON 结构：{"message": "我丢了一个黑色钱包", "intent": "report_lost",
                     "fields": {"item_name": "黑色钱包"}, "must_not_write": false}

    :param path: 语料文件路径（UTF-8 编码）
    :return: 解析出的用例列表；fields 中的键会按白名单过滤，
             仅保留 ALLOWED_CONTEXT_FIELDS 内的字段
    """
    cases: list[EvaluationCase] = []
    # enumerate 从 1 开始编号物理行，便于错误信息精确指向出错的哪一行
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        # 空行与注释行（以 # 开头）直接跳过，不参与解析
        if not stripped or stripped.startswith("#"):
            continue
        # 该行是一个完整 JSON 对象
        payload = json.loads(stripped)
        fields = payload.get("fields", {})
        # fields 必须是对象；类型不符时给出带行号的明确错误，便于修语料
        if not isinstance(fields, dict):
            raise ValueError(f"line {line_number}: fields must be an object")
        cases.append(
            EvaluationCase(
                # str() 保证 message/intent 一定被转成字符串（避免数字等脏值）
                message=str(payload["message"]),
                intent=str(payload["intent"]),
                # 只保留白名单内的字段：语料里写的键若不在规则层上下文白名单，
                # 说明该键永远不可能被抽取，直接忽略以免误判失败
                fields={
                    key: value for key, value in fields.items() if key in ALLOWED_CONTEXT_FIELDS
                },
                # must_not_write 缺省为 False（布尔化，容忍非布尔真值）
                must_not_write=bool(payload.get("must_not_write", False)),
            )
        )
    return cases


def evaluate_cases(cases: list[EvaluationCase]) -> dict[str, Any]:
    """对全部用例执行规则层预测并统计回归指标。

    每个用例都用与线上完全相同的两条规则函数预测：先 detect_explicit_intent
    判意图（未显式识别则回退默认 search_found_items），再以该意图调
    extract_fields 抽字段——保证评测口径与真实运行一致。

    :param cases: 从语料加载的用例列表
    :return: 汇总报告 dict，含 total / intent_accuracy / field_completeness /
             mistaken_write_rate / failures
    """
    total = len(cases)
    intent_hits = 0          # 意图识别命中数
    expected_fields = 0      # 所有用例期望抽取的字段总数（分母）
    field_hits = 0           # 字段值抽取正确数（分子）
    mistaken_writes = 0      # 误触发写操作数（must_not_write 却判成写意图）
    failures: list[dict[str, Any]] = []   # 失败用例明细

    for case in cases:
        # 与线上一致：规则层没识别出明确意图时回退到默认搜索意图
        predicted_intent = detect_explicit_intent(case.message) or "search_found_items"
        # 用预测出的意图去抽字段——意图不同则抽取分支也不同（如报失/拾获的物品名
        # 正则不同），因此必须先定意图再抽字段
        predicted_fields = extract_fields(case.message, predicted_intent)
        intent_ok = predicted_intent == case.intent
        intent_hits += int(intent_ok)

        missing_fields: list[str] = []
        # 逐字段对比期望值：字段缺失或抽取值不一致都计入缺失列表
        for field, expected in case.fields.items():
            expected_fields += 1
            if predicted_fields.get(field) == expected:
                field_hits += 1
            else:
                missing_fields.append(field)

        # 写操作意图集合：报失与认领都属于“写”；must_not_write 用例一旦命中
        # 就是严重回归（把纯查询误当写操作执行）
        is_write = predicted_intent in {"report_lost", "claim_item"}
        if case.must_not_write and is_write:
            mistaken_writes += 1

        # 任一维度不达标即记为失败，并保留完整上下文便于定位
        if not intent_ok or missing_fields or (case.must_not_write and is_write):
            failures.append(
                {
                    "message": case.message,
                    "expected_intent": case.intent,
                    "predicted_intent": predicted_intent,
                    # 缺失或抽错值的字段名列表
                    "missing_or_wrong_fields": missing_fields,
                    "must_not_write": case.must_not_write,
                }
            )

    return {
        "total": total,
        # 意图正确率；空语料时定义为 0.0 避免除零
        "intent_accuracy": round(intent_hits / total, 4) if total else 0.0,
        # 字段完整度：命中字段数 / 期望字段数；无期望字段视为 1.0（空集恒真）
        "field_completeness": round(field_hits / expected_fields, 4) if expected_fields else 1.0,
        # 误写率；空语料时定义为 0.0
        "mistaken_write_rate": round(mistaken_writes / total, 4) if total else 0.0,
        "failures": failures,
    }


def main() -> None:
    """命令行入口：读取语料、跑评测、输出 JSON 报告。

    用法：python -m lost_found_agent.nlu_eval <corpus.jsonl> [--output report.json]
    不指定 --output 时报告直接打印到 stdout。
    """
    parser = argparse.ArgumentParser(description="Evaluate Lost & Found NLU regression corpus")
    # 位置参数：语料文件路径（必填）
    parser.add_argument("corpus", type=Path)
    # 可选参数：报告输出文件路径（缺省打印到 stdout）
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    # 一条链完成“加载 → 评测”
    report = evaluate_cases(load_cases(args.corpus))
    # ensure_ascii=False 保留中文原文，indent=2 使报告可读
    text = json.dumps(report, ensure_ascii=False, indent=2)
    # 给了 --output 就写入文件（末尾补换行），否则直接打印
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
    else:
        print(text)


# 作为脚本直接运行时才执行 main()；被 import 时（如回归测试）不触发
if __name__ == "__main__":
    main()
