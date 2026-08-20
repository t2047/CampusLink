"""可复现的匹配排序评估：在固定语料上对比规则/文本嵌入/多模态三个版本。

本脚本在固定 JSONL 语料上评估匹配排序函数 rank_candidates 的三档实现变体：
  - rule       纯规则（剥除视觉指纹后仅用文本/类别/颜色/地点/时间相似度）；
  - embedding  规则 + 文本嵌入相似度（text_embedding=True）；
  - multimodal 规则 + 文本嵌入 + 视觉/跨模态嵌入（完整能力，保留视觉字段）。

指标计算使用 minimum_score=0.0，避免默认 0.35 阈值把相关项过滤掉而污染
Recall@K 等排序指标。`--variant all` 输出逐指标对比表和每个指标的胜者。
"""

from __future__ import annotations  # 延迟求值所有类型注解

import argparse  # 命令行参数解析（语料 / 变体 / min-score / 输出文件）
import json  # 逐行解析语料与报告序列化
from dataclasses import dataclass  # RankingCase 用例数据结构
from math import log2  # NDCG@K 计算的对数折扣
from pathlib import Path  # 语料与输出文件路径
from typing import Any  # 通用类型标注

from .matching import rank_candidates  # 被评估的候选匹配排序函数

# 待对比的三档实现变体：纯规则 / 规则+文本嵌入 / 完整多模态（含视觉与跨模态嵌入）
VARIANTS = ("rule", "embedding", "multimodal")
# 对比报告逐指标展示的排序指标：Recall@5、Precision@5、MRR、NDCG@5
_METRICS = ("recall_at_5", "precision_at_5", "mrr", "ndcg_at_5")


@dataclass(frozen=True)
class RankingCase:
    """单条匹配排序用例。

    query      —— 查询字典，含 item_name / colour / location 等，可选携带嵌入字段
                   （semantic_text_embedding / visual_embeddings 等）；
    candidates —— 候选物品列表，每个元素为后端返回的物品字典；
    relevant   —— 相关物品 id 集合（语料标注的"应当被召回"的项），统一为 str 与
                   排序结果的 item_id 对齐；
    language   —— 生成解释性文案的语言（zh / en），用于 rank_candidates 输出 reason。
    """

    query: dict[str, Any]
    candidates: list[dict[str, Any]]
    relevant: frozenset[str]
    language: str


def load_cases(path: Path) -> list[RankingCase]:
    """逐行读取 JSONL 评估语料。

    空行与 # 开头的注释行跳过；每行是一个用例对象。结构非法（query 非对象 /
    candidates 非数组）时抛出带行号的 ValueError，便于修正语料。
    """
    cases: list[RankingCase] = []
    # enumerate(start=1) 记录真实行号，供结构校验报错时定位
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue  # 跳过空行与注释行
        payload = json.loads(stripped)  # 解析单行 JSON 对象
        query = payload.get("query")
        candidates = payload.get("candidates")
        # 结构校验：query 必须是对象、candidates 必须是数组，否则语料本身写错
        if not isinstance(query, dict) or not isinstance(candidates, list):
            raise ValueError(f"line {line_number}: query/candidates must be an object and a list")
        # relevant 全部转成 str：rank_candidates 返回的 item_id 也是 str，比较口径统一
        relevant = frozenset(str(item_id) for item_id in payload.get("relevant", []))
        cases.append(
            RankingCase(
                query=query,
                candidates=candidates,
                relevant=relevant,
                language=str(payload.get("language", "zh")),  # 未标注时默认中文
            )
        )
    return cases


