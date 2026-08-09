# CampusLink

[![English](https://img.shields.io/badge/English-blue?style=flat-square)](./README.md) [![中文文档](https://img.shields.io/badge/中文-blue?style=flat-square)](./README_cn.md)

CampusLink is a campus-service platform. The current vertical slice provides authentication and a Web-based Lost & Found workflow: publish lost/found reports, upload images, search, submit ownership claims, and review received claims.

Lost & Found Agent development status and future work are maintained in the [Chinese technical roadmap](docs/lost-found/TECHNICAL_ROADMAP_cn.md).

## Tech Stack

| Module | Technology |
|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring Security, JWT |
| Web | React 19, TypeScript, Vite, MUI, Axios |
| Data | MySQL 8 |
| Images | Private MinIO bucket with 15-minute presigned URLs |
| Testing | JUnit 5, Mockito, H2, Vitest, Testing Library |

## Local Setup

Prerequisites: Java 21, Docker Desktop, and Node.js 22 or later.

```bash
# From the repository root
cp .env.example .env
# Set JWT_SECRET and replace the example passwords in .env
openssl rand -base64 64

# The backend reads its local .env from backend/
cp .env backend/.env

# Start MySQL and MinIO
docker compose up -d
```

To run the optional Spring Boot + Lost & Found Agent integration stack, also set the three Agent secrets from `.env.example` (generate each with `openssl rand -hex 32`) and run:

```bash
docker compose --profile agent up -d --build
```

`LOST_FOUND_LLM_API_KEY` may remain empty; `auto` mode then uses the rule engine. The normal `docker compose up -d` command remains unchanged and starts only MySQL and MinIO.

Start the backend in a second terminal:

```bash
cd backend
./mvnw spring-boot:run
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

## Lost & Found Features

- Publish `LOST` and `FOUND` reports with zero to five JPEG, PNG, or WebP images (10 MB maximum per image).
- Search reports by keyword, category, colour, location, date range, type, and status.
- View report details through private, expiring image URLs.
- Submit ownership proof for an open found item. Users cannot claim their own report or submit a duplicate active claim.
- Let the found-item reporter approve or reject a claim. Approval marks the report `CLAIMED` and rejects its other pending claims.
- Keep ownership proof visible only to the claimant and the report publisher.
- Give `ADMIN` and `SUPER_ADMIN` users a read-only operational overview with report metrics, filters, pagination, and reporter identification.
- Let authenticated users try the real Lost & Found Agent from the main page with multi-turn input, write confirmation, search, and candidate links. Agent secrets remain server-side.

Embedding and multimodal image matching, notifications, mobile UI, administrator write actions, report editing, and report deletion remain outside this iteration.

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
```

PR CI runs backend and frontend tests, lint, production builds, CodeQL, blocking high-severity SpotBugs checks, npm vulnerability auditing, and dependency-change review. The nightly workflow adds deeper SpotBugs analysis, OWASP dependency checking, and ZAP scanning. A deployment target has not been configured, so CD remains intentionally disabled.

## Project Structure

```text
project/
├── backend/
│   └── src/main/java/com/app/campusagent/lostfound/
│       ├── controller/  dto/  domain/  exception/
│       ├── repository/  service/  storage/
├── frontend_web/        React Web app and public/admin-test.html
├── frontend_mobile/     Future mobile client
├── ml-service/          Future matching/analytics services
├── docker-compose.yml   MySQL and MinIO
└── docs/
```
