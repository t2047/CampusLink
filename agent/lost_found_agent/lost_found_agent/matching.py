"""候选匹配与排序模块：对检索到的候选物品做结构化、可解释的重排打分。

职责
----
- 融合多路证据：预训练 Embedding（文本/图片/图文跨模态）与规则相似度
  （类别、颜色、地点、日期时间）按权重加权，缺失字段自动归一化权重。
- 可解释输出：为每个命中候选生成 match_score、理由文案列表、分量明细
  score_breakdown 与 matching_mode（pretrained_multimodal / pretrained_image /
  pretrained_text / baseline），供面板展示与调试回溯。
- 自动降级：Embedding 服务或向量不可用时，退回本地确定性文本相似度、
  颜色直方图指纹与结构化规则，保证基线可用（matching_mode=baseline）。

被谁 import / 与哪些模块协作
----------------------------
- rules.py：聊天检索流程调用 rank_candidates() 对后端返回的候选重排。
- models.py：输出契约 MatchResult / SearchRequest，本模块负责填充匹配字段。
- pretrained.py：PretrainedEmbeddingClient.enrich_query() 把 semantic_text_embedding、
  cross_modal_text_embedding 与 _calibration 标定参数注入 query，本模块消费；
  该服务不可用时自动降级到规则路径。
- embeddings.py：本地确定性哈希文本向量与颜色直方图视觉指纹（离线基线），
  text_similarity()/视觉降级路径会借用其 embedding_similarity/visual_similarity。
- matching_eval.py / tests：以 rank_candidates()/score_candidate() 为入口做
  回归评测与单元测试。
"""

# ── 标准库导入 ──────────────────────────────────────────────────
import base64   # 解码 base64 编码的 Embedding 向量（见 _decode_vector）
import math     # math.isfinite 校验向量分量非 NaN/Inf
import re       # 颜色形式词边界匹配、时间正则、文本归一化
import struct   # 小端 float32 数组解包（向量解码）
from dataclasses import dataclass   # 颜色组数据类 ColourGroup
from datetime import date           # 日期差计算（date_similarity）
from difflib import SequenceMatcher # 序列相似度（文本/短文本匹配）
from typing import Any              # 字典字段的宽类型注解

from .models import MatchResult     # 对外契约模型：命中候选的封装

# 各分量在加权总分中的权重，权重之和为 1.0。来源见 README.md「候选重排权重」：
# E5 文本 25%、CLIP 图片 20%、可选图文跨模态 10%、类别 20%、地点 10%、
# 日期与时间 10%、颜色 5%。缺失字段不参与计算，实际生效权重会在
# _score_candidate_detailed 中按已激活分量重新归一化。
WEIGHTS = {
    "text": 0.25,          # 文字描述相似度（预训练语义向量或规则文本相似度）
    "visual": 0.20,        # 图片内容相似度（预训练图像 Embedding）
    "cross_modal": 0.10,   # 图文跨模态：文字与图片之间的匹配（如"描述匹配图片"）
    "category": 0.20,      # 物品类别是否一致（硬性 0/1 判断）
    "location": 0.10,      # 地点接近程度（短文本相似度）
    "temporal": 0.10,      # 日期与时间接近程度
    "colour": 0.05,        # 颜色相似度（canonical 颜色集合判同色）
}

# 预训练 Embedding 相似度的默认标定区间（下限, 上限）。
# 向量相似度通常偏高且分布集中，直接当分量分用会过度饱和；_calibrate 把原始值
# 线性映射到 [0,1]：低于下限按 0、高于上限按 1、中间线性拉伸。
# 当请求未携带 _calibration（例如 Embedding 服务未启用/未注入）时使用这组默认值；
# 各分量的实际区间由 pretrained.py 依据服务配置注入到 query["_calibration"]。
DEFAULT_CALIBRATION = {
    "text": (0.65, 0.95),        # 文本语义相似度常见落在 0.65~0.95
    "visual": (0.50, 0.95),      # 图像相似度常见落在 0.50~0.95
    "cross_modal": (0.15, 0.40), # 图文跨模态相似度常见落在 0.15~0.40（跨模态天然偏低）
}

# 纯 ASCII 表面形式用词边界匹配；含 CJK 的表面形式用子串匹配（避免 "black"
# 误命中 "backpack"、"red" 误命中 "redemption"）。
# 该正则只用于判断一个颜色形式是否含 ASCII 字母/数字：搜索非空即视为"纯 ASCII 形式"。
COLOUR_FORM_ASCII_PATTERN = re.compile(r"[a-z0-9]")


