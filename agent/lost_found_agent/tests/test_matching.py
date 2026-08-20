"""失物招领候选重排（matching.py）的纯单元测试。

覆盖功能点：
- 缺失字段从权重分母移除（不因缺字段而压低总分）；
- 阈值过滤 + 结果上限 5 条（topK）；
- 视觉指纹：区分文本完全相同但图片不同的候选、取最佳图片对、多指纹查询；
- 预训练 Embedding：图片/文本/跨模态向量的打分与匹配模式（pretrained_*）上报；
- 非法向量回退 baseline；
- 颜色规范化：跨语言、大小写、同义词、复合色、词边界、空值。

测试策略：
- 纯函数级断言，无网络与外部依赖；
- 用 make_solid_png 生成纯色图，经 embeddings 的 visual_fingerprint 构造确定性指纹，
  make_embedding 构造小维度 float32 向量的 base64 编码；
- 直接调用 score_candidate / rank_candidates / colour_codes / colour_similarity。
"""

import base64
import struct

from lost_found_agent.embeddings import embed_image, visual_fingerprint
from lost_found_agent.matching import (
    colour_codes,
    colour_similarity,
    rank_candidates,
    score_candidate,
)

from .helpers import make_solid_png


def make_visual(query: tuple[int, int, int]) -> str:
    # 用指定 RGB 的纯色图生成确定性的视觉指纹；RGB 不同 → 指纹不同
    return visual_fingerprint(embed_image(make_solid_png(query)))


def make_embedding(*values: float) -> str:
    # 将若干 float 打包成小端 float32 数组并 base64 编码，模拟后端下发的预训练向量
    return base64.b64encode(struct.pack(f"<{len(values)}f", *values)).decode("ascii")


def test_missing_fields_are_removed_from_weight_denominator() -> None:
    """查询与候选都只有 category 时，权重分母只含 category → 完全匹配得 1.0。"""
    score, reasons = score_candidate(
        {"category": "UMBRELLA"},
        {"category": "UMBRELLA"},
        "en",
    )

    assert score == 1.0  # 缺字段不参与分母，单一匹配项即满分
    assert reasons == ["Same item category"]


def test_threshold_filters_candidates_and_result_is_limited_to_five() -> None:
    """低于阈值（0.35）的候选被过滤，且结果最多返回 5 条（topK）。"""
    # 10 条 BAG 候选：category 完全匹配、colour 蓝色 vs Blue 同色，得分应超过阈值
    query = {"category": "BAG", "colour": "blue"}
    candidates = [
        {
            "id": index,
            "itemName": f"Bag {index}",
            "category": "BAG",
            "description": "A bag",
            "colour": "Blue",
            "location": "Library",
            "eventDate": "2026-08-08",
            "status": "OPEN",
        }
        for index in range(10)
    ]
    # 追加一条 WALLET_PURSE：category 不匹配、colour 是 Brown，应被阈值过滤掉
    candidates.append(
        {
            "id": 99,
            "itemName": "Wallet",
            "category": "WALLET_PURSE",
            "description": "Brown wallet",
            "colour": "Brown",
            "location": "Gym",
            "eventDate": "2026-01-01",
            "status": "OPEN",
        }
    )

    results = rank_candidates(query, candidates, 0.35, "en")

    assert len(results) == 5  # topK 上限 5，多出的 BAG 被截断
    assert all(result.category == "BAG" for result in results)  # 钱包候选已被过滤


def test_visual_component_discriminates_identical_text_candidates() -> None:
    """文本完全相同的两个候选，靠视觉指纹区分：命中蓝图的排第一。"""
    # 查询携带蓝色视觉指纹；两候选文本/类别/地点完全相同，仅图片不同
    query = {"item_name": "水杯", "visual_fingerprint": make_visual((0, 0, 255))}
    candidates = [
        {
            "id": 1,
            "itemName": "水杯",
            "category": "OTHER",
            "description": "一个杯子",
            "location": "图书馆",
            "eventDate": "2026-08-08",
            "status": "OPEN",
            "visualFingerprints": [make_visual((0, 0, 255))],  # 蓝色 → 与查询匹配
        },
        {
            "id": 2,
            "itemName": "水杯",
            "category": "OTHER",
            "description": "一个杯子",
            "location": "图书馆",
            "eventDate": "2026-08-08",
            "status": "OPEN",
            "visualFingerprints": [make_visual((255, 0, 0))],  # 红色 → 不匹配
        },
    ]

    # 阈值 0.0：两个候选都进入，靠视觉得分决出先后
    results = rank_candidates(query, candidates, 0.0, "zh")

    assert results[0].item_id == "1"  # 视觉命中的候选胜出
    assert any("图片特征相似" in reason for reason in results[0].match_reason)


