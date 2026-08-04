# CampusLink

[![English](https://img.shields.io/badge/English-blue?style=flat-square)](./README.md) [![中文文档](https://img.shields.io/badge/中文-blue?style=flat-square)](./README_cn.md)

> 🚧 **Sprint 0** — Authentication & Role System complete. Remaining modules under development.

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

On first startup, a **SUPER_ADMIN** account is created automatically using credentials from `.env` (`SUPER_ADMIN_EMAIL` / `SUPER_ADMIN_PASSWORD`).

---

## API Reference

### Authentication (public)

```
POST /api/auth/register   — Register  { email, password }  → Returns JWT + role (always STUDENT)
POST /api/auth/login      — Login     { email, password }  → Returns JWT + role
```

All auth responses include the user's role:

```json
{
  "token": "eyJhbG...",
  "email": "user@example.com",
  "role": "STUDENT"
}
```

### Admin (authenticated)

| Endpoint | Method | Role Required | Description |
|----------|--------|---------------|-------------|
| `/api/admin/users` | `GET` | `ADMIN`, `SUPER_ADMIN` | List all users |
| `/api/admin/users` | `POST` | `SUPER_ADMIN` | Create a user with specified role |
| `/api/admin/users/{id}/role` | `PUT` | `SUPER_ADMIN` | Change a user's role |

#### Create user with role (SUPER_ADMIN only)

```json
POST /api/admin/users
{
  "email": "newadmin@example.com",
  "password": "Secure123!",
  "role": "ADMIN"
}
```

Allowed roles: `STUDENT`, `ADMIN`. (Cannot create another `SUPER_ADMIN` via API.)

#### Change user role (SUPER_ADMIN only)

```json
PUT /api/admin/users/3/role
{
  "role": "ADMIN"
}
```

---

## Role System

| Role | Access |
|------|--------|
| `STUDENT` | Default role on public registration. Access to core features only. |
| `ADMIN` | Can view the user list. Created by SUPER_ADMIN. |
| `SUPER_ADMIN` | Full access. Can create users with any role, change roles, and view the user list. Auto-created on startup. |

### Role Hierarchy

```
SUPER_ADMIN (manage roles, create admins)
   └── ADMIN (view users, manage content)
       └── STUDENT (default, core features)
```

---

## Testing the Admin Panel

Open `frontend_web/admin-test.html` in a browser to test:

1. **Login** — Enter SUPER_ADMIN credentials (default: `admin@campuslink.com` / `Admin123!`)
2. **Create User** — Register a new user with `STUDENT` or `ADMIN` role
3. **View Users** — List all registered users
4. **Change Role** — Update any user's role (cannot change your own)

---

## Project Structure

```
teamXX-ad-project/
├── backend/               ← Spring Boot backend
│   └── src/main/java/com/app/campusagent/
│       ├── config/        ← Security, JWT, CORS, DataInitializer
│       ├── controller/    ← AuthController, AdminController
│       ├── domain/        ← User entity, Role enum
│       ├── dto/           ← AuthResponse, UpdateRoleRequest, etc.
│       ├── exception/     ← GlobalExceptionHandler
│       ├── repository/    ← UserRepository
│       └── service/       ← AuthService
├── frontend_web/          ← Web frontend & test pages
├── frontend_mobile/       ← Mobile app
├── ml-service/            ← ML recommendation engine
├── docs/                  ← Documentation
└── scripts/               ← Utility scripts
```

---

*This document is in early development and will be updated as the project progresses.*
