"""本地确定性向量嵌入：为多语言失物招领匹配提供离线 baseline。

背景与定位
----------
在引入生产级嵌入服务 / 向量数据库之前，本项目需要一个完全可离线运行的
“确定性嵌入”作为兜底匹配信号：它给匹配流水线（matching.rank_candidates）
提供向量召回面（vector-recall surface），并让 Recall@K 回归测试可复现。

确定性（deterministic）是关键
------------------------------
- 文本向量：对规范化后的文本 token 做哈希散列（blake2b），把每个 token
  映射到 128 维向量的某个桶并叠加带符号权重，再做 L2 归一化。
- 图片向量：对图片做 8x8 网格采样并量化成 64 桶颜色直方图，L1 归一化，
  得到“颜色指纹”；WebP / 无法解码的字节回退到 SHA-256 直方图。

由于 Python Agent 与 Java 后端使用同一套编码规范，文本向量与图片指纹可以在
CI 与 Java 侧被逐字节一致地重新计算，从而保证两端打分口径完全统一。

对外核心函数
------------
- embed_text / embedding_similarity：文本 → 向量 / 文本-文本相似度
- embed_image / image_similarity / visual_fingerprint(_to_vector) /
  visual_similarity：图片 → 颜色指纹向量、指纹字符串编解码与相似度
- 以上全部被 matching.py 在 rank_candidates 打分链路中调用。
"""

import base64
import hashlib
import io
import struct
from collections.abc import Iterable
from hashlib import blake2b
from math import sqrt

# 复用 matching.py 的规范化与切词工具，保证“向量 token”与“文本相似度 token”口径一致
from .matching import normalize, tokens

# 文本向量的维度：每个 token 被哈希散列到 0..127 的某个桶
EMBEDDING_DIMENSIONS = 128

# 图片颜色直方图的桶数（与 Java 后端 VisualFingerprintExtractor 保持一致）
VISUAL_BUCKETS = 64
# 图片采样网格尺寸：8x8 = 64 个采样点，每个点对应一个颜色桶
VISUAL_GRID_SIZE = 8
# 视觉指纹字符串的前缀，用于跨端标识版本（VF = Visual Fingerprint）
VISUAL_FINGERPRINT_PREFIX = "VF1:"
# 视觉指纹二进制载荷的 struct 格式：64 个小端 float32（与后端打包字节序一致）
_VISUAL_FLOAT_FORMAT = "<64f"


def embed_text(value: str, dimensions: int = EMBEDDING_DIMENSIONS) -> list[float]:
    """把文本编码为确定性的 L2 归一化向量（哈希散列 + 带符号叠加）。

    对每个切分出的 token 做 8 字节 blake2b 摘要，取其前 4 字节整数模维度数
    得到“桶”下标，再用第 5 字节的最低位决定贡献正负号并累加——不同 token
    命中同一桶时按符号相互抵消/叠加，从而形成稳定、可复现的高维稀疏特征。
    最后做 L2 归一化，使向量仅保留方向信息，便于直接用余弦相似度比较。

    :param value: 待编码文本
    :param dimensions: 输出向量维度，缺省 EMBEDDING_DIMENSIONS
    :return: 长度 dimensions 的归一化 float 向量
    """
    # 初始化全 0 向量
    vector = [0.0] * dimensions
    for token in embedding_tokens(value):
        # 取 token 的 8 字节哈希摘要作为“指纹字节”，相同 token 恒得相同摘要
        digest = blake2b(token.encode("utf-8"), digest_size=8).digest()
        # 用前 4 字节（按大端）模 dimensions 定位桶下标，保证 token→桶映射确定
        bucket = int.from_bytes(digest[:4], "big") % dimensions
        # 第 5 字节最低位决定 +1 / -1：相同 token 恒同号，不同 token 概率性抵消
        sign = 1.0 if digest[4] & 1 else -1.0
        vector[bucket] += sign
    # 计算 L2 范数
    norm = sqrt(sum(component * component for component in vector))
    # 全 0 向量（无有效 token）直接返回，避免除零
    if norm == 0:
        return vector
    # 除以范数得到单位向量：相似度只比较方向，与文本长度解耦
    return [component / norm for component in vector]