# 冻结的不可变数据类：定义一个"归一化颜色分组"。
@dataclass(frozen=True)
class ColourGroup:
    """一组同义/跨语言颜色：code 为 canonical 标识，en/zh 为展示形式。

    与后端 {@code ColourNormalizer} 保持同步（后端新增颜色时两端一起改）。
    """

    code: str            # canonical 标识，如 "BLUE"；颜色相似度据此比对
    en: str              # 英文展示形式（生成文案/给用户展示用）
    zh: str              # 中文展示形式
    forms: tuple[str, ...]  # 该组包含的所有表面形式（英/中同义词，见 COLOUR_GROUPS 注释）


# 保守合并：只合并跨语言 + 明确同义词（gray/grey、ivory/cream→White、
# navy/dark blue→Blue、gold/golden→Gold）；silver 与 grey、gold 与 yellow
# 保持分开，避免近义色误召回。单字中文形式（白/黑/蓝…）故意不收录，
# 防止子串命中 "明白"、"黑板" 等无关词。
# 下面是全局颜色表：按 canonical 颜色分组列出所有英/中表面形式，
# colour_codes() 据此把自由文本归一化为颜色 code 集合。修改时须与后端
# ColourNormalizer（Java）同步维护，避免两端判色不一致。
COLOUR_GROUPS: tuple[ColourGroup, ...] = (
    ColourGroup(
        "WHITE",
        "White",
        "白色",
        ("white", "ivory", "cream", "白色", "米白", "乳白", "纯白", "象牙白", "奶白"),
    ),
    ColourGroup("BLACK", "Black", "黑色", ("black", "charcoal", "黑色", "纯黑", "墨黑", "乌黑")),
    ColourGroup("GREY", "Grey", "灰色", ("grey", "gray", "灰色", "银灰", "浅灰", "深灰")),
    ColourGroup(
        "BLUE",
        "Blue",
        "蓝色",
        (
            "blue",
            "navy",
            "navy blue",
            "dark blue",
            "light blue",
            "sky blue",
            "cyan",
            "teal",
            "azure",
            "蓝色",
            "深蓝",
            "浅蓝",
            "天蓝",
            "藏蓝",
            "宝蓝",
            "淡蓝",
            "湖蓝",
        ),
    ),
    ColourGroup(
        "RED",
        "Red",
        "红色",
        (
            "red",
            "maroon",
            "crimson",
            "scarlet",
            "红色",
            "深红",
            "浅红",
            "酒红",
            "枣红",
            "朱红",
            "大红",
        ),
    ),
    ColourGroup(
        "GREEN",
        "Green",
        "绿色",
        (
            "green",
            "olive",
            "emerald",
            "jade",
            "绿色",
            "深绿",
            "浅绿",
            "翠绿",
            "墨绿",
            "草绿",
            "橄榄绿",
        ),
    ),
    ColourGroup("YELLOW", "Yellow", "黄色", ("yellow", "amber", "黄色", "杏黄", "米黄", "淡黄")),
    ColourGroup("GOLD", "Gold", "金色", ("gold", "golden", "金色", "金黄", "金黄色")),
    ColourGroup("SILVER", "Silver", "银色", ("silver", "银色", "银白")),
    ColourGroup(
        "PURPLE",
        "Purple",
        "紫色",
        ("purple", "violet", "lavender", "紫色", "淡紫", "紫罗兰"),
    ),
    ColourGroup("PINK", "Pink", "粉色", ("pink", "粉色", "粉红", "桃红", "浅粉")),
    ColourGroup("ORANGE", "Orange", "橙色", ("orange", "橙色", "橘色", "桔色")),
    ColourGroup(
        "BROWN",
        "Brown",
        "棕色",
        ("brown", "tan", "beige", "bronze", "棕色", "褐色", "咖啡色", "茶色", "卡其色", "驼色"),
    ),
    ColourGroup("TRANSPARENT", "Transparent", "透明", ("transparent", "clear", "透明", "无色")),
)  # 共 14 个 canonical 颜色组：白/黑/灰/蓝/红/绿/黄/金/银/紫/粉/橙/棕 + 透明


def contains_colour_form(text: str, form: str) -> bool:
    """颜色表面形式是否出现在 text 中（text 需已 lowercase）。

    纯 ASCII 形式用词边界正则；含 CJK 的形式用子串匹配。
    """
    # 形式含 ASCII 字母/数字 → 视为纯 ASCII 形式，用词边界正则匹配：
    # (?<![a-z0-9]) 和 (?![a-z0-9]) 保证 form 两侧都不是字母数字，
    # 从而 "black" 不会命中 "backpack"；re.escape 转义形式中的正则元字符。
    if COLOUR_FORM_ASCII_PATTERN.search(form):
        return re.search(rf"(?<![a-z0-9]){re.escape(form)}(?![a-z0-9])", text) is not None
    # 含 CJK 的形式直接子串匹配：中文没有空格分词，词边界概念不适用。
    return form in text


