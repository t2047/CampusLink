"""离线索引构建：docs/nus_docs 下的 PDF → 分块 → 嵌入 → 写入 Qdrant。

运行（构建前需 Qdrant 与 lost-found-embedding 服务在线）：
    python -m mcp_servers.policy_rag.indexer [--docs-dir docs/nus_docs]

要点（基于 LlamaIndex 0.14 调查结论）：
- PDFReader（readers-file，pypdf）默认按页返回 Document，metadata 自动带
  file_name + page_label → 检索结果可直接标注来源。
- SentenceSplitter 的 chunk_size 单位是 token；默认句子切分走 nltk punkt
  （离线可能触发下载），这里传入正则切句函数绕开 nltk，纯离线。
- tokenizer 默认 tiktoken，缓存指向 wheel 内置 _static/tiktoken_cache，无需下载。
- QdrantVectorStore 在 collection 不存在时自动创建；显式 dense_config 指定
  384 维 COSINE，与 lost-found-embedding（multilingual-e5-small）一致。
"""

from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

# 允许直接运行（无需安装包）：把 agent/ 加入 sys.path（import mcp_servers）
_ROOT = Path(__file__).resolve().parents[2]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from llama_index.core import SimpleDirectoryReader, StorageContext, VectorStoreIndex
from llama_index.core.node_parser import SentenceSplitter
from llama_index.core.node_parser.text.utils import split_by_regex
from llama_index.vector_stores.qdrant import QdrantVectorStore
from qdrant_client.http import models as rest

from mcp_servers.policy_rag.config import PolicyRagSettings
from mcp_servers.policy_rag.embedding import EMBEDDING_DIMENSION, HttpEmbedding
from mcp_servers.policy_rag.retriever import build_qdrant_client

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("policy_rag.indexer")

# 正则切句：绕开 nltk punkt（离线环境不触发数据下载）
_SENTENCE_SPLIT_RE = r"[^.;。！？]+[.;。！？]?|[.;。！？]"


def build_index(settings: PolicyRagSettings) -> dict[str, int]:
    """读取 PDF → 分块 → 嵌入 → 写入 Qdrant，返回统计信息。"""
    docs_dir = Path(settings.docs_dir)
    if not docs_dir.is_dir():
        raise SystemExit(f"docs dir not found: {docs_dir}")

    embed = HttpEmbedding(
        url=settings.embedding_url,
        shared_secret=settings.embedding_shared_secret,
        timeout_seconds=settings.embedding_timeout_seconds,
        embed_batch_size=settings.embed_batch_size,
    )
    try:
        reader = SimpleDirectoryReader(input_dir=str(docs_dir), required_exts=[".pdf"])
        documents = reader.load_data()
        logger.info("loaded %d documents from %s", len(documents), docs_dir)

        splitter = SentenceSplitter(
            chunk_size=settings.chunk_size,
            chunk_overlap=settings.chunk_overlap,
            chunking_tokenizer_fn=split_by_regex(_SENTENCE_SPLIT_RE),
        )
        nodes = splitter.get_nodes_from_documents(documents)
        logger.info("split into %d nodes (chunk_size=%d token)", len(nodes), settings.chunk_size)

        # 先预嵌入（耗时步骤）：失败则不触碰既有 collection，旧索引保持可用
        embed(nodes)

        client = build_qdrant_client(settings)
        # 全量重建（幂等）：不同 pypdf 版本/分块差异会导致 node id 漂移，
        # upsert 无法覆盖旧节点；部署重复构建时先删 collection 避免累积重复。
        # delete 放在预嵌入之后：不可用窗口仅剩写入阶段（秒级）。
        if client.collection_exists(settings.qdrant_collection):
            logger.info(
                "dropping existing collection %s for full rebuild",
                settings.qdrant_collection,
            )
            client.delete_collection(settings.qdrant_collection)
        vector_store = QdrantVectorStore(
            collection_name=settings.qdrant_collection,
            client=client,
            dense_config=rest.VectorParams(size=EMBEDDING_DIMENSION, distance=rest.Distance.COSINE),
        )
        storage_context = StorageContext.from_defaults(vector_store=vector_store)
        # 空索引 + insert_nodes：内部完成嵌入并写入 Qdrant
        # （预嵌入后 insert 会重复嵌入一次，属 LlamaIndex 无跳过逻辑的已知代价，可接受）
        index = VectorStoreIndex(
            nodes=[],
            embed_model=embed,
            storage_context=storage_context,
        )
        index.insert_nodes(nodes, show_progress=True)
        logger.info(
            "index written: collection=%s documents=%d nodes=%d",
            settings.qdrant_collection,
            len(documents),
            len(nodes),
        )
        return {"documents": len(documents), "nodes": len(nodes)}
    finally:
        embed.close()


def main() -> None:
    parser = argparse.ArgumentParser(description="Build NUS policy RAG index into Qdrant")
    parser.add_argument(
        "--docs-dir", default=None, help="PDF 目录（默认取 POLICY_RAG_DOCS_DIR 或 docs/nus_docs）"
    )
    parser.add_argument("--qdrant-url", default=None, help="Qdrant 地址（默认取 POLICY_RAG_QDRANT_URL）")
    args = parser.parse_args()

    settings = PolicyRagSettings()
    if args.docs_dir:
        settings.docs_dir = args.docs_dir
    if args.qdrant_url:
        settings.qdrant_url = args.qdrant_url
    if not settings.embedding_shared_secret or len(settings.embedding_shared_secret) < 16:
        raise SystemExit(
            "POLICY_RAG_EMBEDDING_SHARED_SECRET 未配置（复用 LOST_FOUND_EMBEDDING_SHARED_SECRET 的同一密钥）"
        )

    stats = build_index(settings)
    print(
        f"OK: {stats['documents']} documents, {stats['nodes']} nodes "
        f"indexed into {settings.qdrant_collection}"
    )


if __name__ == "__main__":
    main()
