# ─── CampusLink Chat Orchestration (Python) ───
FROM python:3.12-slim

WORKDIR /app

# Dependencies
# 注意：pyproject 先 COPY、orchestration 源码后 COPY，若 editable install 因源码
# 目录缺失而失败会走 fallback，故 fallback 也需装全运行时依赖（含 langgraph/mcp）
COPY agent/chat_core/pyproject.toml ./
RUN pip install --no-cache-dir -e ".[dev]" 2>/dev/null || \
    pip install --no-cache-dir fastapi "uvicorn[standard]" python-dotenv httpx PyJWT \
        "mcp>=1.28,<2" "langgraph>=0.2" "langchain-core>=0.3" \
        "langchain-openai>=0.2" "langchain-community>=0.3" "pydantic>=2.7" "PyYAML>=6.0"

# Source
COPY agent/chat_core/orchestration ./orchestration
# 配置（services.yaml：服务注册表 / 安全段；容器内由 env_file 提供环境变量占位符）
COPY agent/chat_core/config ./config

# Non-root user
RUN useradd --create-home --shell /bin/bash app && chown -R app:app /app
USER app

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

EXPOSE 8000
CMD ["uvicorn", "orchestration.main:app", "--host", "0.0.0.0", "--port", "8000"]
