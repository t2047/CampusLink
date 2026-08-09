FROM python:3.12-slim AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /build
COPY pyproject.toml uv.lock README.md ./
COPY lost_found_agent ./lost_found_agent
RUN pip install --no-cache-dir uv \
    && uv export --frozen --no-dev --no-emit-project --format requirements-txt --output-file /dist/requirements.txt \
    && uv build --wheel --out-dir /dist

FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

RUN groupadd --system campuslink \
    && useradd --system --gid campuslink --create-home campuslink

WORKDIR /app
COPY --from=builder /dist/*.whl /tmp/
COPY --from=builder /dist/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir --require-hashes -r /tmp/requirements.txt \
    && pip install --no-cache-dir --no-deps /tmp/*.whl \
    && rm -f /tmp/*.whl /tmp/requirements.txt

USER campuslink
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8083/health', timeout=2)"

CMD ["uvicorn", "lost_found_agent.main:app", "--host", "0.0.0.0", "--port", "8083"]
