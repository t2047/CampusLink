# CampusLink Lost & Found Agent

这是 Lost & Found 领域 Agent 的独立 FastAPI 服务。当前已接入报失、搜索、详情和认领四个真实 Spring Boot 工具；自然语言规则对话会在下一阶段接入。

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

从仓库根目录可以启动整个 Agent 联调环境：

```bash
docker compose --profile agent up -d --build
```

未启用 `agent` profile 时，原有 `docker compose up -d` 仍只启动 MySQL 和 MinIO。启用 profile 前必须在根目录 `.env` 配置 `JWT_SECRET`、`SUPER_ADMIN_PASSWORD` 和三个 Agent 密钥；密钥可分别用 `openssl rand -hex 32` 生成，不能提交到 Git。

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