def colour_codes(value: str) -> frozenset[str]:
    """返回 value 中命中的 canonical 颜色 code 集合；空值/未命中返回空集。

    复合色如 "blue lid black bottle" → {"BLUE", "BLACK"}。
    """
    # 空串/None 直接返回空集：无颜色信息，不参与判色。
    if not value:
        return frozenset()
    text = value.lower()  # 统一转小写，便于 ASCII 形式匹配
    # 遍历全部颜色组，只要组内任一表面形式命中文本，就收集该组的 canonical code；
    # frozenset 去重，后续用集合交集在 colour_similarity 中判断是否同色。
    return frozenset(
        group.code
        for group in COLOUR_GROUPS
        if any(contains_colour_form(text, form) for form in group.forms)
    )


def colour_similarity(left: str, right: str) -> float:
    """颜色相似度：两侧都命中 canonical 颜色时按 code 集合判同色（white↔白色
    → 1.0、white vs black → 0.0）；任一侧未命中则回退 short_text_similarity，
    保留未知颜色/拼写变体的旧行为。
    """
    left_codes = colour_codes(left)
    right_codes = colour_codes(right)
    # 两侧都识别出颜色：集合有交集即同色（得 1.0），无交集即异色（得 0.0）。
    # 用硬性判断而非文本相似度，保证"白色/white"这类跨语言形式仍判为同色。
    if left_codes and right_codes:
        return 1.0 if left_codes & right_codes else 0.0
    # 任一侧没有可识别的颜色（未知颜色/新拼写变体）：回退到通用短文本相似度，
    # 保留旧版行为，避免这类输入直接得 0 而误伤本应匹配的候选。
    return short_text_similarity(left, right)


# ── 公开入口：候选重排 ──────────────────────────────────────────
# 对候选列表逐一打分、过滤并排序，返回 Top-5 的 MatchResult。
# 由 rules.py（聊天检索）与 models.SearchRequest（Browse 以图搜物）调用。
# 入参 query 为后端检索的匹配条件（含 Embedding 服务注入的向量字段），
# candidates 为检索命中的报告列表（来自 Spring Boot 后端分页 content），
# minimum_score 为命中阈值，language 决定理由文案语言，text_embedding
# 控制本地文本规则路径是否附加哈希向量信号。
def rank_candidates(
    query: dict[str, Any],
    candidates: list[dict[str, Any]],
    minimum_score: float,
    language: str,
    *,
    text_embedding: bool = True,
) -> list[MatchResult]:
    results: list[MatchResult] = []
    for candidate in candidates:
        # 计算单个候选的加权总分、理由、分量明细与匹配模式。
        score, reasons, breakdown, mode = _score_candidate_detailed(
            query, candidate, language, text_embedding=text_embedding
        )
        # 低于最低分数线直接丢弃（minimum_score 由调用方按场景给定）。
        if score < minimum_score:
            continue
        # 把后端报告字段转成对外契约模型 MatchResult，全部兜底为 str 默认值。
        results.append(
            MatchResult(
                item_id=str(candidate["id"]),
                report_type=str(candidate.get("reportType", "FOUND")),
                item_name=str(candidate.get("itemName", "")),
                category=str(candidate.get("category", "OTHER")),
                description=str(candidate.get("description", "")),
                colour=(str(candidate["colour"]) if candidate.get("colour") else None),
                location=str(candidate.get("location", "")),
                event_date=str(candidate.get("eventDate", "")),
                time_description=str(candidate["timeDescription"])
                if candidate.get("timeDescription")
                else None,
                image_urls=[str(url) for url in candidate.get("imageUrls", []) if url],
                status=str(candidate.get("status", "OPEN")),
                match_score=round(score, 4),       # 分数保留 4 位小数供展示/排序
                match_reason=reasons,              # 可解释的理由文案列表
                score_breakdown=breakdown,         # 各分量得分明细
                matching_mode=mode,                # pretrained_multimodal/image/text 或 baseline
            )
        )
    # 按分数降序排序并只保留前 5 名（topK 契约见 test_contract.py）。
    return sorted(results, key=lambda result: result.match_score, reverse=True)[:5]


# ── 轻量打分入口 ───────────────────────────────────────────────
# 只需 (总分, 理由) 的场景（如规则引擎对单个候选做阈值判断/排序），
# 丢弃明细与匹配模式；参数含义同 _score_candidate_detailed。
# 返回 float 为加权总分，list[str] 为理由文案列表。
def score_candidate(
    query: dict[str, Any],
    candidate: dict[str, Any],
    language: str,
    *,
    text_embedding: bool = True,
) -> tuple[float, list[str]]:
    score, reasons, _breakdown, _mode = _score_candidate_detailed(
        query, candidate, language, text_embedding=text_embedding
    )
    return score, reasons


