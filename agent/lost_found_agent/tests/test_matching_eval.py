"""匹配排序（matching_eval）回归评估测试。

覆盖 `lost_found_agent.matching_eval` 模块，在固定 JSONL 语料
（fixtures/matching_regression.jsonl）上对比三种排序变体：
- rule：纯规则匹配（去掉视觉信息）；
- embedding：规则匹配 + 文本向量召回；
- multimodal：规则匹配 + 文本向量 + 视觉指纹。

验证的指标有 Recall@5 / Precision@5 / MRR / NDCG@5，以及：
- 语料加载只保留合法用例，语言字段（zh/en）与 query 结构正确；
- 多模态在视觉分辨场景下指标不劣于、且 MRR 优于纯规则；
- compare() 要求 rule 基线、能给出每项指标的胜者；
- 命令行入口支持单变体与全量对比两种模式；
- evaluate() 对未知变体抛出 ValueError。

策略：直接调用模块函数验证核心逻辑，再用 subprocess 运行
`python -m lost_found_agent.matching_eval` 校验 CLI 输出。
"""

import subprocess
import sys
from pathlib import Path

from lost_found_agent.matching_eval import compare, evaluate, load_cases

# 评估语料：每行一条 JSON（空行 / # 开头的注释行被忽略），
# 包含 query / candidates / relevant / language 字段，由 load_cases 解析。
FIXTURE = Path(__file__).parent / "fixtures" / "matching_regression.jsonl"


def test_corpus_loads_all_cases() -> None:
    """语料文件应被完整加载：10 条用例、语言字段只允许 zh/en、query 必须是 dict。"""
    cases = load_cases(FIXTURE)

    assert len(cases) == 10  # 回归语料共有 10 条用例
    assert all(case.language in {"zh", "en"} for case in cases)  # 语言只允许中文或英文
    assert all(isinstance(case.query, dict) for case in cases)  # 查询必须为结构化的 dict


def test_multimodal_beats_rule_on_visual_discrimination() -> None:
    """视觉分辨用例中，多模态变体应优于纯规则；纯文本嵌入与规则持平。

    该语料刻意包含"仅靠视觉才能区分"的候选（例如同一物品不同颜色），
    因此加入视觉指纹的多模态应当把相关项排到第一位。
    """
    cases = load_cases(FIXTURE)

    # 分别用三种变体在同一个语料上评估（minimum_score 默认 0.0，不截断相关项）
    rule = evaluate(cases, "rule")
    embedding = evaluate(cases, "embedding")
    multimodal = evaluate(cases, "multimodal")

    assert rule["scored_cases"] == 9  # 10 条里 9 条含相关项（1 条无 relevant 被跳过）
    assert multimodal["mrr"] == 1.0  # 多模态每次都能把第一个相关项排到首位
    assert rule["mrr"] < multimodal["mrr"]  # 纯规则在视觉区分上不如多模态
    assert embedding["mrr"] == rule["mrr"]  # 文本嵌入不提供视觉信号，因此与规则 MRR 一致
    assert multimodal["ndcg_at_5"] >= rule["ndcg_at_5"]  # 排序质量不劣于规则
    assert multimodal["recall_at_5"] == 1.0  # 多模态在前 5 位召回所有相关项


def test_compare_picks_multimodal_as_mrr_winner() -> None:
    """compare() 应正确选出 MRR 胜者（多模态），并计算相对 rule 基线的提升。"""
    cases = load_cases(FIXTURE)
    # 先对三种变体分别求指标，再交给 compare() 做逐指标对比
    results = [evaluate(cases, variant) for variant in ("rule", "embedding", "multimodal")]

    comparison = compare(results)

    assert comparison["mrr"]["best"] == "multimodal"  # MRR 的胜者是多模态
    assert comparison["mrr"]["delta_from_rule"]["multimodal"] > 0  # 多模态相对规则有正提升
    assert "recall_at_5" in comparison  # 对比报告需包含 recall_at_5 指标
    assert "ndcg_at_5" in comparison  # 对比报告需包含 ndcg_at_5 指标


def test_compare_requires_rule_baseline() -> None:
    """缺少 rule 基线时 compare() 必须报 ValueError，防止指标对比失去参照。"""
    cases = load_cases(FIXTURE)

    # 只传入 embedding 一个结果，没有 rule 基线
    try:
        compare([evaluate(cases, "embedding")])
    except ValueError as exc:
        assert "rule baseline" in str(exc)  # 错误信息应指明缺少 rule 基线
    else:
        raise AssertionError("expected ValueError without rule baseline")


def test_cli_supports_single_variant() -> None:
    """CLI 支持 --variant embedding 只跑单变体，且不输出对比段。"""
    # 用当前解释器以模块方式运行 CLI：语料路径 + 指定单变体
    completed = subprocess.run(  # noqa: S603 - 固定解释器 + 固定参数，非不可信输入
        [
            sys.executable,
            "-m",
            "lost_found_agent.matching_eval",
            str(FIXTURE),
            "--variant",
            "embedding",
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    assert '"variant": "embedding"' in completed.stdout  # 报告只含 embedding 结果
    assert '"comparison"' not in completed.stdout  # 单变体模式不生成对比表


def test_cli_defaults_to_all_with_comparison() -> None:
    """不带 --variant 时默认跑全部三种变体，并输出逐指标对比与胜者。"""
    completed = subprocess.run(  # noqa: S603 - 固定解释器 + 固定参数，非不可信输入
        [
            sys.executable,
            "-m",
            "lost_found_agent.matching_eval",
            str(FIXTURE),
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    assert '"variant": "rule"' in completed.stdout  # 包含规则变体结果
    assert '"variant": "multimodal"' in completed.stdout  # 包含多模态变体结果
    assert '"comparison"' in completed.stdout  # 全量模式下生成 comparison 段
    assert '"best"' in completed.stdout  # 对比段内给出每个指标的胜者


def test_cli_single_rule_variant_no_comparison() -> None:
    """--variant rule 只跑规则变体：不出现 embedding 结果，也不输出对比段。"""
    completed = subprocess.run(  # noqa: S603 - 固定解释器 + 固定参数，非不可信输入
        [
            sys.executable,
            "-m",
            "lost_found_agent.matching_eval",
            str(FIXTURE),
            "--variant",
            "rule",
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    assert '"variant": "rule"' in completed.stdout  # 报告只含 rule 结果
    assert '"embedding"' not in completed.stdout  # 其它变体不应出现
    assert '"comparison"' not in completed.stdout  # 单变体不生成对比段


def test_evaluate_rejects_unknown_variant() -> None:
    """evaluate() 遇到未知变体名必须抛出 ValueError（fail-fast，防止拼写错误静默通过）。"""
    cases = load_cases(FIXTURE)

    # 传入 VARIANTS 之外的变体名
    try:
        evaluate(cases, "unknown")
    except ValueError as exc:
        assert "unknown variant" in str(exc)  # 错误信息应点名未知变体
    else:
        raise AssertionError("expected ValueError for unknown variant")
