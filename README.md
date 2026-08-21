# CampusLink

[![English](https://img.shields.io/badge/English-blue?style=flat-square)](./README.md) [![中文文档](https://img.shields.io/badge/中文-blue?style=flat-square)](./README_cn.md)

CampusLink is a campus **AI Agent platform**: the `agent/chat_core` orchestration layer (FastAPI + LangGraph)
is the core, driving multi-domain Agents (mail / facility / lost-found / utility-tools) over the
MCP protocol for chat Q&A and business operations, with SSE streaming and HITL human approval.

**Lost & Found is the first fully shipped vertical slice of the platform**: a complete Web workflow
(publish/search/claim/admin) plus Agent integration through `agent/mcp_servers/lost_found_server.py` —
users can report/find/claim in natural language, and write operations are persisted only after
explicit user confirmation.

Lost & Found Agent development status and future work are maintained in the [Chinese technical roadmap](docs/lost-found/TECHNICAL_ROADMAP_cn.md).
Pretrained model boundaries and evaluation are documented in the [Chinese multimodal matching guide](docs/lost-found/PRETRAINED_MULTIMODAL_MATCHING_cn.md).

## Tech Stack

| Module               | Technology                                            |
| -------------------- | ----------------------------------------------------- |
| Orchestration (core) | Python 3.12, FastAPI, LangGraph, MCP SDK              |
| Backend              | Java 21, Spring Boot 4.1, Spring Security, JWT        |
| Web                  | React 19, TypeScript, Vite, MUI, Axios                |
| Android              | Kotlin, Jetpack Compose, Room/SQLCipher, OkHttp SSE   |
| Data                 | MySQL 8                                               |
| Images               | Private MinIO bucket with 15-minute presigned URLs    |
| Testing              | JUnit 5, Mockito, H2, Vitest, Testing Library, pytest |

## Local Setup

Prerequisites: Java 21, Docker Desktop, and Node.js 22 or later.

```bash
# From the repository root
cp .env.example .env
# Set JWT_SECRET and replace the example passwords in .env
openssl rand -base64 64

# The backend reads its local .env from backend/
cp .env backend/.env

# Start infrastructure, Spring Boot, Chat Core, MCP services, and the Mail REST service
docker compose up -d
```

## Admin Facilities Dashboard

The web admin area includes a Facilities module at `/admin/facilities` with:

- Dashboard: a searchable and sortable table of all facilities, including status and recent reservation count.
- Reservations: administrator access to reservations from all user accounts, with filtering and sorting by applicant, date, status, or ID.
- Maintenance: administrator access to all maintenance requests, with status and priority filters and status updates.
- Facility reservation details: select a facility to view its reservation calendar and open individual reservation details.

The administrator overview also includes Lost & Found and Facilities KPI summaries. The Facilities summary shows total facilities, available facilities, today's reservations, and facilities under maintenance.

Administrator-only endpoints:

```text
GET /api/admin/facilities/bookings
GET /api/admin/facilities/bookings/{bookingId}
GET /api/admin/facilities/maintenance
GET /api/admin/facilities/maintenance/{ticketId}
PATCH /api/facilities/maintenance/{ticketId}/status
GET /api/admin/facilities/analytics
```

New bookings are currently confirmed automatically after availability and conflict checks. Administrator approval workflow is not enabled yet.

The Mail module (Gmail REST + Calendar + ML classifier + LangChain agent) runs as
the `mail-service` container on port 5000 (OAuth callback
`http://localhost:5000/callback` — keep the host port at 5000). Gmail tokens
(`token.json`) and the SQLite calendar (`calendar.db`) persist in the `mail_data`
named volume. Chat mail requests are answered by the mail module's own LangChain
agent via `mail-agent-mcp` (falls back to rule-based dispatch when no LLM key is
configured). See [agent/mail_agent/README.md](agent/mail_agent/README.md).

To add the optional Lost & Found REST Agent used by the module test panel, also set the three Agent secrets from `.env.example` (generate each with `openssl rand -hex 32`) and run:

```bash
docker compose --profile agent up -d --build
```

To opt into Multilingual-E5, CLIP image-to-image matching, and optional multilingual text-to-image matching:

```bash
docker compose --profile agent --profile multimodal up -d --build
```

Model files live in a Docker named volume. Normal startup does not load them, and report creation plus matching automatically fall back to the existing baseline when the model service is unavailable.

The **Policy & Regulation RAG** (`search_policy` utility tool) reuses the same multimodal embedding service and stores vectors in Qdrant (started with the default stack). To (re)build the index from `docs/nus_docs/`:

```bash
docker compose --profile multimodal up -d lost-found-embedding
docker compose --profile multimodal run --rm policy-index-builder
```

See [docs/policy_rag.md](docs/policy_rag.md) for details.

`LOST_FOUND_LLM_API_KEY` may remain empty; `auto` mode then uses the rule engine. The normal `docker compose up -d` starts the platform base stack but does not expose the optional REST Agent on port 8083.

For non-Docker development, start the backend in a second terminal:

```bash
cd backend
./mvnw spring-boot:run
```

Start the Chat Core orchestration layer in another terminal (dev mode with hot reload; full setup in [agent/chat_core/README.md](agent/chat_core/README.md)):

```bash
cd agent/chat_core
uvicorn orchestration.main:app --host 0.0.0.0 --port 8000 --reload
```

Start the React app in a third terminal:

```bash
cd frontend_web
cp .env.example .env.local
npm ci
npm run dev
```

Open [http://localhost:5173](http://localhost:5173). MinIO Console is available at [http://localhost:9001](http://localhost:9001). The legacy administrator test page is preserved at [http://localhost:5173/admin-test.html](http://localhost:5173/admin-test.html).

Build the native Android Core Chat demo with:

```bash
cd frontend_mobile
./gradlew testDemoDebugUnitTest lintDemoDebug detekt assembleDemoDebug
```

`demoDebug` connects only to `https://campuslink.tokeninf.xyz/`. See the [Android README](frontend_mobile/README.md).

Stop the infrastructure without deleting its data with `docker compose stop`. Use `docker compose down` to remove the containers while retaining the named volumes.

## Agent Platform (Core)

The platform core is a campus **AI assistant** (natural-language chat + multi-domain Agent orchestration):

- **Orchestration**: `agent/chat_core` (FastAPI + LangGraph; intent routing, agent invocation, HITL human approval, LLM fallback)
- **Agents**: MCP servers under `agent/mcp_servers/` (mail / facility / lost-found / utility-tools —
  calculator, current time, unit conversion, web search, and **policy/regulation RAG** via `search_policy`),
  exposed over streamable HTTP and registered via capability declarations in `agent/schemas/*.json`
- **Frontend**: chat entry at the React app home page (typing/SSE streaming, intent display, HITL confirmations — confirming resumes the suspended graph via `POST /api/chat/resume` and re-invokes the sub-agent with `confirmed=true` so the write actually happens; lost-report / claim confirmation flows are live)
- **Security**: RS256 Delegation Token chain — see [docs/communication-security.md](docs/communication-security.md)
- **Local dev**: [agent/chat_core/README.md](agent/chat_core/README.md) (start orchestration + MCP servers)
- **Agent interface contract** (for implementers): [docs/AGENT_INTERFACE_NOTICE.md](docs/AGENT_INTERFACE_NOTICE.md)

## Lost & Found Features (first vertical slice)

Web workflow of the L&F sub-module (Agent integration is covered in “Agent Platform (Core)” above):

- Publish `LOST` and `FOUND` reports with zero to five JPEG, PNG, or WebP images (10 MB maximum per image).
- Search reports by keyword, category, colour, location, date range, type, and status.
- View report details through private, expiring image URLs.
- Submit ownership proof for an open found item. Users cannot claim their own report or submit a duplicate active claim.
- Let the found-item reporter approve or reject a claim. Approval marks the report `CLAIMED` and rejects its other pending claims.
- Keep ownership proof visible only to the claimant and the report publisher.
- Give `ADMIN` and `SUPER_ADMIN` users a read-only operational overview with report metrics, filters, pagination, and reporter identification.
- Let authenticated users try the real Lost & Found Agent from the main page with multi-turn lost/found reporting, write confirmation, search, and candidate links. Agent secrets remain server-side.

Lost & Found now supports Multilingual-E5 text semantics, CLIP image-to-image similarity, optional multilingual text-to-image similarity, structured-field fusion, and automatic baseline fallback. Notifications and the mobile UI remain outside this iteration.

## Policy & Regulation RAG

`search_policy` answers questions about NUS policies and regulations (student code of conduct, examination rules, assessment guidelines) by retrieving from the 29 PDFs in `docs/nus_docs/`:

- **Pipeline**: LlamaIndex 0.14 (PDF → chunking → embedding) → Qdrant vector store (`nus_policy`, 384-dim COSINE) → Top-K retrieval at query time
- **Embedding reuse**: calls the shared `lost-found-embedding` service (`intfloat/multilingual-e5-small`) over HTTP — no model is loaded in the utility container
- **Offline-ready**: `llama-index-core` wheels bundle tiktoken/nltk caches; chunking uses a regex sentence splitter to avoid network downloads
- **Index lifecycle**: `policy-index-builder` one-shot service rebuilds the index (idempotent full rebuild); CD runs it on every deploy
- **Fallback**: service outages return `status=failed` instead of failing the chat

See [docs/policy_rag.md](docs/policy_rag.md).

## API Reference

Authentication remains public:

```text
POST /api/auth/register
POST /api/auth/login
```

All Lost & Found endpoints require `Authorization: Bearer <token>`:

| Method   | Endpoint                                      | Purpose                           |
| -------- | --------------------------------------------- | --------------------------------- |
| `POST` | `/api/lost-found/reports`                   | Create a multipart report         |
| `GET`  | `/api/lost-found/reports`                   | Filter and page reports           |
| `GET`  | `/api/lost-found/reports/{reportId}`        | Get report details                |
| `GET`  | `/api/lost-found/metadata`                  | Get enum values                   |
| `POST` | `/api/lost-found/reports/{reportId}/claims` | Submit ownership proof            |
| `GET`  | `/api/lost-found/claims/mine`               | List claims submitted by the user |
| `GET`  | `/api/lost-found/claims/received`           | List claims received by the user  |
| `POST` | `/api/lost-found/claims/{claimId}/approve`  | Approve a received claim          |
| `POST` | `/api/lost-found/claims/{claimId}/reject`   | Reject a received claim           |

Administrator-only Lost & Found endpoints:

| Method  | Endpoint                           | Purpose                                           |
| ------- | ---------------------------------- | ------------------------------------------------- |
| `GET` | `/api/admin/lost-found/overview` | Get report and pending-claim metrics              |
| `GET` | `/api/admin/lost-found/reports`  | Filter and page all reports for operations review |

Create a report:

```bash
curl -X POST http://localhost:8080/api/lost-found/reports \
  -H "Authorization: Bearer $TOKEN" \
  -F 'report={"reportType":"FOUND","itemName":"Black headphones","category":"ELECTRONICS","description":"Black headphones in a small scratched case","colour":"Black","location":"Central Library","eventDate":"2026-08-06","timeDescription":"Around 3 pm"};type=application/json' \
  -F 'images=@headphones.png;type=image/png'
```

Search open found items:

```bash
curl -H "Authorization: Bearer $TOKEN" \
  'http://localhost:8080/api/lost-found/reports?reportType=FOUND&status=OPEN&category=ELECTRONICS&colour=black&page=0&size=20&sort=createdAt,desc'
```

## Tests

```bash
cd backend
./mvnw test -DskipDependencyCheck=true -Dspotbugs.skip=true

cd ../frontend_web
npm run lint
npm test
npm run build

cd ../services/lost_found_embedding
uv sync --frozen --all-extras
uv run ruff check .
uv run mypy lost_found_embedding tests
uv run pytest
```

PR CI runs backend, frontend, and Android tests, lint, builds, CodeQL, and dependency review. Android CI publishes an installable demo APK and the nightly workflow runs emulator tests. The CD workflow deploys the server stack to AWS EC2 via SSH on `main` pushes — see `DEPLOYMENT.md`.

## Project Structure

```text
project/
├── agent/                   Agent system (platform core)
│   ├── chat_core/           Orchestration (FastAPI + LangGraph; intent routing / HITL / LLM fallback)
│   ├── mcp_servers/         MCP server adapters (mail/facility/lost-found/utility)
│   │   └── policy_rag/      Policy RAG: config/embedding/retriever/indexer (LlamaIndex + Qdrant)
│   ├── lost_found_agent/    L&F business engine (rules + LLM intent parsing)
│   └── schemas/             Agent capability declarations (JSON Schema)
├── backend/
│   └── src/main/java/com/app/campusagent/
│       ├── chat/            Chat relay (SSE) + Token Service endpoints
│       └── lostfound/       L&F web business (controller/ dto/ domain/ exception/ …)
├── frontend_web/            React Web app (chat + L&F pages) and public/admin-test.html
├── services/
│   └── lost_found_embedding/ Standalone pretrained E5/CLIP service
├── frontend_mobile/         Kotlin + Compose Core Chat Android client
├── docker-compose.yml       MySQL, MinIO, Qdrant, and optional model profile
└── docs/
```

## Individual Contribution — Zhao Lei

Zhao Lei's main contributions are:

- The basic full-stack Lost & Found workflow, including publishing `LOST` and `FOUND` reports,
  browsing, filtering, and viewing report details, excluding Claim and administrator features.
- The explainable matching system using pretrained Multilingual-E5 text embeddings and CLIP image
  embeddings, with structured-field scoring and fallback matching.
- The native Kotlin and Jetpack Compose mobile frontend, excluding Facilities, covering
  authentication, Core Chat, encrypted local history, Lost & Found screens, mobile Claim UI
  integration, Mail and Calendar, navigation, bilingual support, and dark mode.

## Individual Contribution — Xuhan Zhang

Xuhan Zhang's main contributions are:

- The full-stack Facilities System, covering facility search, space booking and cancellation, booking conflict detection, maintenance request submission, booking/request status tracking, role-based status management, database integration, and backend authorization.

- The Facilities Agent and MCP integration, including agent workflow implementation, conversation context and confirmation handling, date/time parsing, tool invocation, result mapping, and integration with the CampusLink Chat Core and Facilities backend.

- The shared Android mobile application development, with major contributions to mobile feature integration and overall UI/UX optimization, including navigation, responsive layouts, visual consistency, and cross-screen interface improvements, as well as the Facilities web frontend and related API integration.

- System integration and regression testing, including Facilities integration with shared authentication and security configuration, Chat Core/MCP connectivity, branch synchronization, merge conflict resolution, API verification, permission testing, and regression checks to ensure new Facilities functionality did not break existing CampusLink modules.

## Individual Contribution — JIA QIANRUI

JIA QIANRUI's main contributions are:

- The Claim module, covering the full claim lifecycle from submitting an ownership-proof claim
  on an open `FOUND` report to administrator-side approve/reject review with notifications.
- The Agent image-upload pipeline, staging images to MinIO with visual fingerprint and embedding
  generation, and image-based "search by image" retrieval on the Browse page.
- The Personal Center (`/lost-found/profile`) with profile and avatar updates, password change
  and JWT invalidation, and an aggregated view of the user's claims and reports.

## Individual Contribution — Wu Tianzhuo

Wu Tianzhuo's main contributions are:

- The full-stack Mail module (backend + web + agent) — a Gmail OAuth2-backed mail REST service (FastAPI) where each CampusLink user binds their own Gmail account (per-user token/identity resolution), a web mail page covering inbox reading, Gmail-style search, compose/send, archive, and delete, an MCP adapter, and a LangChain-based mail agent chat with fuzzy search that Core Chat routes to — including containerization (Docker/Compose) and prod deployment wiring.
- The email classification system — LLM-first automatic tagging of emails into campus / career / finance / other categories, with a trained scikit-learn ML model (joblib) as a fallback classifier, plus a refresh of the module README for the classification feature.
- The Calendar module — a per-user calendar service (event CRUD on SQLite) with automatic schedule extraction from emails via rule-based parsing with an LLM fallback (pre-filtering the Gmail window and raising the extract timeout to fix timeouts), a schedule import flow into the calendar, and a web Calendar page with a schedule-import dialog.
- Security & delivery hardening — removed hardcoded Gmail OAuth client secrets in favor of enforced environment-variable configuration, derived OAuth redirect URIs from the request origin for multi-host deployments, fixed mail tests to be non-UTC timezone stable, and added a dedicated mail & calendar CI pipeline (pytest / ruff / bandit / pip-audit + image build + CodeQL).

## Individual Contribution — Liu Zhuocheng

Liu Zhuocheng main contributions are:

- The Web Admin Dashboard foundation, including `ADMIN` and `SUPER_ADMIN` route protection, responsive administrative layouts, sidebar navigation, module entry points, and dedicated error pages.
- The Lost & Found administration frontend, including real-time overview metrics, claim filtering and pagination, evidence and report details, approval and rejection workflows, conflict handling, and API integration.
- The system-wide administration dashboard and reporting features, including cross-module KPIs, charts, operational monitoring tables, Facilities administration APIs, and the 30-day Administrative Usage Report.

## Individual Contribution — Cai Hanbo

Cai Hanbo's main contributions are:

- The admin-side Facilities Dashboard, covering overview statistics, booking search and filtering, maintenance ticket management, paginated sorting, and the supporting Spring Boot admin facilities API with dynamic queries and pagination validation.
- Frontend structure redeployment and UI/UX improvements, including workspace navigation and page layout refinements, unified page title styling, source-aware return buttons, full-height report forms, and a new CampusLink brand logo applied across the app.
- Cross-module consistency improvements, including a bilingual Facilities FAQ section, chat and Facilities navigation refinements, shared pagination builder extraction, and client-side aggregation replaced with server-side filtered queries.
