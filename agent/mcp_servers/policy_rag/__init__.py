"""NUS 政策/规章制度文档 RAG（LlamaIndex + Qdrant + 复用 lost-found-embedding）。

- indexer.py：离线构建索引（PDF → 分块 → 嵌入 → Qdrant）
- retriever.py：运行时 Top-K 检索（供 search_policy MCP 工具使用）
- embedding.py：封装 lost-found-embedding HTTP 接口的 BaseEmbedding 实现
"""

from .config import PolicyRagSettings
from .retriever import PolicyRetriever

__all__ = ["PolicyRagSettings", "PolicyRetriever"]