def cosine_similarity(left: list[float], right: list[float]) -> float:
    """两个等长向量的余弦相似度（对已归一化向量等价于点积）。

    :param left: 左向量
    :param right: 右向量
    :return: 裁剪到 [0,1] 的相似度；长度不一致时抛 ValueError
    :raises ValueError: left 与 right 长度不同，防止错位相乘产生虚假相似度
    """
    if len(left) != len(right):
        raise ValueError("embedding dimensions must match")
    # 归一化向量的点积即余弦相似度；负相似度对匹配语义无意义，裁剪归零
    return max(0.0, min(1.0, sum(a * b for a, b in zip(left, right, strict=True))))


def embedding_similarity(left: str, right: str) -> float:
    """两段文本的向量相似度：先各自编码，再用余弦相似度比较。

    :param left: 第一段文本
    :param right: 第二段文本
    :return: [0,1] 相似度；任一侧无可编码内容时返回 0.0 而非报错
    """
    left_vector = embed_text(left)
    right_vector = embed_text(right)
    # 任一侧是空向量（无有效 token）视为无相似信号，返回 0.0
    if not any(left_vector) or not any(right_vector):
        return 0.0
    return cosine_similarity(left_vector, right_vector)


def embedding_tokens(value: str) -> Iterable[str]:
    """把文本切成参与向量构建的 token 集合（词 token + 1~3 字 n-gram 并集）。

    仅依赖 tokens() 的词级别切分对中文/拼写变体过于稀疏，这里额外补充
    去空格文本的全部 1、2、3 字滑动子串（char n-gram）：中文单字、双字、
    三字都能稳定命中同一字符片段，从而提升同义/近义表达的向量重合度。

    :param value: 原始文本（未规范化）
    :return: 去重后的 token 集合（含词 token 与字符 n-gram），已过滤空串
    """
    # 先做小写化/去标点等规范化，保证不同大小写/标点形式命中同一 token
    normalized = normalize(value)
    # 词级别 token（matching.tokens 的输出，词 + 双字）
    base_tokens = tokens(normalized)
    # 去掉所有空格后的紧凑文本，用于生成跨词界的连续 n-gram
    compact = normalized.replace(" ", "")
    # 集合推导：size=1/2/3 时取 compact 的全部长度为 size 的滑动子串；
    # range(max(0, len-size+1)) 处理 len < size 的边界，避免负下标
    char_grams = {
        compact[index : index + size]
        for size in (1, 2, 3)
        for index in range(max(0, len(compact) - size + 1))
    }
    # 词 token 与 n-gram 取并集，最后滤掉空串
    return {token for token in base_tokens | char_grams if token}


def embed_image(image_data: bytes) -> list[float]:
    """Return a deterministic 64-dim colour-histogram vector for an image.

    The spec is shared with the Java backend (VisualFingerprintExtractor):
    sample an 8x8 grid with integer scaling, quantize each RGB pixel into a
    64-bucket histogram, then L1-normalize. WebP (which the JDK ImageIO cannot
    decode) and undecodable bytes fall back to a SHA-256 histogram so both
    sides agree.

    （中文）返回图片的确定性 64 维颜色直方图向量（视觉指纹的原始向量）。
    规格与 Java 后端共享：8x8 网格整数缩放采样，每个 RGB 像素量化进 64 桶
    直方图并做 L1 归一化。WebP（JDK ImageIO 无法解码）以及无法解码的字节
    回退到 SHA-256 直方图，保证两端对同一图片算出完全一致的向量。

    :param image_data: 图片文件的原始字节
    :return: 64 维非负 float 向量，分量为 0~1 且总和为 1（L1 归一化）
    """
    # 非 WebP 才尝试 PIL 颜色直方图；WebP 直接走 fallback 以保持与 Java 一致
    if not _is_webp(image_data):
        counts = _colour_histogram(image_data)
        # 解码成功返回直方图向量；失败（None）落到 fallback
        if counts is not None:
            return _normalize_visual(counts)
    return _fallback_visual_vector(image_data)