def test_visual_component_uses_best_candidate_image() -> None:
    """候选有多张图时取与查询最相似的一张（最佳图片对），而非平均/首张。"""
    query = {"visual_fingerprint": make_visual((0, 0, 255))}
    base = {
        "id": 7,
        "itemName": "水杯",
        "category": "OTHER",
        "description": "一个杯子",
        "location": "图书馆",
        "eventDate": "2026-08-08",
        "status": "OPEN",
    }
    # 同一候选带两张图：一红一蓝，蓝色命中查询
    with_matching_image = {
        **base,
        "visualFingerprints": [make_visual((255, 0, 0)), make_visual((0, 0, 255))],
    }
    # 只有红图的候选完全不命中
    only_wrong_image = {**base, "visualFingerprints": [make_visual((255, 0, 0))]}

    better_score, _ = score_candidate(query, with_matching_image, "zh")
    worse_score, _ = score_candidate(query, only_wrong_image, "zh")

    assert better_score == 1.0  # 候选只要有一张命中即得满分
    assert worse_score == 0.0


def test_query_supports_multiple_fingerprints_and_takes_best_pair() -> None:
    """查询端支持多指纹（visual_fingerprints），取与候选的最佳配对。"""
    # 查询带红、蓝两个指纹
    query = {"visual_fingerprints": [make_visual((255, 0, 0)), make_visual((0, 0, 255))]}
    base = {
        "id": 7,
        "itemName": "水杯",
        "category": "OTHER",
        "description": "一个杯子",
        "location": "图书馆",
        "eventDate": "2026-08-08",
        "status": "OPEN",
    }
    matches_second = {**base, "visualFingerprints": [make_visual((0, 0, 255))]}  # 命中第二个查询指纹
    no_match = {**base, "visualFingerprints": [make_visual((255, 255, 0))]}  # 与两个查询指纹都不匹配

    better_score, _ = score_candidate(query, matches_second, "zh")
    worse_score, _ = score_candidate(query, no_match, "zh")

    assert better_score == 1.0
    assert worse_score == 0.0


def test_text_embedding_flag_turns_off_vector_signal() -> None:
    """text_embedding=False 关掉文本向量信号后得分不高于开启时（回归防护）。"""
    query = {"description": "black wireless earbuds in charging case"}
    candidate = {
        "id": 1,
        "itemName": "Black Bluetooth earphones",
        "category": "ELECTRONICS",
        "description": "Small black earphones with a charging case",
        "location": "Library",
        "eventDate": "2026-08-08",
        "status": "OPEN",
    }

    # 开启文本向量：sequence / jaccard / containment / vector 四者取最大
    with_embedding, _ = score_candidate(query, candidate, "en")
    # 关闭文本向量：只走字符序列相似度，向量信号被移除
    rule_only, _ = score_candidate(query, candidate, "en", text_embedding=False)

    # 向量信号只会提升或持平得分，绝不应拖低
    assert with_embedding >= rule_only


def test_pretrained_image_embedding_uses_best_image_pair_and_reports_mode() -> None:
    """预训练图片向量：取最佳图片对打分，matching_mode 上报 pretrained_image。"""
    # 查询带两个图片向量；候选 1 命中第二个（(1,0)），候选 2 与查询正交/相反
    query = {
        "visual_embeddings": [make_embedding(0.0, 1.0), make_embedding(1.0, 0.0)],
    }
    candidates = [
        {
            "id": 1,
            "reportType": "FOUND",
            "itemName": "Black headphones",
            "category": "ELECTRONICS",
            "description": "Headphones in a case",
            "location": "Library",
            "eventDate": "2026-08-08",
            "status": "OPEN",
            "visualEmbeddings": [make_embedding(1.0, 0.0)],  # 与查询第二个向量全等 → 余弦=1
        },
        {
            "id": 2,
            "reportType": "FOUND",
            "itemName": "Red umbrella",
            "category": "UMBRELLA",
            "description": "Foldable umbrella",
            "location": "Gym",
            "eventDate": "2026-08-08",
            "status": "OPEN",
            "visualEmbeddings": [make_embedding(0.0, -1.0)],  # 与查询向量点积为 0 → 不匹配
        },
    ]

    results = rank_candidates(query, candidates, 0.0, "en")

    assert results[0].item_id == "1"
    assert results[0].matching_mode == "pretrained_image"  # 命中预训练图片模式
    assert results[0].score_breakdown["visual"] == 1.0  # 校准后得满分
    assert "Similar image content" in results[0].match_reason


