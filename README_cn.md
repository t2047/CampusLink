# CampusLink

[![English](https://img.shields.io/badge/English%20Version-blue?style=flat-square)](./README.md)
[![中文文档](https://img.shields.io/badge/中文-blue?style=flat-square)](./README_cn.md)

当前文档仅为早期开发，需要更新
> 🚧 **Sprint 0** — 登录认证模块已完成，其余模块待开发。

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

---

## API

```
POST /api/auth/register   — 注册  { email, password }
POST /api/auth/login      — 登录  { email, password }  → 返回 JWT
```

---

## 项目结构

```
teamXX-ad-project/
├── backend/           ← Spring Boot 后端
├── web-client/        ← 前端 Web
├── mobile-client/     ← 移动端
├── ml-service/        ← ML 推荐引擎
├── docs/              ← 文档
└── scripts/           ← 脚本
```

*当前文档仅为早期开发，需要更新*
