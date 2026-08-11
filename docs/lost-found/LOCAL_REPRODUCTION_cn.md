# Lost & Found + Chat Core 本地完整复现指南

本文用于从一个新的 Git 工作副本复现以下链路：

```text
React Web
→ Spring Boot Chat Backend
→ Chat Core 编排层
→ Lost & Found MCP / REST Agent
→ Spring Boot Lost & Found Service
→ MySQL / MinIO
```

## 1. 前置条件

- Docker Desktop 已安装并启动。
- Git 已安装。
- 建议至少为 Docker Desktop 分配 6 GB 内存。
- 完整 Chat Core 自然语言路由需要 DeepSeek/OpenAI-compatible API Key；无 Key 时基础 Web 和 Lost & Found REST Agent 的规则模式仍可使用，但不能完整复现 Chat Core 的模型路由效果。

## 2. 获取复现分支

```bash
git fetch origin
git switch --track origin/agent/lost-found-chatcore-reproducible
```

如果本地已存在同名分支：

```bash
git switch agent/lost-found-chatcore-reproducible
git pull --ff-only
```

## 3. 创建本地配置

```bash
cp .env.example .env
```

编辑根目录 `.env`，至少确认以下配置。真实密钥只能保存在 `.env`，不得提交到 Git：

```dotenv
MYSQL_PASSWORD=仅供本地使用的数据库密码
JWT_SECRET=至少32字符的随机值
AGENT_SHARED_SECRET=至少32字符的随机值
AGENT_BACKEND_SHARED_SECRET=另一个至少32字符的随机值
LOST_FOUND_CONFIRMATION_SECRET=另一个至少32字符的随机值

LOST_FOUND_AGENT_MODE=auto
LOST_FOUND_LLM_API_KEY=你的API密钥
LOST_FOUND_LLM_BASE_URL=https://api.deepseek.com
LOST_FOUND_LLM_MODEL=deepseek-v4-flash
LOST_FOUND_LLM_TIMEOUT_SECONDS=30
```

可以使用以下命令分别生成随机值：

```bash
openssl rand -hex 32
```

Compose 会把 `LOST_FOUND_LLM_API_KEY` 同时提供给 Lost & Found Agent 和 Chat Core 编排层，因此本地复现不需要保存两份相同密钥。

## 4. 构建并启动

在仓库根目录执行：

```bash
docker compose --profile agent up -d --build
```

首次构建需要下载 Java、Node.js 和 Python 依赖，时间取决于网络速度。不要只执行普通的 `docker compose up`，否则可选的 8083 REST Agent 不会启动，Lost & Found 页面中的模块测试面板将无法使用。

## 5. 检查服务

```bash
docker compose --profile agent ps
curl http://localhost:8080/actuator/health
curl http://localhost:8000/health
curl http://localhost:8083/health
curl http://localhost:8085/health
```

预期四个健康接口均成功，主要入口如下：

| 入口 | 地址 |
|---|---|
| Web | `http://localhost` |
| 统一 Chat Core | `http://localhost/chat` |
| Lost & Found 页面 | `http://localhost/lost-found` |
| Spring Boot API | `http://localhost:8080` |
| Chat Core 编排层 | `http://localhost:8000` |
| Lost & Found REST Agent | `http://localhost:8083` |
| Lost & Found MCP | `http://localhost:8085/mcp/` |
| MinIO 控制台 | `http://localhost:9001` |

## 6. 验收场景

1. 在 Web 注册并登录测试账户。
2. 打开 Lost & Found 页面，在模块测试面板输入：

   ```text
   我前天在UHC找到一把红色的伞，为我创建
   ```

3. 系统应返回拾获信息摘要和确认请求，而不是执行搜索并返回 `no_match`。
4. 确认后应创建一条 `FOUND + OPEN` 记录，并可在列表中查看。
5. 打开统一 Chat Core，输入：

   ```text
   帮我搜索在UHC拾获的红色雨伞
   ```

6. 页面应显示 `lost-found-agent` 和 `search_found_items` 执行步骤，随后返回候选结果或明确的无候选结果；执行步骤不应永久停留在“处理中”。

## 7. 常见问题

- `编排层暂时不可用`：检查 `orchestration` 容器是否健康，以及 Compose 中是否使用 `http://orchestration:8000`，不能在容器内使用 `localhost:8000`。
- MCP 返回 `421 Misdirected Request`：确认 MCP 容器具有 `FASTMCP_HOST=0.0.0.0`。
- Agent 提示模型不可用：检查 API Key、模型名称、账户额度和网络；不要把 Key 发到群聊或提交到仓库。
- 端口占用：检查本机的 `80`、`443`、`8000`、`8080`、`8083`、`8085`、`9000` 和 `9001`。
- 旧镜像未更新：重新执行 `docker compose --profile agent up -d --build`。

## 8. 停止服务

```bash
docker compose --profile agent down
```

该命令会移除本项目容器和网络，但保留 MySQL、MinIO 等命名数据卷。只有在明确不再需要测试数据时才考虑额外删除数据卷。
