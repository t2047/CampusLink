# ─── CampusLink Agent / Utility MCP Server ───
# 运行：uvicorn mcp_servers.domain_server:app（Domain Agent，MCP_AGENT_NAME 选角）
#       uvicorn mcp_servers.utility_server:app（Utility Tools）
FROM python:3.12-slim

WORKDIR /app

# 依赖（mcp SDK + FastAPI 等；与编排层 pyproject 共用）
COPY agent/chat_core/pyproject.toml ./
RUN pip install --no-cache-dir -e ".[dev]" 2>/dev/null || \
    pip install --no-cache-dir mcp fastapi uvicorn[standard] python-dotenv httpx PyJWT

# 源码
COPY agent/mcp_servers ./mcp_servers

# 非 root 运行
RUN useradd --create-home --shell /bin/bash app && chown -R app:app /app
USER app

EXPOSE 8081
# compose 中通过 MCP_AGENT_NAME / command 覆盖
CMD ["uvicorn", "mcp_servers.domain_server:app", "--host", "0.0.0.0", "--port", "8081"]
