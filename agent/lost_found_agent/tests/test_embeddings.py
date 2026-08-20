"""嵌入（embeddings）模块测试：文本向量、图像指纹与跨语言一致性。

覆盖 `lost_found_agent.embeddings`：
- 文本嵌入的相似度应支持中文、部分词重叠的语义匹配；
- rank_candidates 使用向量信号召回时能把语义相关的候选排在前面；
- 图像指纹（visual_fingerprint）与 Java 后端 VisualFingerprintExtractor 存在
  跨语言一致性契约——同一张 PNG 必须生成完全相同的 golden 指纹；
- 指纹 base64 round-trip 往返无损；对畸形输入优雅返回 None；
- 相同图像的相似度为 1.0，不同纯色图像的相似度明显小于 1.0；
- 无法解码的字节走确定性 fallback（哈希直方图），两次结果一致。

策略：以与 Java 测试共享的 golden PNG 为基准做回归断言。
"""

import base64

from lost_found_agent.embeddings import (
    embed_image,
    embedding_similarity,
    image_similarity,
    visual_fingerprint,
    visual_fingerprint_to_vector,
    visual_similarity,
)
from lost_found_agent.matching import rank_candidates

# Canonical image shared with the Java VisualFingerprintExtractorTest:
# a 16x16 RGB PNG, left half blue (0,0,255), right half red (255,0,0),
# with no gAMA/iCCP/alpha so both decoders see the same pixels.
# 该图是 Python / Java 两侧共享的"黄金图"：不带 gAMA/iCCP/alpha 元数据，
# 保证两边的解码器（Pillow 与 JDK ImageIO）看到完全相同的像素。
GOLDEN_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAIAAACQkWg2AAAAKElEQVR4nGNkYPjPgA38Z2DEKs7EQCJgGtVABGAiRhEyGNVADCA5lAA1WwIfpDdLxAAAAABJRU5ErkJggg=="
)
# Computed with embed_image -> visual_fingerprint; the Java extractor must
# reproduce this exact string on the same PNG (cross-language parity contract).
# 由 Python 端预先算好的标准指纹（VF1: 前缀 + 64 个 float32 的 base64）；
# Java 端 VisualFingerprintExtractor 在同一张 PNG 上必须还原出这串完全相同的文本。
GOLDEN_FINGERPRINT = (
    "VF1:AAAAAAAAAAAAAAAAAAAAPwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAPwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
)


def test_embedding_similarity_supports_chinese_partial_overlap() -> None:
    """中文文本部分词重叠时，相似度应显著高于完全无关文本。

    验证："黑色无线耳机"与"黑色耳机盒"有"黑色/耳机"等共同 token，
    其相似度应大于与"红色雨伞"的相似度（说明相似度不是只对完全相同文本才高）。
    """
    assert embedding_similarity("黑色无线耳机", "黑色耳机盒") > embedding_similarity(
        "黑色无线耳机", "红色雨伞"
    )


def test_rank_candidates_uses_vector_signal_for_text_recall() -> None:
    """rank_candidates 开启文本向量时，应把语义相近的候选排到首位。

    查询是英文无线耳机，候选里"Black Bluetooth earbuds"与"Red umbrella"相比
    语义上更接近查询，因此第一个结果应为 id=1 的耳机。
    """
    results = rank_candidates(
        {"item_name": "wireless earbuds", "description": "black wireless earbuds"},
        [
            {
                "id": 1,
                "itemName": "Black Bluetooth earbuds",
                "category": "ELECTRONICS",
                "description": "Small black earphones in a charging case",
                "location": "Library",
                "eventDate": "2026-08-08",
                "status": "OPEN",
            },
            {
                "id": 2,
                "itemName": "Red umbrella",
                "category": "UMBRELLA",
                "description": "Compact umbrella",
                "location": "Gym",
                "eventDate": "2026-08-08",
                "status": "OPEN",
            },
        ],
        0.2,
        "en",
    )

    assert results[0].item_id == "1"  # 向量召回把耳机候选排在第一位


