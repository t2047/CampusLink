# CampusLink

[![English](https://img.shields.io/badge/English%20Version-blue?style=flat-square)](./README.md) [![中文文档](https://img.shields.io/badge/中文-blue?style=flat-square)](./README_cn.md)

CampusLink 是校园 **AI Agent 平台**：以 `agent/chat_core` 编排层（FastAPI + LangGraph）为核心，
通过 MCP 协议驱动多领域 Agent（mail / facility / lost-found / utility-tools）完成
聊天问答与业务操作，支持 SSE 流式输出与 HITL 人工确认。

**Lost & Found 是平台首个完整落地的垂直切片**：既有 Web 端完整功能（发布/搜索/认领/管理），
也已通过 `agent/mcp_servers/lost_found_server.py` 适配层接入 Agent 体系——用户可以用自然语言
报失/登记拾获/查找/认领，写操作经用户确认后真正落库。

Lost & Found 的开发状态、技术债和后续功能统一记录在[中文技术路线文档](docs/lost-found/TECHNICAL_ROADMAP_cn.md)中。
需要复现 Web、Chat Core 和 Lost & Found Agent 完整链路时，请按[本地完整复现指南](docs/lost-found/LOCAL_REPRODUCTION_cn.md)操作。

## 技术栈

| 模块 | 技术 |
|---|---|
| 编排层（核心） | Python 3.12、FastAPI、LangGraph、MCP SDK |
| 后端 | Java 21、Spring Boot 4.1、Spring Security、JWT |
| Web 前端 | React 19、TypeScript、Vite、MUI、Axios |
| 数据库 | MySQL 8 |
| 图片存储 | 私有 MinIO Bucket、15 分钟预签名 URL |
| 测试 | JUnit 5、Mockito、H2、Vitest、Testing Library、pytest |

## 本地启动

需要预先安装 Java 21、Docker Desktop 和 Node.js 22 或更高版本。

```bash
# 在项目根目录执行
cp .env.example .env
# 编辑 .env，设置 JWT_SECRET 并替换示例密码
openssl rand -base64 64

# 后端会从 backend/ 读取本地 .env
cp .env backend/.env

# 启动基础设施、Spring Boot、Chat Core 与 MCP 服务
docker compose up -d
```

如需增加 Lost & Found 模块测试面板使用的可选 REST Agent，还需填写 `.env.example` 中的三个 Agent 密钥（每个可用 `openssl rand -hex 32` 生成），然后执行：

```bash
docker compose --profile agent up -d --build
```

`LOST_FOUND_LLM_API_KEY` 可以保持为空，`auto` 模式会使用规则引擎。普通 `docker compose up -d` 会启动平台基础栈，但不会在 8083 端口暴露这个可选 REST Agent。

如需脱离 Docker 开发，可在第二个终端启动后端：

```bash
cd backend
./mvnw spring-boot:run
```

在另一个终端启动编排层（开发模式，热重载；完整说明见 [agent/chat_core/README.md](agent/chat_core/README.md)）：

```bash
cd agent/chat_core
uvicorn orchestration.main:app --host 0.0.0.0 --port 8000 --reload
```

在第三个终端启动 React：

```bash
cd frontend_web
cp .env.example .env.local
npm ci
npm run dev
```

访问 [http://localhost:5173](http://localhost:5173)。MinIO 控制台为 [http://localhost:9001](http://localhost:9001)，原管理员测试页保留在 [http://localhost:5173/admin-test.html](http://localhost:5173/admin-test.html)。

统一聊天入口位于 [http://localhost:5173/chat](http://localhost:5173/chat)，Lost & Found 页面另保留模块级自然语言联调面板。

`docker compose stop` 可停止基础设施但保留容器；`docker compose down` 会移除容器，但仍保留命名数据卷。

## Agent 平台（核心）

平台核心是**校园 AI 助手**（自然语言聊天 + 多领域 Agent 调度）：

- **编排层**：`agent/chat_core`（FastAPI + LangGraph；意图路由、Agent 调用、HITL 人工确认、LLM 兜底）
- **Agent**：`agent/mcp_servers/` 下的 MCP Server（mail / facility / lost-found / utility-tools），
  以 streamable HTTP 暴露，按 `agent/schemas/*.json` 能力声明注册
- **前端**：React 应用首页的聊天入口（SSE 流式打字机、意图展示、HITL 确认——确认后
  经 `/api/chat/resume` 恢复挂起图并真正重调子 Agent 执行写操作，报失/认领等确认流程已可用）
- **安全**：RS256 Delegation Token 链路，见 [docs/communication-security.md](docs/communication-security.md)
- **本地开发**：[agent/chat_core/README.md](agent/chat_core/README.md)（编排层与 MCP Server 启动）
- **Agent 接口契约**（给实现者）：[docs/AGENT_INTERFACE_NOTICE.md](docs/AGENT_INTERFACE_NOTICE.md)

## Lost & Found 功能（首个垂直切片）

以下为 L&F 子模块的 Web 端功能（Agent 接入见上文「Agent 平台（核心）」）：

- 发布 `LOST` 或 `FOUND` 记录；图片可不上传，也可上传最多 5 张 JPEG、PNG 或 WebP，每张不超过 10 MB。
- 按关键词、类别、颜色、地点、日期范围、记录类型和状态组合筛选。
- 通过私有且会过期的图片地址查看详情。
- 对开放的拾获记录提交认领证明；不能认领自己发布的记录，也不能重复提交有效申请。
- 拾获记录发布者可以批准或拒绝；批准后记录变为 `CLAIMED`，其他待处理申请自动拒绝。
- 认领证明只对申请人与拾获记录发布者可见。
- `ADMIN` 和 `SUPER_ADMIN` 可以使用只读管理页面查看统计、筛选全部记录、分页浏览和识别记录发布者。
- 登录用户可以在 Lost & Found 首页通过自然语言测试 Agent，支持多轮补充、报失与登记拾获确认、搜索和候选结果跳转；浏览器不会接触 Agent 共享密钥。

当前 Lost & Found 已接入 Agent，使用规则重排和受控的 LLM 字段提取；尚不包含 Embedding、多模态图片匹配、通知、移动端、管理员写操作、记录编辑和删除。Agent 平台说明见上文“Agent 平台（核心）”。

## API

认证接口仍为公开接口：

```text
POST /api/auth/register
POST /api/auth/login
```

Lost & Found 接口均需携带 `Authorization: Bearer <token>`：

| 方法 | 接口 | 功能 |
|---|---|---|
| `POST` | `/api/lost-found/reports` | 创建 multipart 记录 |
| `GET` | `/api/lost-found/reports` | 条件筛选与分页 |
| `GET` | `/api/lost-found/reports/{reportId}` | 查看详情 |
| `GET` | `/api/lost-found/metadata` | 获取枚举元数据 |
| `POST` | `/api/lost-found/reports/{reportId}/claims` | 提交认领证明 |
| `GET` | `/api/lost-found/claims/mine` | 查看我提交的申请 |
| `GET` | `/api/lost-found/claims/received` | 查看我收到的申请 |
| `POST` | `/api/lost-found/claims/{claimId}/approve` | 批准申请 |
| `POST` | `/api/lost-found/claims/{claimId}/reject` | 拒绝申请 |

仅管理员可访问的 Lost & Found 接口：

| 方法 | 接口 | 功能 |
|---|---|---|
| `GET` | `/api/admin/lost-found/overview` | 获取记录和待处理认领统计 |
| `GET` | `/api/admin/lost-found/reports` | 筛选并分页查看全部记录 |

创建拾获记录示例：

```bash
curl -X POST http://localhost:8080/api/lost-found/reports \
  -H "Authorization: Bearer $TOKEN" \
  -F 'report={"reportType":"FOUND","itemName":"Black headphones","category":"ELECTRONICS","description":"Black headphones in a small scratched case","colour":"Black","location":"Central Library","eventDate":"2026-08-06","timeDescription":"Around 3 pm"};type=application/json' \
  -F 'images=@headphones.png;type=image/png'
```

筛选开放的拾获记录：

```bash
curl -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/lost-found/reports?reportType=FOUND&status=OPEN&category=ELECTRONICS&colour=black&page=0&size=20&sort=createdAt,desc'
```

## 测试

```bash
cd backend
./mvnw test -DskipDependencyCheck=true -Dspotbugs.skip=true

cd ../frontend_web
npm run lint
npm test
npm run build
```

PR 流水线会执行前后端测试、Lint、生产构建、CodeQL、高危 SpotBugs 阻断、npm 漏洞审计和依赖变更审查。夜间流水线继续执行更深入的 SpotBugs、OWASP 依赖检查和 ZAP 扫描。CD 流水线（`cd-deploy.yml`）在推送到 `main` 时通过 SSH 部署到单台 DigitalOcean Droplet——配置见 `DEPLOYMENT.md`。

## 项目结构

```text
project/
├── agent/                   Agent 体系（平台核心）
│   ├── chat_core/           编排层（FastAPI + LangGraph；意图路由/HITL/LLM 兜底）
│   ├── mcp_servers/         MCP Server 适配层（mail/facility/lost-found/utility）
│   ├── lost_found_agent/    L&F 业务引擎（规则 + LLM 意图解析）
│   └── schemas/             Agent 能力声明（JSON Schema）
├── backend/
│   └── src/main/java/com/app/campusagent/
│       ├── chat/            聊天中继（SSE）+ Token Service 端点
│       └── lostfound/       L&F Web 业务（controller/ dto/ domain/ exception/ …）
├── frontend_web/        React Web（聊天 + L&F 页面）和 public/admin-test.html
├── frontend_mobile/     后续移动端
├── ml-service/          后续匹配/分析服务
├── docker-compose.yml   MySQL 与 MinIO
└── docs/
```