# ── 核心打分 ────────────────────────────────────────────────────
# 把多路证据合成一个加权总分，并附带理由、明细与匹配模式。
# 返回 (总分 float, 理由 list[str], 分量明细 dict[str,float], 匹配模式 str)。
# 每个激活分量记作 (分量名, 权重, 得分)，缺失字段不入列；
# 最终总分 = Σ(权重×得分) / Σ(激活权重)，实现缺失字段的自动归一化。
def _score_candidate_detailed(
    query: dict[str, Any],
    candidate: dict[str, Any],
    language: str,
    *,
    text_embedding: bool = True,
) -> tuple[float, list[str], dict[str, float], str]:
    components: list[tuple[str, float, float]] = []
    # 拼接查询文本（物品名/关键词/描述）与候选文本（物品名/描述），供文本分量使用。
    query_text = " ".join(
        str(query.get(field, "")) for field in ("item_name", "keyword", "description")
    ).strip()
    candidate_text = " ".join(
        str(candidate.get(field, "")) for field in ("itemName", "description")
    ).strip()
    # ── 文本分量 ─────────────────────────────────────────────────
    # 优先用预训练语义向量（pretrained.py 注入的 semantic_text_embedding）；
    # 解码成功且维度一致才用（返回非 None），否则回退到规则文本相似度。
    pretrained_text = _embedding_similarity(
        query.get("semantic_text_embedding"), candidate.get("semanticTextEmbedding")
    )
    if pretrained_text is not None:
        # 预训练向量可用：原始相似度经 _calibrate 线性标定到 [0,1] 再入列。
        components.append(
            (
                "text",
                WEIGHTS["text"],
                _calibrate(pretrained_text, query, "text"),
            )
        )
    elif query_text:
        # 无向量时回退本地文本相似度（序列/Jaccard/包含度/哈希向量取最高）。
        components.append(
            (
                "text",
                WEIGHTS["text"],
                text_similarity(query_text, candidate_text, text_embedding=text_embedding),
            )
        )
    # ── 类别分量 ─────────────────────────────────────────────────
    # 查询带类别时做硬性相等判断：类别一致得 1.0，不一致得 0.0（布尔转 float）。
    if query.get("category"):
        components.append(
            (
                "category",
                WEIGHTS["category"],
                float(str(query["category"]) == str(candidate.get("category"))),
            )
        )
    # ── 颜色分量 ─────────────────────────────────────────────────
    # 按 canonical 颜色集合判同色（white↔白色 得 1.0）；两侧都可识别才用硬性判断。
    if query.get("colour"):
        components.append(
            (
                "colour",
                WEIGHTS["colour"],
                colour_similarity(str(query["colour"]), str(candidate.get("colour", ""))),
            )
        )
    # ── 地点分量 ─────────────────────────────────────────────────
    # 地点是自由文本，用短文本相似度（包含/序列相似度）度量"接近"程度。
    if query.get("location"):
        components.append(
            (
                "location",
                WEIGHTS["location"],
                short_text_similarity(str(query["location"]), str(candidate.get("location", ""))),
            )
        )
    # ── 时间分量 ─────────────────────────────────────────────────
    # event_date 兼容两种命名（event_date / date），把日期 + 时间段（如"下午"）
    # 合成一个时间分：日期权重 0.8、时间段权重 0.2。
    event_date = query.get("event_date") or query.get("date")
    if event_date:
        components.append(
            (
                "temporal",
                WEIGHTS["temporal"],
                temporal_similarity(
                    str(event_date),
                    str(candidate.get("eventDate", "")),
                    str(query.get("time_description") or query.get("timeDescription") or ""),
                    str(candidate.get("timeDescription") or ""),
                ),
            )
        )
    # ── 图片/视觉分量 ────────────────────────────────────────────
    # 优先用预训练图像 Embedding（visualEmbeddings 向量两两比对取最优）；
    # 不可用时降级到颜色直方图指纹（visual_fingerprint）。
    pretrained_visual = _pretrained_visual_similarity(query, candidate)
    if pretrained_visual is not None:
        components.append(
            ("visual", WEIGHTS["visual"], _calibrate(pretrained_visual, query, "visual"))
        )
    else:
        visual_value = _visual_similarity(query, candidate)
        if visual_value is not None:
            # 颜色直方图只是降级信号，保持旧版 10% 权重，避免同色错误候选压过文本。
            components.append(("visual", 0.10, visual_value))
    # ── 图文跨模态分量 ───────────────────────────────────────────
    # 用文本 Embedding 与图片 Embedding 交叉比对，衡量"文字描述是否匹配图片"；
    # 仅当查询或候选之一带跨模态文本向量、且另一侧带图片向量时才产生分量。
    cross_modal = _cross_modal_similarity(query, candidate)
    if cross_modal is not None:
        components.append(
            (
                "cross_modal",
                WEIGHTS["cross_modal"],
                _calibrate(cross_modal, query, "cross_modal"),
            )
        )
    # 一个分量都没有（查询/候选都无任何可用信号）：直接判为无匹配基线。
    if not components:
        return 0.0, [], {}, "baseline"

    # 加权平均：分母用实际激活分量的权重之和，字段缺失时其余分量自动"扛"住权重。
    active_weight = sum(weight for _, weight, _ in components)
    score = sum(weight * value for _, weight, value in components) / active_weight
    # 生成可解释理由：只有得分 >= 0.6 的分量才给出理由；全都不足则给一句笼统文案。
    reasons = [reason(name, value, language) for name, _, value in components if value >= 0.6]
    if not reasons:
        reasons = ["综合条件较为接近" if language == "zh" else "Overall conditions are similar"]
    # 分量明细：每个分量名 → 保留 4 位小数的得分，供前端展示雷达/条形图。
    breakdown = {name: round(value, 4) for name, _weight, value in components}
    # 匹配模式（按最高可用信号判定，供前端展示与调试）：
    # 有跨模态向量 → multimodal；否则有图像向量 → image；
    # 否则有文本向量 → text；全部规则回退 → baseline。
    has_text = pretrained_text is not None
    has_visual = pretrained_visual is not None
    has_cross = cross_modal is not None
    if has_cross:
        mode = "pretrained_multimodal"
    elif has_visual:
        mode = "pretrained_image"
    elif has_text:
        mode = "pretrained_text"
    else:
        mode = "baseline"
    return score, reasons, breakdown, mode


