"""运行时检索：从 Qdrant 加载政策文档索引并做 Top-K 向量检索。

懒加载（首次 search 时才建 Qdrant 客户端与索引），避免 utility 容器
启动即依赖 Qdrant/embedding 服务在线；检索失败抛异常，由 MCP 工具层
fail-open 转成错误 JSON（与 web_search 一致）。
"""

from __future__ import annotations

import logging
from typing import Any

from llama_index.core import VectorStoreIndex
from llama_index.vector_stores.qdrant import QdrantVectorStore
from qdrant_client import QdrantClient
from qdrant_client.http import models as rest

from .config import PolicyRagSettings
from .embedding import EMBEDDING_DIMENSION, HttpEmbedding

logger = logging.getLogger(__name__)


def build_qdrant_client(settings: PolicyRagSettings) -> QdrantClient:
    """构造 QdrantClient：qdrant_local_path 走嵌入式本地模式，否则连远程 url。"""
    if settings.qdrant_local_path:
        return QdrantClient(path=settings.qdrant_local_path)
    return QdrantClient(url=settings.qdrant_url, timeout=settings.qdrant_timeout_seconds)


class PolicyRetriever:
    def __init__(self, settings: PolicyRagSettings) -> None:
        self._settings = settings
        self._index: VectorStoreIndex | None = None
        self._embedding: HttpEmbedding | None = None

    def is_ready(self) -> bool:
        """Qdrant 可达且 collection 存在（用于 health/预热检查）。"""
        try:
            client = build_qdrant_client(self._settings)
            return client.collection_exists(self._settings.qdrant_collection)
        except Exception:  # noqa: BLE001 - 探测语义：任何连接/查询失败都视为未就绪
            return False

    def search(self, query: str, top_k: int | None = None) -> list[dict[str, Any]]:
        index = self._ensure_index()
        retriever = index.as_retriever(similarity_top_k=top_k or self._settings.top_k)
        nodes = retriever.retrieve(query)
        return [_node_to_result(node) for node in nodes]

    def _ensure_index(self) -> VectorStoreIndex:
        if self._index is None:
            settings = self._settings
            client = build_qdrant_client(settings)
            vector_store = QdrantVectorStore(
                collection_name=settings.qdrant_collection,
                client=client,
                # 与 indexer 的 dense_config 保持一致：缺失 collection 时按 384 维 COSINE 创建
                dense_config=rest.VectorParams(size=EMBEDDING_DIMENSION, distance=rest.Distance.COSINE),
            )
            self._embedding = HttpEmbedding(
                url=settings.embedding_url,
                shared_secret=settings.embedding_shared_secret,
                timeout_seconds=settings.embedding_timeout_seconds,
                embed_batch_size=settings.embed_batch_size,
            )
            self._index = VectorStoreIndex.from_vector_store(vector_store, embed_model=self._embedding)
            logger.info(
                "policy retriever ready: collection=%s top_k=%d",
                settings.qdrant_collection,
                settings.top_k,
            )
        return self._index


def _node_to_result(node: Any) -> dict[str, Any]:
    metadata = node.metadata or {}
    file_name = str(metadata.get("file_name", "unknown.pdf"))
    page_label = str(metadata.get("page_label", ""))
    return {
        "text": node.get_text(),
        "score": round(float(node.score), 4) if node.score is not None else None,
        "source": f"{file_name}#p{page_label}" if page_label else file_name,
        "file": file_name,
        "page": page_label,
    }
