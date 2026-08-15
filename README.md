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

| Module | Technology |
|---|---|
| Orchestration (core) | Python 3.12, FastAPI, LangGraph, MCP SDK |
| Backend | Java 21, Spring Boot 4.1, Spring Security, JWT |
| Web | React 19, TypeScript, Vite, MUI, Axios |
| Data | MySQL 8 |
| Images | Private MinIO bucket with 15-minute presigned URLs |
| Testing | JUnit 5, Mockito, H2, Vitest, Testing Library, pytest |

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

Stop the infrastructure without deleting its data with `docker compose stop`. Use `docker compose down` to remove the containers while retaining the named volumes.

## Agent Platform (Core)

The platform core is a campus **AI assistant** (natural-language chat + multi-domain Agent orchestration):

- **Orchestration**: `agent/chat_core` (FastAPI + LangGraph; intent routing, agent invocation, HITL human approval, LLM fallback)
- **Agents**: MCP servers under `agent/mcp_servers/` (mail / facility / lost-found / utility-tools),
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

## API Reference

Authentication remains public:

```text
POST /api/auth/register
POST /api/auth/login
```

All Lost & Found endpoints require `Authorization: Bearer <token>`:

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/api/lost-found/reports` | Create a multipart report |
| `GET` | `/api/lost-found/reports` | Filter and page reports |
| `GET` | `/api/lost-found/reports/{reportId}` | Get report details |
| `GET` | `/api/lost-found/metadata` | Get enum values |
| `POST` | `/api/lost-found/reports/{reportId}/claims` | Submit ownership proof |
| `GET` | `/api/lost-found/claims/mine` | List claims submitted by the user |
| `GET` | `/api/lost-found/claims/received` | List claims received by the user |
| `POST` | `/api/lost-found/claims/{claimId}/approve` | Approve a received claim |
| `POST` | `/api/lost-found/claims/{claimId}/reject` | Reject a received claim |

Administrator-only Lost & Found endpoints:

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/admin/lost-found/overview` | Get report and pending-claim metrics |
| `GET` | `/api/admin/lost-found/reports` | Filter and page all reports for operations review |

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

PR CI runs backend and frontend tests, lint, production builds, CodeQL, blocking high-severity SpotBugs checks, npm vulnerability auditing, and dependency-change review. The nightly workflow adds deeper SpotBugs analysis, OWASP dependency checking, and ZAP scanning. A CD workflow (`cd-deploy.yml`) deploys to a single DigitalOcean Droplet via SSH on `main` push — see `DEPLOYMENT.md` for setup.

## Project Structure

```text
project/
├── agent/                   Agent system (platform core)
│   ├── chat_core/           Orchestration (FastAPI + LangGraph; intent routing / HITL / LLM fallback)
│   ├── mcp_servers/         MCP server adapters (mail/facility/lost-found/utility)
│   ├── lost_found_agent/    L&F business engine (rules + LLM intent parsing)
│   └── schemas/             Agent capability declarations (JSON Schema)
├── backend/
│   └── src/main/java/com/app/campusagent/
│       ├── chat/            Chat relay (SSE) + Token Service endpoints
│       └── lostfound/       L&F web business (controller/ dto/ domain/ exception/ …)
├── frontend_web/            React Web app (chat + L&F pages) and public/admin-test.html
├── services/
│   └── lost_found_embedding/ Standalone pretrained E5/CLIP service
├── frontend_mobile/         Future mobile client
├── docker-compose.yml       MySQL, MinIO, and optional model profile
└── docs/
```