def evaluate(
    cases: list[RankingCase],
    variant: str,
    minimum_score: float = 0.0,
) -> dict[str, Any]:
    """对一组用例按指定变体运行排序并统计排序指标。

    入参：
        cases         —— 评估用例列表；
        variant       —— 变体名，须在 VARIANTS 内，否则抛 ValueError；
        minimum_score —— 传给 rank_candidates 的最低匹配分。默认 0.0：若用生产默认
                         0.35，相关项可能因低于阈值被过滤，进而污染 Recall@K / MRR
                         等"看排序"而非"看阈值"的指标。
    返回：
        该变体的指标报告：scored_cases（实际参与统计的用例数）、recall_at_5、
        precision_at_5、mrr、ndcg_at_5、mean_first_relevant_rank。
    """
    # 校验变体名合法，避免拼写错误悄悄跑出无意义的空报告
    if variant not in VARIANTS:
        raise ValueError(f"unknown variant {variant!r}; expected one of {VARIANTS}")
    # 每个用例各贡献一个指标值，最后取平均
    recall: list[float] = []
    precision: list[float] = []
    reciprocal: list[float] = []
    ndcg: list[float] = []
    first_ranks: list[int] = []  # 首个相关项排名（1 起），用于 mean_first_relevant_rank
    scored = 0  # 实际参与统计的用例数（无相关标注的用例不计）

    for case in cases:
        # 没有相关标注的用例无法计算召回/NDCG，直接跳过
        if not case.relevant:
            continue
        # 按变体准备：rule/embedding 剥掉视觉字段，embedding/multimodal 启用文本嵌入
        prepared, embedding_enabled = _prepare(case, variant)
        # 调用被测排序函数：结果按匹配分降序，取 Top-5（内部已切片）
        results = rank_candidates(
            prepared.query,
            prepared.candidates,
            minimum_score,
            prepared.language,
            text_embedding=embedding_enabled,
        )
        ranked_ids = [result.item_id for result in results]  # 只取排序后的 id 序列
        # 找出相关项命中的位置（index 从 0 开始；没出现在列表里说明在 Top-5 之外）
        hits = [index for index, item_id in enumerate(ranked_ids) if item_id in prepared.relevant]
        scored += 1
        recall.append(len(hits) / len(prepared.relevant))  # Recall@5 = 命中相关数 / 总相关数
        precision.append(len(hits) / 5)  # Precision@5 = 命中数 / 5（Top-5 恒为 5 项）
        reciprocal.append(1.0 / (hits[0] + 1) if hits else 0.0)  # RR：首个命中的倒数，无命中为 0
        ndcg.append(_ndcg_at_k(ranked_ids, prepared.relevant, 5))  # NDCG@5（对数折扣）
        if hits:
            first_ranks.append(hits[0] + 1)  # 记录首个相关项的 1 起排名

    return {
        "variant": variant,
        "scored_cases": scored,
        # _mean 对空列表返回 0.0（全部用例都无相关标注时）
        "recall_at_5": _mean(recall),
        "precision_at_5": _mean(precision),
        "mrr": _mean(reciprocal),
        "ndcg_at_5": _mean(ndcg),
        "mean_first_relevant_rank": _mean(first_ranks),
    }


def compare(results: list[dict[str, Any]]) -> dict[str, Any]:
    """汇总多个变体结果，逐指标找出胜者并计算相对 rule 基线的差值。

    要求结果中必须包含 rule 变体作为基线（否则无法衡量"增量收益"），
    缺失时抛 ValueError。返回按指标组织的字典：
        {metric: {"values": {variant: score}, "best": variant,
                  "delta_from_rule": {variant: diff}}}
    """
    # rule 是必备对照项：缺少它就没有"加了嵌入到底提升了多少"的参照
    if not any(result["variant"] == "rule" for result in results):
        raise ValueError("rule baseline is required for comparison")
    by_metric: dict[str, Any] = {}
    for metric in _METRICS:
        # 收集该指标下每个变体的得分
        scores = {result["variant"]: result[metric] for result in results}
        # 胜者 = 得分最高的变体（同分时取字典序靠前的）
        best = max(scores, key=lambda variant: scores[variant])
        baseline = scores["rule"]  # 基线得分
        by_metric[metric] = {
            "values": scores,  # 全量得分，供对比表渲染
            "best": best,  # 该指标胜出的变体
            # 相对基线的差值（保留 4 位），正值为改进、负值为退化
            "delta_from_rule": {
                variant: round(scores[variant] - baseline, 4) for variant in scores
            },
        }
    return by_metric


def _prepare(case: RankingCase, variant: str) -> tuple[RankingCase, bool]:
    """按变体准备用例与嵌入开关，返回 (用例, 是否启用文本嵌入)。

    变体语义：
      rule       —— 剥除视觉字段 + 关闭文本嵌入（纯规则基线）；
      embedding  —— 剥除视觉字段 + 开启文本嵌入（隔离视觉能力，单独看文本嵌入增量）；
      multimodal —— 保留视觉字段 + 开启文本嵌入（完整能力，视觉/跨模态也参与打分）。
    """
    if variant == "rule":
        return strip_visual(case), False
    if variant == "embedding":
        return strip_visual(case), True
    return case, True


def strip_visual(case: RankingCase) -> RankingCase:
    """生成剥掉视觉指纹字段的新用例，用于隔离"规则/文本嵌入"变体与视觉能力。

    只有 rule / embedding 变体需要调用：保证对比公平 —— 它们不应被
    visual_fingerprint / visualFingerprints 影响打分，否则无法判断嵌入增量。
    """
    def without_visual(value: dict[str, Any]) -> dict[str, Any]:
        # 从查询或候选字典中剔除两个视觉指纹键，其余键原样保留
        return {
            key: item
            for key, item in value.items()
            if key not in {"visual_fingerprint", "visualFingerprints"}
        }

    return RankingCase(
        query=without_visual(case.query),
        candidates=[without_visual(candidate) for candidate in case.candidates],
        relevant=case.relevant,  # 相关标注与语言保持不变
        language=case.language,
    )