def test_pretrained_text_and_cross_modal_vectors_are_calibrated() -> None:
    """文本 + 跨模态向量都命中时进入 pretrained_multimodal，且各自校准为 1.0。"""
    same = make_embedding(1.0, 0.0)  # 全等向量，余弦相似度 = 1
    query = {
        "semantic_text_embedding": same,  # 文本语义向量
        "cross_modal_text_embedding": same,  # 跨模态（文字↔图片）向量
    }
    candidate = {
        "id": 3,
        "reportType": "FOUND",
        "itemName": "黑色耳机",
        "category": "ELECTRONICS",
        "description": "装在充电盒中的耳机",
        "location": "图书馆",
        "eventDate": "2026-08-08",
        "status": "OPEN",
        "semanticTextEmbedding": same,
        "visualEmbeddings": [same],  # 供跨模态相似度配对
    }

    result = rank_candidates(query, [candidate], 0.0, "zh")[0]

    assert result.matching_mode == "pretrained_multimodal"  # 文本 + 跨模态双信号
    assert result.score_breakdown == {"text": 1.0, "cross_modal": 1.0}  # 全等向量校准为满分
    assert "文字描述相似" in result.match_reason  # 文本组件的中文原因
    assert "文字描述与图片相符" in result.match_reason  # cross_modal 组件的中文原因


def test_invalid_pretrained_vector_falls_back_to_rules() -> None:
    """查询端预训练向量非法（非 base64）时回退规则引擎，matching_mode=baseline。"""
    results = rank_candidates(
        {"item_name": "黑色耳机", "semantic_text_embedding": "not-base64"},  # 非法向量
        [
            {
                "id": 4,
                "reportType": "FOUND",
                "itemName": "黑色耳机",
                "category": "ELECTRONICS",
                "description": "一副耳机",
                "location": "图书馆",
                "eventDate": "2026-08-08",
                "status": "OPEN",
                "semanticTextEmbedding": make_embedding(1.0, 0.0),  # 候选端向量是合法的
            }
        ],
        0.0,
        "zh",
    )

    assert results[0].matching_mode == "baseline"  # 查询端向量解码失败 → 降级 baseline
    assert results[0].score_breakdown["text"] > 0.0  # 文本规则相似度仍参与打分


def test_colour_codes_cross_language_and_synonyms() -> None:
    """colour_codes：大小写、跨语言、同义词统一归一到同一 canonical code。"""
    # 大小写、跨语言、同义词归到同一 canonical code
    assert colour_codes("white") == {"WHITE"}
    assert colour_codes("White") == {"WHITE"}
    assert colour_codes("白色") == {"WHITE"}
    assert colour_codes("ivory") == {"WHITE"}
    assert colour_codes("cream") == {"WHITE"}
    assert colour_codes("黑色") == {"BLACK"}
    assert colour_codes("gray") == {"GREY"}
    assert colour_codes("navy") == {"BLUE"}
    assert colour_codes("golden") == {"GOLD"}
    # 复合色 → 多个 code
    assert colour_codes("blue lid black bottle") == {"BLUE", "BLACK"}
    # 词边界避免误命中：backpack / redemption 都不是颜色
    assert colour_codes("backpack") == frozenset()
    assert colour_codes("redemption") == frozenset()
    assert colour_codes("") == frozenset()


def test_colour_similarity_maps_canonical_groups() -> None:
    """colour_similarity：两侧都命中 canonical 颜色时按 code 集合判定同色/异色。"""
    assert colour_similarity("white", "白色") == 1.0
    assert colour_similarity("white", "White") == 1.0
    assert colour_similarity("ivory", "白色") == 1.0
    assert colour_similarity("white", "Black") == 0.0
    assert colour_similarity("白色", "蓝色") == 0.0
    assert colour_similarity("white", "") == 0.0


def test_colour_similarity_falls_back_for_unknown_values() -> None:
    """未命中 canonical 颜色表时回退 short_text_similarity，保留旧行为。"""
    # 未命中 canonical 表时回退 short_text_similarity（保留旧行为）
    assert 0.0 < colour_similarity("white", "whitish") < 1.0
    assert colour_similarity("midnight", "mid night") > 0.0
    assert colour_similarity("", "") == 0.0
