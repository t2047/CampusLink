# Lost & Found Embedding Service

该内部服务负责加载固定版本的 Multilingual-E5 与 CLIP 模型，为失物招领提供文本、图片和可选的中文图文共享空间向量。它不访问数据库、MinIO 或用户身份信息。

服务通过 Docker Compose 的 `multimodal` profile 按需启动，模型保存在命名卷中，不会提交到 Git。普通 CI 使用 Fake Runtime，不下载真实模型。

```bash
export LOST_FOUND_EMBEDDING_SHARED_SECRET=replace-with-at-least-16-characters
uv sync --frozen --all-extras
uv run uvicorn lost_found_embedding.main:app --host 127.0.0.1 --port 8091
```

请求 `/v1/embed/*` 必须携带 `X-Embedding-Service-Key`。`LOST_FOUND_CROSS_MODAL_ENABLED` 支持 `auto`、`on`、`off`；`auto` 加载失败时保留 E5 和图片对图片能力。