def _ndcg_at_k(ranked_ids: list[str], relevant: frozenset[str], k: int) -> float:
    """计算 NDCG@K：归一化折损累计收益，衡量"越相关越靠前"的程度。

    DCG 对 Top-K 中命中的位置做 1/log2(rank+2) 折扣累加（排名越靠前贡献越大）；
    IDCG 是理想排序（所有相关项排在最前）下的 DCG；两者相除得到 0~1 归一值。
    无相关项（IDCG=0）时返回 0.0，避免除零。
    """
    # DCG：仅累加 Top-K 内命中相关项的折扣收益；index=0（第一名）贡献 1.0
    dcg = sum(
        1.0 / log2(index + 2) for index, item_id in enumerate(ranked_ids[:k]) if item_id in relevant
    )
    ideal_count = min(len(relevant), k)  # 理想情况下 Top-K 里最多能命中几个相关项
    idcg = sum(1.0 / log2(index + 2) for index in range(ideal_count)) if ideal_count else 0.0
    return dcg / idcg if idcg else 0.0


def _mean(values: list[float] | list[int]) -> float:
    """均值：保留 4 位小数；空列表返回 0.0。"""
    if not values:
        return 0.0
    return round(sum(values) / len(values), 4)


def _format_report(results: list[dict[str, Any]], comparison: dict[str, Any]) -> str:
    """生成人类可读的对比报告：指标 × 变体得分表 + 每指标相对 rule 的差值。"""
    # 表头：左对齐 metric 列（宽 22），每变体右对齐一列，最后是 best 胜者列
    header = f"{'metric':<22}" + "".join(f"{variant:>14}" for variant in VARIANTS) + f"{'best':>12}"
    lines = [header]
    # 每指标一行：各变体得分（4 位小数）+ 胜者名
    for metric in _METRICS:
        values = comparison[metric]["values"]
        best = comparison[metric]["best"]
        line = f"{metric:<22}" + "".join(f"{values[variant]:>14.4f}" for variant in VARIANTS)
        line += f"{best:>12}"
        lines.append(line)
    lines.append("")  # 与差值段落之间空一行
    # 差值段：每指标列出各变体相对 rule 的增量（带 + / - 符号，便于一眼看出升降）
    for metric in _METRICS:
        deltas = comparison[metric]["delta_from_rule"]
        lines.append(
            f"{metric}: delta vs rule -> "
            + ", ".join(f"{variant}={delta:+.4f}" for variant, delta in deltas.items())
        )
    return "\n".join(lines)


def main() -> None:
    """CLI 入口：python -m ...matching_eval <corpus.jsonl> [--variant rule|embedding|multimodal|all]。

    all 模式（默认）逐一评估三档变体并输出对比表；也可只跑单个变体。
    """
    parser = argparse.ArgumentParser(
        description="Evaluate matching ranking variants on a JSONL corpus"
    )
    parser.add_argument("corpus", type=Path)  # 必填：JSONL 匹配语料
    parser.add_argument("--variant", choices=[*VARIANTS, "all"], default="all")
    parser.add_argument("--min-score", type=float, default=0.0)  # 最低分，默认 0 不过滤
    parser.add_argument("--output", type=Path)  # 可选：报告 JSON 输出文件
    args = parser.parse_args()

    cases = load_cases(args.corpus)
    # all 模式跑全部变体，否则只跑指定变体
    variants = list(VARIANTS) if args.variant == "all" else [args.variant]
    results = [evaluate(cases, variant, args.min_score) for variant in variants]
    report = {
        "corpus": str(args.corpus),
        "cases": len(cases),
        "min_score": args.min_score,
        "results": results,
    }
    comparison = None
    # 只有 all 模式才有对比意义（需要 rule 基线），单变体不生成 comparison
    if args.variant == "all":
        comparison = compare(results)
        report["comparison"] = comparison
    # 序列化完整 JSON（保留中文、缩进 2）
    text = json.dumps(report, ensure_ascii=False, indent=2)
    # 有 --output 先把完整 JSON 写入文件
    if args.output:
        args.output.write_text(text + "\n", encoding="utf-8")
    if comparison is not None:
        # all 模式：先打印人读对比表，再提示完整 JSON 见 stdout / 文件
        print(_format_report(results, comparison))
        print("\nDetailed JSON written to stdout below (pipe to a file or use --output):")
    print(text)


# 仅当直接运行时执行 CLI 入口（被 import 时不做任何事，避免副作用）
if __name__ == "__main__":
    main()
