# CampusLink Lost & Found Agent

这是 Lost & Found 领域 Agent 的独立 FastAPI 服务。当前已在无模型 API Key 的情况下支持中英文报失、登记拾获、搜索、详情和认领，支持多轮字段补充、写操作确认和可解释 Top 5 候选重排。

报失、登记拾获和认领在首次调用时只返回 10 分钟有效的确认 ID，确认前不会写数据库。确认 ID 与用户绑定且只能使用一次。

## 本地运行

```bash
# 先在仓库根目录创建统一配置文件
cp .env.example .env
cd agent/lost_found_agent
# 为三个共享密钥变量分别生成随机值，例如：openssl rand -hex 32
set -a && source ../../.env && set +a
python3.12 -m pip install uv
uv sync --all-extras
uv run uvicorn lost_found_agent.main:app --host 0.0.0.0 --port 8083
```

模型 API Key 可以为空；`auto` 模式会使用规则模式。服务安全密钥不能为空。

模型模式说明：

- `auto`：配置 `LOST_FOUND_LLM_API_KEY` 时使用 OpenAI-compatible 模型，否则使用规则引擎；
- `rules`：始终使用规则引擎；
- `llm`：强制使用模型，缺少 API Key 时拒绝启动。

模型只负责五种允许意图的识别和字段提取，输出必须通过 Pydantic 校验。默认 `LOST_FOUND_LLM_FAIL_CLOSED=true`，模型超时、限流、无效 JSON 或越权输出会明确失败；仅在显式设为 `false` 时降级到规则引擎。报失、登记拾获和认领仍由服务端确认流程控制，模型不能直接写数据库或绕过确认。当前默认使用 `deepseek-v4-flash`；可将 `LOST_FOUND_LLM_MODEL` 改为 `deepseek-v4-pro`，也可配合 `LOST_FOUND_LLM_BASE_URL` 接入其他 OpenAI-compatible 服务。

从仓库根目录可以启动整个 Agent 联调环境：

```bash
docker compose --profile agent up -d --build
```

未启用 `agent` profile 时，`docker compose up -d` 会启动平台基础栈，但不会启动 8083 端口的 Lost & Found REST Agent。启用 profile 前必须在根目录 `.env` 配置 `JWT_SECRET`、`SUPER_ADMIN_PASSWORD` 和三个 Agent 密钥；密钥可分别用 `openssl rand -hex 32` 生成，不能提交到 Git。

## 匹配与评估

候选重排权重：文字 28%、类别 28%、颜色 14%、地点 14%、日期 6%、图片 10%（缺失字段自动归一化）。文字相似度融合本地确定性哈希向量；当查询 `shared_data.visual_fingerprint`（`VF1:` 前缀）与候选 `visualFingerprints` 同时存在时启用图片相似度，并返回可解释的「图片特征相似」理由。

可复现评估工具（无需外部服务）：

```bash
# 匹配排序：对比 rule / embedding / multimodal 三个版本
uv run python -m lost_found_agent.matching_eval tests/fixtures/matching_regression.jsonl --variant all
# 真实模型批量评估：无 Key 时输出 skipped（exit 0）；有 Key 时输出质量、P95 延迟与费用
uv run python -m lost_found_agent.model_eval tests/fixtures/model_regression.jsonl --output model_report.json
```

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
