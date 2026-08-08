# CampusLink

[![English](https://img.shields.io/badge/English%20Version-blue?style=flat-square)](./README.md) [![中文文档](https://img.shields.io/badge/中文-blue?style=flat-square)](./README_cn.md)

CampusLink 是校园服务平台。目前已形成一条可运行的 Web 端 Lost & Found 垂直功能：用户登录后可发布遗失/拾获记录、上传图片、筛选搜索、提交认领证明，并由拾获记录发布者处理申请。

Lost & Found Agent 的开发状态、技术债和后续功能统一记录在[中文技术路线文档](docs/lost-found/TECHNICAL_ROADMAP_cn.md)中。

## 技术栈

| 模块 | 技术 |
|---|---|
| 后端 | Java 21、Spring Boot 4.1、Spring Security、JWT |
| Web 前端 | React 19、TypeScript、Vite、MUI、Axios |
| 数据库 | MySQL 8 |
| 图片存储 | 私有 MinIO Bucket、15 分钟预签名 URL |
| 测试 | JUnit 5、Mockito、H2、Vitest、Testing Library |

## 本地启动

需要预先安装 Java 21、Docker Desktop 和 Node.js 22 或更高版本。

```bash
# 在项目根目录执行
cp .env.example .env
# 编辑 .env，设置 JWT_SECRET 并替换示例密码
openssl rand -base64 64

# 后端会从 backend/ 读取本地 .env
cp .env backend/.env

# 启动 MySQL 和 MinIO
docker compose up -d
```

如需启动 Spring Boot + Lost & Found Agent 可选联调环境，还需填写 `.env.example` 中的三个 Agent 密钥（每个可用 `openssl rand -hex 32` 生成），然后执行：

```bash
docker compose --profile agent up -d --build
```

`LOST_FOUND_LLM_API_KEY` 可以保持为空，`auto` 模式会使用规则引擎。普通 `docker compose up -d` 仍只启动 MySQL 和 MinIO。

在第二个终端启动后端：

```bash
cd backend
./mvnw spring-boot:run
```

在第三个终端启动 React：

```bash
cd frontend_web
cp .env.example .env.local
npm ci
npm run dev
```

访问 [http://localhost:5173](http://localhost:5173)。MinIO 控制台为 [http://localhost:9001](http://localhost:9001)，原管理员测试页保留在 [http://localhost:5173/admin-test.html](http://localhost:5173/admin-test.html)。

`docker compose stop` 可停止基础设施但保留容器；`docker compose down` 会移除容器，但仍保留命名数据卷。

## Lost & Found 功能

- 发布 `LOST` 或 `FOUND` 记录；图片可不上传，也可上传最多 5 张 JPEG、PNG 或 WebP，每张不超过 10 MB。
- 按关键词、类别、颜色、地点、日期范围、记录类型和状态组合筛选。
- 通过私有且会过期的图片地址查看详情。
- 对开放的拾获记录提交认领证明；不能认领自己发布的记录，也不能重复提交有效申请。
- 拾获记录发布者可以批准或拒绝；批准后记录变为 `CLAIMED`，其他待处理申请自动拒绝。
- 认领证明只对申请人与拾获记录发布者可见。
- `ADMIN` 和 `SUPER_ADMIN` 可以使用只读管理页面查看统计、筛选全部记录、分页浏览和识别记录发布者。

本阶段不包含 AI 匹配、Agent、通知、移动端、管理员写操作、记录编辑和删除。

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

PR 流水线会执行前后端测试、Lint、生产构建、CodeQL、高危 SpotBugs 阻断、npm 漏洞审计和依赖变更审查。夜间流水线继续执行更深入的 SpotBugs、OWASP 依赖检查和 ZAP 扫描。当前尚未指定部署环境，因此不会擅自启用 CD。

## 项目结构

```text
project/
├── backend/
│   └── src/main/java/com/app/campusagent/lostfound/
│       ├── controller/  dto/  domain/  exception/
│       ├── repository/  service/  storage/
├── frontend_web/        React Web 和 public/admin-test.html
├── frontend_mobile/     后续移动端
├── ml-service/          后续匹配/分析服务
├── docker-compose.yml   MySQL 与 MinIO
└── docs/
```