def test_embed_image_and_visual_fingerprint_match_java_golden() -> None:
    """图像指纹必须与 Java 后端预计算的 golden 值完全一致（跨语言契约）。"""
    fingerprint = visual_fingerprint(embed_image(GOLDEN_PNG))

    assert len(fingerprint) == 348  # VF1: 前缀 4 字符 + 64*4 字节 float32 的 base64（344）总长
    assert fingerprint.startswith("VF1:")  # 指纹以固定前缀开头
    assert fingerprint == GOLDEN_FINGERPRINT  # 与 Java 契约值逐字节一致


def test_visual_fingerprint_round_trip() -> None:
    """指纹 -> 向量 -> 指纹 往返必须无损，向量能被精确还原。"""
    vector = embed_image(GOLDEN_PNG)
    restored = visual_fingerprint_to_vector(visual_fingerprint(vector))

    assert restored is not None  # 合法指纹应能成功解码
    assert restored == vector  # 解码出的向量与原向量逐元素一致


def test_visual_fingerprint_to_vector_rejects_malformed_values() -> None:
    """畸形指纹（无前缀 / 非 base64 / 长度不足）应返回 None 而非抛异常。"""
    assert visual_fingerprint_to_vector("not-a-fingerprint") is None  # 缺少 VF1: 前缀
    assert visual_fingerprint_to_vector("VF1:not-base64!!!") is None  # 非法 base64 字符
    assert visual_fingerprint_to_vector("VF1:AQID") is None  # truncated payload；截断负载，不足 256 字节


def test_image_similarity_is_one_for_identical_visuals() -> None:
    """相同视觉向量之间相似度必须为 1.0。"""
    vector = embed_image(GOLDEN_PNG)

    assert image_similarity(vector, vector) == 1.0  # 自相似为最大相似度


def test_visual_similarity_distinguishes_solid_colours() -> None:
    """视觉相似度应能区分纯蓝与纯红图像，且与自身比较为 1.0。"""
    # 构造纯蓝 / 纯红两张 16x16 PNG 并分别计算指纹
    blue_fingerprint = visual_fingerprint(embed_image(make_solid_png((0, 0, 255))))
    red_fingerprint = visual_fingerprint(embed_image(make_solid_png((255, 0, 0))))

    differing = visual_similarity(blue_fingerprint, red_fingerprint)
    assert differing is not None  # 合法指纹应返回数值而非 None
    assert differing < 0.5  # 蓝与红的相似度应明显偏低
    assert visual_similarity(blue_fingerprint, blue_fingerprint) == 1.0  # 同图自相似为 1.0


def test_visual_similarity_is_none_on_malformed_input() -> None:
    """任一侧指纹非法时 visual_similarity 应返回 None，而不是抛异常。"""
    assert visual_similarity("garbage", "VF1:garbage") is None


def test_embed_image_fallback_is_deterministic_for_undecodable_bytes() -> None:
    """无法解码为图片的字节走确定性 fallback，两次结果必须一致。

    这是与 Java 端一致性的要求：WebP / 损坏字节在两边都用 SHA-256 直方图兜底，
    因此不能是随机噪声。
    """
    first = visual_fingerprint(embed_image(b"\x00\x01\x02 not an image"))
    second = visual_fingerprint(embed_image(b"\x00\x01\x02 not an image"))

    assert first == second  # fallback 是确定性的，两次指纹一致
    assert first.startswith("VF1:")  # fallback 产物仍是标准指纹格式


def make_solid_png(rgb: tuple[int, int, int]) -> bytes:
    """生成长宽 16px 的纯色 PNG（RGB，无透明度），供视觉相关测试构造输入。"""
    import io

    from PIL import Image

    image = Image.new("RGB", (16, 16), rgb)  # 创建 16x16 纯色画布
    buffer = io.BytesIO()  # 用内存字节流承接编码结果，避免写盘
    image.save(buffer, format="PNG")
    return buffer.getvalue()