# ── 文本相似度 ──────────────────────────────────────────────────
# 取序列相似度/Jaccard/包含度/哈希向量 4 路信号的最高值。
# 取 max 而不是平均：不同语言与写法下各信号表现不同（例如中文依赖字符二元组
# Jaccard、英文依赖单词包含度），任一路命中都应给出高分，平均会拉低强信号。
def text_similarity(left: str, right: str, *, text_embedding: bool = True) -> float:
    left_normalized = normalize(left)    # 统一小写、去标点、压缩空白
    right_normalized = normalize(right)
    # 任一侧归一化后为空 → 无有效文本，得 0 分。
    if not left_normalized or not right_normalized:
        return 0.0
    # 信号1：difflib 序列匹配（最长公共子序列类算法）比值。
    sequence = SequenceMatcher(None, left_normalized, right_normalized).ratio()
    left_tokens = tokens(left_normalized)   # 单词 + 中文相邻二元字组
    right_tokens = tokens(right_normalized)
    union = left_tokens | right_tokens
    # 信号2：Jaccard 系数 = 交集 / 并集，度量词集合重叠度。
    jaccard = len(left_tokens & right_tokens) / len(union) if union else 0.0
    # 信号3：包含度——若一方文本整体包含另一方，用较小 token 数占比度量。
    containment = (
        min(len(left_tokens), len(right_tokens)) / max(len(left_tokens), len(right_tokens))
        if left_normalized in right_normalized or right_normalized in left_normalized
        else 0.0
    )
    # 信号4：本地确定性哈希向量余弦相似度（离线基线，见 embeddings.py）。
    # 函数内延迟导入，避免仅在文本规则路径中引入依赖开销。
    vector = 0.0
    if text_embedding:
        from .embeddings import embedding_similarity

        vector = embedding_similarity(left_normalized, right_normalized)
    return max(sequence, jaccard, containment, vector)


# ── 短文本相似度 ────────────────────────────────────────────────
# 用于地点、颜色回退等简短自由文本：一方完整包含另一方即视为完全匹配（1.0），
# 否则退到序列相似度。子串包含（如 "主楼" vs "主楼一层"）通常表示同一地点。
def short_text_similarity(left: str, right: str) -> float:
    left_normalized = normalize(left)
    right_normalized = normalize(right)
    if not left_normalized or not right_normalized:
        return 0.0
    if left_normalized in right_normalized or right_normalized in left_normalized:
        return 1.0
    return SequenceMatcher(None, left_normalized, right_normalized).ratio()