def image_similarity(left: list[float], right: list[float]) -> float:
    """两个颜色直方图向量的相似度（基于 L1 距离的相似度）。

    L1 距离取值范围为 [0, 2]（两个 L1 归一化向量分量差绝对值之和最大为 2），
    因此用 1 - distance/2 映射到 [0,1] 作为相似度，再裁剪保底。

    :param left: 左颜色向量（64 维）
    :param right: 右颜色向量（64 维）
    :return: [0,1] 相似度；维度不一致或任一侧全 0 时返回 0.0
    """
    # 维度不一致或任一侧全 0（无颜色信息）视为无相似信号
    if len(left) != len(right) or not any(left) or not any(right):
        return 0.0
    # L1 距离：各分量绝对值差之和
    distance = sum(abs(a - b) for a, b in zip(left, right, strict=True))
    # 距离越小相似度越高；1.0 - distance/2 把 [0,2] 距离线性映射到 [0,1]
    return max(0.0, min(1.0, 1.0 - distance / 2.0))


def visual_fingerprint(vector: list[float]) -> str:
    """把 64 维颜色向量编码为字符串指纹（VF1:<base64>），供存储/传输/检索。

    向量先按小端 float32 打包成二进制（128 字节），再 base64 成 ASCII 字符串
    并加上 VF1: 前缀。与 Java 后端约定相同编码，保证跨端可互相解析。

    :param vector: 64 维颜色向量
    :return: "VF1:" 前缀 + base64 指纹字符串
    :raises ValueError: vector 维度不是 64 时抛出，防止打包错位
    """
    # 只接受恰好 64 维的向量
    if len(vector) != VISUAL_BUCKETS:
        raise ValueError("visual vector must have exactly 64 dimensions")
    # struct.pack 按 _VISUAL_FLOAT_FORMAT（<64f）把小端 64 个 float32 序列化
    payload = struct.pack(_VISUAL_FLOAT_FORMAT, *vector)
    # 前缀 + base64 编码，decode("ascii") 得到纯 ASCII 字符串
    return VISUAL_FINGERPRINT_PREFIX + base64.b64encode(payload).decode("ascii")


def visual_fingerprint_to_vector(fingerprint: str) -> list[float] | None:
    """visual_fingerprint 的逆操作：把指纹字符串解析回 64 维向量。

    解析失败（前缀不符 / base64 非法 / 字节数不匹配）一律返回 None，
    由调用方降级处理，绝不抛异常中断匹配流程。

    :param fingerprint: "VF1:..." 形式的指纹字符串
    :return: 64 维 float 向量；无法解析时返回 None
    """
    # 前缀不符说明不是本版本的指纹，无法解析
    if not fingerprint.startswith(VISUAL_FINGERPRINT_PREFIX):
        return None
    try:
        # 去掉前缀后按严格模式 base64 解码（validate=True 拒绝非 base64 字符）
        payload = base64.b64decode(fingerprint[len(VISUAL_FINGERPRINT_PREFIX) :], validate=True)
        # 按 <64f 解包为 64 个 float；字节数不对会抛 struct.error
        return list(struct.unpack(_VISUAL_FLOAT_FORMAT, payload))
    except (ValueError, struct.error):
        # 捕获解码/解包异常，统一返回 None 表示“不可解析”
        return None


def visual_similarity(left: str, right: str) -> float | None:
    """两张图片（以指纹字符串形式）的相似度；任一侧不可解析返回 None。

    :param left: 左图片指纹
    :param right: 右图片指纹
    :return: [0,1] 相似度；任一指纹无效时返回 None（与“相似度 0”区分开）
    """
    left_vector = visual_fingerprint_to_vector(left)
    right_vector = visual_fingerprint_to_vector(right)
    # 任一指纹无效即无视觉相似信号，返回 None
    if left_vector is None or right_vector is None:
        return None
    return image_similarity(left_vector, right_vector)


