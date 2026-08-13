FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models \
    OMP_NUM_THREADS=2 \
    TOKENIZERS_PARALLELISM=false \
    PATH="/app/.venv/bin:$PATH"

RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app

COPY pyproject.toml uv.lock README_cn.md ./
COPY lost_found_embedding ./lost_found_embedding
RUN pip install --no-cache-dir uv==0.12.3 \
    && uv sync --frozen --no-dev --no-editable \
    && rm -rf /root/.cache/uv

RUN mkdir -p /models && chown -R app:app /app /models
USER app

EXPOSE 8091
HEALTHCHECK --interval=30s --timeout=10s --start-period=300s --retries=10 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8091/health/ready', timeout=8)"]

CMD ["uvicorn", "lost_found_embedding.main:app", "--host", "0.0.0.0", "--port", "8091"]
