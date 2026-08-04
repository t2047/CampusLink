# CampusLink

[![English](https://img.shields.io/badge/English%20Version-blue?style=flat-square)](./README.md)
[![中文文档](https://img.shields.io/badge/中文-blue?style=flat-square)](./README_cn.md)

> 🚧 **Sprint 0** — 登录认证 & 角色系统已完成，其余模块待开发。

---

## 已用技术栈

| 模块 | 技术 |
|------|------|
| 后端 | Java 21 · Spring Boot 3.4 · Spring Security · JWT |
| 数据库 | MySQL 8 |
| CI/CD | GitHub Actions (SAST + SCA + DAST) |
| 测试 | JUnit 5 · Mockito |

---

## 快速启动

```bash
# 1. 克隆
git clone https://github.com/your-org/teamXX-ad-project.git
cd teamXX-ad-project

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env 填入你的 MySQL 凭据

# 3. 生成 JWT 密钥
openssl rand -base64 64
# 复制输出，替换 .env 中的 JWT_SECRET

# 4. 启动
cd backend
mvn spring-boot:run
```

首次启动时，系统会根据 `.env` 中的 `SUPER_ADMIN_EMAIL` / `SUPER_ADMIN_PASSWORD` 自动创建超级管理员账号。

---

## API 参考

### 认证接口（公开）

```
POST /api/auth/register   — 注册  { email, password }  → 返回 JWT + role（固定为 STUDENT）
POST /api/auth/login      — 登录  { email, password }  → 返回 JWT + role
```

所有认证响应均包含用户角色：

```json
{
  "token": "eyJhbG...",
  "email": "user@example.com",
  "role": "STUDENT"
}
```

### 管理接口（需认证）

| 端点 | 方法 | 所需角色 | 说明 |
|------|------|----------|------|
| `/api/admin/users` | `GET` | `ADMIN`、`SUPER_ADMIN` | 查看所有用户 |
| `/api/admin/users` | `POST` | `SUPER_ADMIN` | 创建指定角色的用户 |
| `/api/admin/users/{id}/role` | `PUT` | `SUPER_ADMIN` | 修改用户角色 |

#### 创建指定角色用户（仅 SUPER_ADMIN）

```json
POST /api/admin/users
{
  "email": "newadmin@example.com",
  "password": "Secure123!",
  "role": "ADMIN"
}
```

可选角色：`STUDENT`、`ADMIN`（不可通过 API 创建 `SUPER_ADMIN`）。

#### 修改用户角色（仅 SUPER_ADMIN）

```json
PUT /api/admin/users/3/role
{
  "role": "ADMIN"
}
```

---

## 角色体系

| 角色 | 权限 |
|------|------|
| `STUDENT` | 公开注册默认角色，仅可使用核心功能 |
| `ADMIN` | 可查看用户列表，由 SUPER_ADMIN 创建 |
| `SUPER_ADMIN` | 全部权限，可创建任意角色用户、修改角色、查看用户列表。启动时自动创建 |

### 角色层级

```
SUPER_ADMIN （管理角色、创建管理员）
   └── ADMIN （查看用户、管理内容）
       └── STUDENT （默认用户、核心功能）
```

---

## 测试管理面板

在浏览器中打开 `frontend_web/admin-test.html`：

1. **登录** — 输入 SUPER_ADMIN 凭据（默认：`admin@campuslink.com` / `Admin123!`）
2. **创建用户** — 注册新用户，可选择 `STUDENT` 或 `ADMIN` 角色
3. **查看用户列表** — 列出所有已注册用户
4. **修改角色** — 更新任意用户角色（不可修改自己）

---

## 项目结构

```
teamXX-ad-project/
├── backend/               ← Spring Boot 后端
│   └── src/main/java/com/app/campusagent/
│       ├── config/        ← Security、JWT、CORS、DataInitializer
│       ├── controller/    ← AuthController、AdminController
│       ├── domain/        ← User 实体、Role 枚举
│       ├── dto/           ← AuthResponse、UpdateRoleRequest 等
│       ├── exception/     ← GlobalExceptionHandler
│       ├── repository/    ← UserRepository
│       └── service/       ← AuthService
├── frontend_web/          ← 前端 Web & 测试页面
├── frontend_mobile/       ← 移动端
├── ml-service/            ← ML 推荐引擎
├── docs/                  ← 文档
└── scripts/               ← 脚本
```

---

*当前文档为早期开发版本，随项目推进持续更新。*