def _colour_histogram(image_data: bytes) -> list[int] | None:
    """用 PIL 计算 8x8 采样网格的 64 桶 RGB 颜色直方图（桶计数，未归一化）。

    :param image_data: 图片字节
    :return: 64 个整数的计数列表；解码失败返回 None（由调用方回退 fallback）
    """
    image = None
    converted = None
    try:
        # 延迟导入 PIL，避免在未安装 Pillow 的环境中导入本模块即失败
        from PIL import Image

        # 从内存字节打开并统一转为 RGB（忽略原图色彩模式/透明度差异）
        image = Image.open(io.BytesIO(image_data))
        converted = image.convert("RGB")
        width, height = converted.size
        # 按行主序展平为字节串：每像素 3 字节（R,G,B）
        raw = converted.tobytes()
    except Exception:
        # 任何解码异常都视为“不可解码”，返回 None 走 fallback
        return None
    finally:
        # 显式释放 PIL 图像对象，避免句柄/内存泄漏
        if converted is not None:
            converted.close()
        if image is not None:
            image.close()

    # 初始化 64 桶计数
    counts = [0] * VISUAL_BUCKETS
    # 在 8x8 网格上均匀采样 64 个像素点（整数缩放，避免浮点采样与 Java 不一致）
    for row in range(VISUAL_GRID_SIZE):
        # 采样行坐标：按行序号等比例取整，任意尺寸图片都得到确定性位置
        sample_y = (row * height) // VISUAL_GRID_SIZE
        row_offset = sample_y * width
        for column in range(VISUAL_GRID_SIZE):
            sample_x = (column * width) // VISUAL_GRID_SIZE
            # 字节偏移：像素 (sample_x, sample_y) 的 R 通道起始位置
            offset = (row_offset + sample_x) * 3
            # RGB 各通道取高 2 位（>>6）拼成一个 6 bit 桶号：
            #   (R 高2位)<<4 | (G 高2位)<<2 | (B 高2位) → 0..63
            # 只保留高 2 位等价于丢弃低 6 位噪声，相近颜色落入同一桶
            bucket = (
                ((raw[offset] >> 6) & 3) << 4
                | ((raw[offset + 1] >> 6) & 3) << 2
                | ((raw[offset + 2] >> 6) & 3)
            )
            counts[bucket] += 1
    return counts


def _fallback_visual_vector(image_data: bytes) -> list[float]:
    """无法走颜色直方图时的确定性回退向量：基于图片字节的 SHA-256 直方图。

    取图片前 1024 字节（不足则整段）做 SHA-256，把 32 个摘要字节按循环方式
    展开成 64 个桶值，再 L1 归一化。规则与 Java 后端一致，保证两端对同一张
    图片（即使无法解码）也能算出相同向量。

    :param image_data: 图片字节
    :return: 64 维 L1 归一化向量
    """
    # 取前 1KB 作为采样；空字节串时回退到整段（此时 sample 即原字节）
    sample = image_data[:1024] or image_data
    # SHA-256 摘要：32 字节，内容完全由输入字节决定 → 确定性强
    digest = hashlib.sha256(sample).digest()
    # 循环取摘要字节填充 64 个桶（index % 32），得到“伪颜色直方图”计数
    counts = [digest[index % len(digest)] for index in range(VISUAL_BUCKETS)]
    return _normalize_visual(counts)


def _normalize_visual(counts: list[int]) -> list[float]:
    """L1 归一化：把桶计数列表转换成总和为 1 的概率分布向量。

    :param counts: 桶计数列表（长度 64）
    :return: 各分量在 [0,1] 且总和为 1 的向量；全 0 输入返回全 0 向量
    """
    total = sum(counts)
    # 全 0（无任何像素信息）时返回全 0 向量，避免除零
    if total == 0:
        return [0.0] * VISUAL_BUCKETS
    # 每个桶除以总数，得到总和为 1 的分布向量
    return [count / total for count in counts]


def _is_webp(image_data: bytes) -> bool:
    """按 WebP 容器的魔数判断是否为 WebP（RIFF....WEBP）。

    JDK 的 ImageIO 无法解码 WebP，因此 Python 侧也跳过 PIL 直方图、直接走
    fallback，保证与 Java 后端对同一张 WebP 图算出相同的向量。

    :param image_data: 图片字节
    :return: True 表示该字节流是 WebP
    """
    # WebP 容器头：前 4 字节 "RIFF"、偏移 8..11 为 "WEBP"，整体至少 12 字节
    return len(image_data) >= 12 and image_data[:4] == b"RIFF" and image_data[8:12] == b"WEBP"
