# CampusLink

[![English](https://img.shields.io/badge/English-blue?style=flat-square)](./README.md) [![中文文档](https://img.shields.io/badge/中文-blue?style=flat-square)](./README_cn.md)

> 🚧 **Sprint 0** — Authentication module complete. Remaining modules under development.

---

## Tech Stack

| Module | Technology |
|--------|------------|
| Backend | Java 21 · Spring Boot 3.4 · Spring Security · JWT |
| Database | MySQL 8 |
| CI/CD | GitHub Actions (SAST + SCA + DAST) |
| Testing | JUnit 5 · Mockito |

---

## Quick Start

```bash
# 1. Clone
git clone https://github.com/your-org/teamXX-ad-project.git
cd teamXX-ad-project

# 2. Configure environment variables
cp .env.example .env
# Edit .env and fill in your MySQL credentials

# 3. Generate JWT secret
openssl rand -base64 64
# Copy the output and replace JWT_SECRET in .env

# 4. Launch
cd backend
mvn spring-boot:run
```

---

## API

```
POST /api/auth/register   — Register  { email, password }
POST /api/auth/login      — Login     { email, password }  → Returns JWT
```

---

## Project Structure

```
teamXX-ad-project/
├── backend/           ← Spring Boot backend
├── web-client/        ← Web frontend
├── mobile-client/     ← Mobile app
├── ml-service/        ← ML recommendation engine
├── docs/              ← Documentation
└── scripts/           ← Utility scripts
```

---

*This document is in early development and will be updated as the project progresses.*
