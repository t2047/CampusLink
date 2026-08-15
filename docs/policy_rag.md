# 政策/规章制度 RAG（Policy RAG）

在 Utility MCP Server（`agent/mcp_servers/utility_server.py`）下新增的
`search_policy` 工具：检索 `docs/nus_docs/` 下的 NUS 政策/规章制度 PDF
（学生守则、考试条例、评估规则等），返回最相关的条款段落及来源（文件名+页码）。

## 架构

```
[离线构建] docs/nus_docs/*.pdf
  → llama-index-readers-file 的 PDFReader（pypdf，按页，metadata 带 file_name/page_label）
  → SentenceSplitter 分块（chunk_size=512 token，正则切句绕开 nltk，纯离线）
  → HttpEmbedding（自定义 BaseEmbedding，HTTP 调 lost-found-embedding 的
    intfloat/multilingual-e5-small，384 维）
  → QdrantVectorStore（collection: nus_policy，COSINE，自动建集合）
[运行时] utility-mcp → search_policy(query)
  → PolicyRetriever（懒加载 VectorStoreIndex.from_vector_store + as_retriever Top-K）
  → 返回 [{text, score, source: 文件名#页码}]，fail-open（服务不可用返回 status=failed）
```

- **复用 embedding**：不加载 torch/sentence-transformers，模型保留在
  `services/lost_found_embedding`（multimodal profile），仅 HTTP 调用其 `/v1/embed/text`。
- **依赖**：`llama-index-core 0.14.x` / `llama-index-readers-file 0.6.x` /
  `llama-index-vector-stores-qdrant 0.10.x`（wheel 内置 tiktoken/nltk 缓存，离线可用）。
- **LlamaIndex 0.14 注意**：`SimpleNodeParser` 已移除，用 `SentenceSplitter`；
  其 chunk_size 单位为 token。

## 构建索引（一次性/文档更新时）

前置：Qdrant 与 lost-found-embedding 服务在线。

```bash
# 本地（multimodal profile 启动 embedding；qdrant 默认启动）
docker compose --profile multimodal up -d qdrant lost-found-embedding

# 在 agent/ 环境执行（PYTHONPATH 指向仓库根）
export POLICY_RAG_EMBEDDING_SHARED_SECRET=${LOST_FOUND_EMBEDDING_SHARED_SECRET}
python -m mcp_servers.policy_rag.indexer --docs-dir docs/nus_docs
```

输出示例：`OK: 29 documents, N nodes indexed into nus_policy`

## 配置（env 前缀 `POLICY_RAG_`）

| 变量 | 默认 | 说明 |
|---|---|---|
| `POLICY_RAG_QDRANT_URL` | `http://localhost:6333` | Qdrant REST 地址 |
| `POLICY_RAG_QDRANT_COLLECTION` | `nus_policy` | 集合名 |
| `POLICY_RAG_EMBEDDING_URL` | `http://localhost:8091` | 复用 lost-found-embedding |
| `POLICY_RAG_EMBEDDING_SHARED_SECRET` | 空 | 与 `LOST_FOUND_EMBEDDING_SHARED_SECRET` 同值 |
| `POLICY_RAG_TOP_K` | `5` | 默认返回段落数 |
| `POLICY_RAG_DOCS_DIR` / `POLICY_RAG_CHUNK_SIZE` / `POLICY_RAG_CHUNK_OVERLAP` | `docs/nus_docs` / 512 / 100 | 构建脚本专用 |

compose 中 utility-mcp 已注入 `POLICY_RAG_QDRANT_URL`、`POLICY_RAG_EMBEDDING_URL`、
`POLICY_RAG_EMBEDDING_SHARED_SECRET` 并 depends_on `qdrant`。

## 编排层接入

`agent/chat_core/orchestration/graph/nodes.py`：
- `UTILITY_CAPABILITIES` 注册 `search_policy`
- 意图提示词规则 2：政策/规章制度查询归 `utility`，targets=`["search_policy"]`
- `_extract_utility_params` 支持 `search_policy` 的 query 提取

检索结果由编排层 `_rephrase_utility_results` 交给 LLM 用与用户相同的语言重述（含来源）。
