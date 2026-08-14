# Lost & Found Embedding Service

This internal FastAPI service loads revision-pinned Multilingual-E5 and CLIP models for semantic text, image-to-image, and optional multilingual text-to-image embeddings. It never accesses MySQL, MinIO, or user identity data.

The Docker Compose `multimodal` profile starts it on demand; normal project startup does not load model weights. Model files are cached in a Docker named volume, and ordinary CI uses a fake runtime.

```bash
export LOST_FOUND_EMBEDDING_SHARED_SECRET=replace-with-at-least-16-characters
uv sync --frozen --all-extras
uv run uvicorn lost_found_embedding.main:app --host 127.0.0.1 --port 8091
```

Every `/v1/embed/*` request requires `X-Embedding-Service-Key`. Set `LOST_FOUND_CROSS_MODAL_ENABLED` to `auto`, `on`, or `off`; `auto` retains E5 and image-to-image matching if the multilingual text model cannot load.
