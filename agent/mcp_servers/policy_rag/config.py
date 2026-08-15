"""政策/规章制度 RAG 配置（policy_rag）。

独立于 lost-found-embedding 的 LOST_FOUND_EMBEDDING_* 配置前缀，
统一使用 POLICY_RAG_* 前缀；embedding 服务地址/密钥复用 L&F 同一实例。
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class PolicyRagSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="POLICY_RAG_",
        extra="ignore",
    )

    # 向量库（Qdrant）
    # 二选一：qdrant_url 连接远程服务（docker/生产）；qdrant_local_path 使用
    # qdrant-client 嵌入式本地模式（本地无 docker 测试用，path 为磁盘目录）
    qdrant_url: str = "http://localhost:6333"
    qdrant_local_path: str = ""
    qdrant_collection: str = "nus_policy"
    qdrant_timeout_seconds: float = 5.0

    # 复用 lost-found-embedding 服务的文本向量接口（intfloat/multilingual-e5-small，384 维）
    embedding_url: str = "http://localhost:8091"
    embedding_shared_secret: str = ""
    embedding_timeout_seconds: float = 8.0

    # 检索
    top_k: int = 5

    # 索引构建（离线脚本使用）
    docs_dir: str = "docs/nus_docs"
    chunk_size: int = 512  # token（SentenceSplitter 单位）
    chunk_overlap: int = 100  # token
    embed_batch_size: int = 32
