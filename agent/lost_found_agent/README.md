# CampusLink Lost & Found Agent

这是 Lost & Found 领域 Agent 的独立 FastAPI 服务。当前阶段提供接口契约、安全通信、限流和 SSE 基础设施；后续 PR 将依次接入真实工具、规则对话和可选 LLM。

## 本地运行

```bash
cd agent/lost_found_agent
cp .env.example .env
# 为三个共享密钥变量分别生成随机值，例如：openssl rand -hex 32
python3.12 -m pip install uv
uv sync --all-extras
uv run uvicorn lost_found_agent.main:app --host 0.0.0.0 --port 8083
```

模型 API Key 可以为空；`auto` 模式会使用规则模式。服务安全密钥不能为空。

## 检查

```bash
uv run ruff check .
uv run ruff format --check .
uv run mypy lost_found_agent tests
uv run pytest
uv run bandit -r lost_found_agent -x tests -q
uv run pip-audit
```

接口契约见 `../schemas/lost-found-agent.json`，总体进度见 `../../docs/lost-found/TECHNICAL_ROADMAP_cn.md`。