# ── 降级视觉信号：颜色直方图指纹 ────────────────────────────────
# 仅在预训练图像 Embedding 不可用时被调用；返回最高的一对指纹相似度。
# 指纹来自 Java 后端与本地 embeddings.visual_fingerprint 同规格的 64 维
# 颜色直方图（VF1: 前缀），visual_similarity() 解析后做 L1 距离相似度计算。
def _visual_similarity(query: dict[str, Any], candidate: dict[str, Any]) -> float | None:
    query_fingerprints = _query_fingerprints(query)      # 查询端单/多图指纹
    candidate_fingerprints = candidate.get("visualFingerprints")
    # 任一端缺少指纹或候选端不是列表 → 无法计算，返回 None 表示"无此信号"。
    if not query_fingerprints or not isinstance(candidate_fingerprints, list):
        return None
    # 函数内延迟导入，仅降级路径用到。
    from .embeddings import visual_similarity

    best: float | None = None
    # 穷举查询指纹 × 候选指纹，取最高相似度作为该候选的视觉分。
    for query_fingerprint in query_fingerprints:
        for fingerprint in candidate_fingerprints:
            if not isinstance(fingerprint, str):
                continue
            value = visual_similarity(query_fingerprint, fingerprint)
            if value is not None and (best is None or value > best):
                best = value
    return best


# ── 预训练图像相似度 ────────────────────────────────────────────
# 查询图片向量与候选图片向量两两比对取最优。query 端来自
# AgentImage.visual_embedding（查询携带的图片），candidate 端来自报告
# visualEmbeddings 字段。返回 None 表示向量不可用（将降级到指纹路径）。
def _pretrained_visual_similarity(query: dict[str, Any], candidate: dict[str, Any]) -> float | None:
    return _best_embedding_pair(
        _string_list(query.get("visual_embeddings")),
        _string_list(candidate.get("visualEmbeddings")),
    )


# ── 图文跨模态相似度 ────────────────────────────────────────────
# 把文本向量与图片向量交叉比对，双向都试：查询文本 ↔ 候选图片、
# 候选文本 ↔ 查询图片，取所有可算结果的最高值。
# 只有任一侧文本带跨模态向量、另一侧带图片向量时才产生结果，
# 否则返回 None（即不贡献 cross_modal 分量）。
def _cross_modal_similarity(query: dict[str, Any], candidate: dict[str, Any]) -> float | None:
    values: list[float] = []
    query_cross = query.get("cross_modal_text_embedding")      # 查询侧文本跨模态向量
    candidate_cross = candidate.get("crossModalTextEmbedding")  # 候选侧文本跨模态向量
    candidate_images = _string_list(candidate.get("visualEmbeddings"))
    query_images = _string_list(query.get("visual_embeddings"))
    # 方向1：查询文本 ↔ 候选图片
    if isinstance(query_cross, str):
        for image in candidate_images:
            value = _embedding_similarity(query_cross, image)
            if value is not None:
                values.append(value)
    # 方向2：候选文本 ↔ 查询图片
    if isinstance(candidate_cross, str):
        for image in query_images:
            value = _embedding_similarity(candidate_cross, image)
            if value is not None:
                values.append(value)
    return max(values) if values else None


# ── Embedding 配对工具 ──────────────────────────────────────────
# 两组合 Embedding（base64 字符串）两两比对，返回最高的相似度。
# 用于预训练图像/文本向量的"最相似对"检索；任一侧为空或全部比对失败
# （解码失败/维度不一致）时返回 None。
def _best_embedding_pair(left: list[str], right: list[str]) -> float | None:
    values = [
        value
        for first in left
        for second in right
        if (value := _embedding_similarity(first, second)) is not None  # 海象赋值：过滤掉 None
    ]
    return max(values) if values else None


# 两条 base64 编码 Embedding 的内积（余弦）相似度；任何不可用情况返回 None。
# 向量来自外部 Embedding 服务（pretrained.py 注入）或后端，以 base64 编码的
# 32 位浮点数组传输。长度必须一致才可比；结果被截断到 [-1, 1]。
def _embedding_similarity(left: Any, right: Any) -> float | None:
    if not isinstance(left, str) or not isinstance(right, str):
        return None
    left_vector = _decode_vector(left)
    right_vector = _decode_vector(right)
    # 解码失败或维度不一致 → 视为不可用，返回 None（调用方据此跳过该分量）。
    if left_vector is None or right_vector is None or len(left_vector) != len(right_vector):
        return None
    # 单位向量内积即余弦相似度；zip(strict=True) 的长度已被上面保证一致。
    return max(-1.0, min(1.0, sum(a * b for a, b in zip(left_vector, right_vector, strict=True))))


