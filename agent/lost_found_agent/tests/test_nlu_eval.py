"""NLU 回归评估（nlu_eval）测试。

覆盖 `lost_found_agent.nlu_eval.evaluate_cases`：在固定语料
（fixtures/nlu_regression.jsonl）上跑规则式意图 / 字段解析，验证输出报告的
total / intent_accuracy / field_completeness / mistaken_write_rate / failures。
"""

from pathlib import Path

from lost_found_agent.nlu_eval import evaluate_cases, load_cases


def test_nlu_regression_fixture_generates_quality_report() -> None:
    """回归语料应产出质量达标报告：全部意图正确、字段完整、无误写、无失败。"""
    cases = load_cases(Path(__file__).parent / "fixtures" / "nlu_regression.jsonl")  # 加载 NLU 回归语料

    report = evaluate_cases(cases)  # 用规则引擎跑一遍批量评估

    assert report["total"] == 4  # 语料共 4 条用例
    assert report["intent_accuracy"] == 1.0  # 意图识别全部正确
    assert report["field_completeness"] >= 0.9  # 字段完整率至少 0.9（规则允许少量字段缺失）
    assert report["mistaken_write_rate"] == 0.0  # 没有在应只读的场景触发写操作
    assert report["failures"] == []  # 无失败用例
