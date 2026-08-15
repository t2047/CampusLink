"""复用 lost-found-embedding 服务的 LlamaIndex 自定义 Embedding。

把现有 embedding 微服务（POST /v1/embed/text，intfloat/multilingual-e5-small，
384 维，float32-le-base64 编码）封装成 llama_index.core.embeddings.BaseEmbedding
子类：模型保留在独立服务内，本模块只做 HTTP 调用，不在 utility 容器加载 torch。

语义约定（与 lost_found_embedding 服务一致）：
- 查询文本 role="query"（E5 的 "query: " 前缀）
- 文档/索引文本 role="document"（"passage: " 前缀）
"""

from __future__ import annotations

import base64
import math
import struct

import httpx
from llama_index.core.embeddings import BaseEmbedding

EMBEDDING_MODEL = "intfloat/multilingual-e5-small"
EMBEDDING_DIMENSION = 384
EMBEDDING_ENCODING = "float32-le-base64"

# embedding 服务单请求上限（TextEmbeddingRequest.items max_length=100）
_MAX_ITEMS_PER_REQUEST = 100


class HttpEmbedding(BaseEmbedding):
    """通过 HTTP 调用独立 embedding 服务的 BaseEmbedding 实现。

    所有 _*_embedding 方法失败时抛异常（由调用方 fail-open 降级），
    不做静默吞错——避免检索返回空结果时无法区分"无命中"与"服务故障"。
    """

    model_name: str = EMBEDDING_MODEL

    def __init__(
        self,
        url: str,
        shared_secret: str,
        timeout_seconds: float = 8.0,
        embed_batch_size: int = 32,
    ) -> None:
        super().__init__(embed_batch_size=embed_batch_size)
        self._url = url.rstrip("/")
        self._secret = shared_secret
        self._timeout = timeout_seconds
        self._client = httpx.Client(timeout=self._timeout)

    def close(self) -> None:
        self._client.close()

    # ── 抽象方法实现 ──────────────────────────────────────────────

    def _get_query_embedding(self, query: str) -> list[float]:
        return self._embed([query], role="query")[0]

    async def _aget_query_embedding(self, query: str) -> list[float]:
        return self._embed([query], role="query")[0]

    def _get_text_embedding(self, text: str) -> list[float]:
        return self._embed([text], role="document")[0]

    # 批量覆盖：一次 HTTP 请求多条，避免默认逐条循环
    def _get_text_embeddings(self, texts: list[str]) -> list[list[float]]:
        return self._embed(texts, role="document")

    # ── 内部实现 ──────────────────────────────────────────────────

    def _embed(self, texts: list[str], *, role: str) -> list[list[float]]:
        vectors: list[list[float]] = []
        for start in range(0, len(texts), _MAX_ITEMS_PER_REQUEST):
            batch = texts[start : start + _MAX_ITEMS_PER_REQUEST]
            response = self._client.post(
                f"{self._url}/v1/embed/text",
                headers={"X-Embedding-Service-Key": self._secret},
                json={
                    "items": [
                        {"id": str(index), "text": text, "role": role} for index, text in enumerate(batch)
                    ],
                    "spaces": ["semantic"],
                },
            )
            response.raise_for_status()
            payload = response.json()
            for item in payload["items"]:
                semantic = item.get("semantic")
                if not semantic:
                    raise ValueError("embedding service returned empty semantic vector")
                vectors.append(_decode_vector(semantic["vector"]))
        return vectors


def _decode_vector(encoded: str) -> list[float]:
    """float32-le-base64 → list[float]（与 lost_found_embedding/models.py 契约一致）。"""
    raw = base64.b64decode(encoded, validate=True)
    if len(raw) % 4 != 0:
        raise ValueError("invalid embedding payload length")
    values = list(struct.unpack(f"<{len(raw) // 4}f", raw))
    if not values or any(math.isnan(v) for v in values):
        raise ValueError("embedding payload contains NaN")
    return values