# ── 向量解码 ────────────────────────────────────────────────────
# 把 base64 编码的小端 32 位浮点数组解码为 Python float 列表。
# 这是对外部向量格式的防御性解析：任何异常/超长/畸形数据都返回 None，
# 保证下游打分不会被脏数据打崩。向量为小端序 IEEE754 float32，
# 因此每 4 字节一个分量。
def _decode_vector(value: str) -> list[float] | None:
    # 长度上限保护：超过 12KB 的输入直接放弃（防超大串/资源占用）。
    if len(value) > 12_000:
        return None
    try:
        # base64 严格模式解码（validate=True 拒绝非标准字符）。
        payload = base64.b64decode(value, validate=True)
        # 空数据、超过 8192 字节、或长度不是 4 的倍数（切不出完整 float32）→ 无效。
        if not payload or len(payload) > 8192 or len(payload) % 4 != 0:
            return None
        # 按 <(N)f 格式一次性解出 N 个 float32；N = 字节数 / 4。
        vector = list(struct.unpack(f"<{len(payload) // 4}f", payload))
        # 逐分量校验有限性（拒绝 NaN/Inf，否则内积会产出非法分数）。
        return vector if all(math.isfinite(component) for component in vector) else None
    except (ValueError, struct.error):
        # base64 解码失败（ValueError）或 struct 格式/缓冲不匹配（struct.error）。
        return None


# 把任意值规整为字符串列表：非 list 返回空表，list 只保留 str 元素。
# 用于安全读取向量字段——后端字段可能缺失、为 None、或含非字符串脏数据。
def _string_list(value: Any) -> list[str]:
    return [item for item in value if isinstance(item, str)] if isinstance(value, list) else []


# ── 标定 ────────────────────────────────────────────────────────
# 把预训练相似度原始值线性标定到 [0,1]（对给定的 [lower, upper] 区间）。
# 预训练向量相似度分布集中且偏高，直接入总分会饱和；标定让低于下限的按 0、
# 高于上限的按 1，中间线性拉伸。区间优先取 query["_calibration"][component]
# （由 pretrained.py 按服务实测分布配置），缺失/非法时回退到
# DEFAULT_CALIBRATION 的同名默认区间。
def _calibrate(value: float, query: dict[str, Any], component: str) -> float:
    calibration = query.get("_calibration")
    limits = calibration.get(component) if isinstance(calibration, dict) else None
    # 区间必须是非空长度为 2 的列表/元组，否则用默认区间兜底。
    if not isinstance(limits, (list, tuple)) or len(limits) != 2:
        limits = DEFAULT_CALIBRATION[component]
    lower, upper = float(limits[0]), float(limits[1])
    # 防御：上限必须严格大于下限（如配置被写反则回退默认），避免除零/负拉伸。
    if upper <= lower:
        lower, upper = DEFAULT_CALIBRATION[component]
    # (value - lower) / (upper - lower)，再截断到 [0,1]。
    return max(0.0, min(1.0, (value - lower) / (upper - lower)))


def _query_fingerprints(query: dict[str, Any]) -> list[str]:
    """查询端视觉指纹：支持单图（visual_fingerprint）与多图（visual_fingerprints）两种写法。"""
    # 单图写法：字段是单个字符串就直接返回单元素列表。
    single = query.get("visual_fingerprint")
    if isinstance(single, str):
        return [single]
    # 多图写法：取列表并过滤出字符串元素。
    multiple = query.get("visual_fingerprints")
    if isinstance(multiple, list):
        return [value for value in multiple if isinstance(value, str)]
    return []  # 两种写法都未命中 → 空列表（无指纹信号）


# ── 时间相似度 ──────────────────────────────────────────────────
# 按日期差天数返回分档相似度（ISO 格式 YYYY-MM-DD）。
# 采用分档而非线性：让"差 1 天"与"差 0 天"保持可感知的区分，
# 同时让超过 30 天的情况快速衰减为 0。日期无法解析时返回 0.0。
def date_similarity(left: str, right: str) -> float:
    try:
        days = abs((date.fromisoformat(left) - date.fromisoformat(right)).days)
    except ValueError:
        return 0.0  # 任一侧不是合法 ISO 日期 → 无法比较，得 0
    # 分档：同日 1.0 → 差1天 0.9 → 差3天 0.7 → 差7天 0.5 → 差30天 0.2 → 更久 0.0
    if days == 0:
        return 1.0
    if days <= 1:
        return 0.9
    if days <= 3:
        return 0.7
    if days <= 7:
        return 0.5
    if days <= 30:
        return 0.2
    return 0.0


