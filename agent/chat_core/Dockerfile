# ─── CampusLink Chat Orchestration (Python) ───
FROM python:3.12-slim

WORKDIR /app

# Dependencies
COPY agent/chat_core/pyproject.toml agent/chat_core/requirements.txt* ./
RUN pip install --no-cache-dir -e ".[dev]" 2>/dev/null || \
    pip install --no-cache-dir fastapi uvicorn[standard] python-dotenv httpx

# Source
COPY agent/chat_core/orchestration ./orchestration

# Non-root user
RUN useradd --create-home --shell /bin/bash app && chown -R app:app /app
USER app

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')" || exit 1

EXPOSE 8000
CMD ["uvicorn", "orchestration.main:app", "--host", "0.0.0.0", "--port", "8000"]