# 日期 + 时间段（如"下午"）综合时间相似度。
# 日期分量权重 0.8、时间段分量权重 0.2。任一侧无法解析时间段时
# 退化为只按日期打分（避免时间缺失拉低本应匹配的候选）。
def temporal_similarity(left_date: str, right_date: str, left_time: str, right_time: str) -> float:
    date_score = date_similarity(left_date, right_date)
    first = parse_time_minutes(left_time)   # 解析时间段为"当天第几分钟"
    second = parse_time_minutes(right_time)
    if first is None or second is None:
        return date_score  # 时间信息不全 → 只用日期分
    difference = abs(first - second)
    # 环形距离：一天 1440 分钟，23:00 与 01:00 只差 120 分钟（跨零点取最短弧）。
    difference = min(difference, 24 * 60 - difference)
    # 线性衰减：以 720 分钟（12 小时）为半衰半径，差满 12 小时得分归零。
    time_score = max(0.0, 1.0 - difference / 720.0)
    # 日期为主（0.8）、时间段为辅（0.2）的加权合成。
    return 0.8 * date_score + 0.2 * time_score


# 把时间段文本解析为"当天第几分钟"（0~1439）；无法解析返回 None。
# 优先识别 24 小时制时钟（如 "14:30"），否则匹配中英文时间段词
# （"下午"、"evening" 等，映射到一天内的代表性分钟数）。
def parse_time_minutes(value: str) -> int | None:
    normalized = value.lower().strip()
    # 先找 HH:MM 时钟：支持 0-9/00-23 时、00-59 分，两侧用词边界隔离。
    match = re.search(r"\b([01]?\d|2[0-3]):([0-5]\d)\b", normalized)
    if match:
        return int(match.group(1)) * 60 + int(match.group(2))  # 时×60 + 分
    # 时间段词表：中文与英文各时段取一个代表性分钟点（用于比较而非精确时刻）。
    periods = {
        "凌晨": 3 * 60,
        "早上": 8 * 60,
        "上午": 10 * 60,
        "中午": 12 * 60,
        "下午": 15 * 60,
        "傍晚": 18 * 60,
        "晚上": 21 * 60,
        "morning": 9 * 60,
        "noon": 12 * 60,
        "afternoon": 15 * 60,
        "evening": 19 * 60,
        "night": 22 * 60,
    }
    # 返回第一个出现在文本中的时段标签对应的分钟数；全不命中返回 None。
    return next((minutes for label, minutes in periods.items() if label in normalized), None)


# ── 文本预处理 ──────────────────────────────────────────────────
# 文本归一化：转小写、非单词/非汉字字符替换为单个空格、去首尾空白。
# 用途：把自由文本规整为可比形式，供文本相似度、颜色匹配、Embedding 前处理
# 复用。正则中 \u4e00-\u9fff 为 CJK 汉字区，保证中文汉字保留。
def normalize(value: str) -> str:
    return re.sub(r"[^\w\u4e00-\u9fff]+", " ", value.lower()).strip()


# 把归一化文本切成词集合：英文单词 + 中文相邻二元字组。
# 中文没有空格分词，单靠 split() 会把整句当一个 token；补上连续字符的
# 相邻二元组（bigram）后，Jaccard/哈希向量才能在中文上获得判别力。
# 输入需为 normalize() 的输出（否则空格会污染二元组）。
def tokens(value: str) -> set[str]:
    words = set(value.split())  # 空格分词：英文单词/完整中文串
    compact = value.replace(" ", "")  # 去掉空格得到连续字符流
    # 生成所有相邻二元字组（如 "失物招领" → 失物/物招/招领），
    # range(max(0, len-1)) 保护：len 为 0/1 时无二元组。
    words.update(compact[index : index + 2] for index in range(max(0, len(compact) - 1)))
    return {word for word in words if word}  # 过滤空串（防御性）


# 把分量名与得分转成人类可读的理由文案（中/英）。
# 得分 >= 0.85 时只给纯标签（"颜色相似"）；否则附上百分比（"颜色相似 (92%)"），
# 让用户看到各维度命中强度。只会在得分 >= 0.6 的分量上被调用。
def reason(component: str, value: float, language: str) -> str:
    labels = {
        "zh": {
            "text": "文字描述相似",
            "category": "物品类别一致",
            "colour": "颜色相似",
            "location": "地点接近",
            "temporal": "日期和时间接近",
            "visual": "图片特征相似",
            "cross_modal": "文字描述与图片相符",
        },
        "en": {
            "text": "Similar text description",
            "category": "Same item category",
            "colour": "Similar colour",
            "location": "Nearby location",
            "temporal": "Close date and time",
            "visual": "Similar image content",
            "cross_modal": "Text matches the image",
        },
    }
    # 按语言选文案表（非 zh 一律用英文），再取分量对应的标签。
    label = labels["zh" if language == "zh" else "en"][component]
    # 高分只显示标签；中高分附百分比便于区分强弱。
    return label if value >= 0.85 else f"{label} ({round(value * 100)}%)"
