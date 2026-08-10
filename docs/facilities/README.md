# CampusLink Facilities System

## 1. Overview

Facilities System provides the CampusLink MVP flows for campus space search and booking, plus maintenance ticket submission and tracking.

```text
Search -> Check availability -> Book -> Track booking
Submit maintenance request -> Receive ticket ID -> Track ticket
```

The module runs inside the existing Spring Boot backend. It reuses the same MySQL instance, datasource, `User` entity, JWT authentication, error handler, and Spring Security filter chain. Facilities owns a separate `facility` schema/database on that instance; it is not a separate service or server.

## 2. Architecture

```text
CampusAgent / MCP client
        |
        | Streamable HTTP MCP (/mcp, Bearer JWT)
        v
FacilitiesMcpTools
        |
REST client -> FacilitiesController
        |              |
        +------> FacilitiesService
                       |
          Space / Booking / Maintenance repositories
                       |
             MySQL instance via JPA
                 /             \
     main schema (`users`)   `facility` schema
```

Business rules live in `FacilitiesService`; REST and MCP are thin adapters over the same service.

## 3. Directory structure

```text
backend/src/main/java/com/app/campusagent/facilities/
├── config/       # Database constants and idempotent demo-space seed data
├── controller/   # Authenticated REST endpoints
├── domain/       # JPA entities and status enums
├── dto/          # API and MCP structured responses
├── exception/    # Facilities error codes
├── mcp/          # MCP tool adapter
├── repository/   # Spring Data JPA repositories
└── service/      # Search, availability, booking, and maintenance rules

backend/src/test/java/com/app/campusagent/facilities/
├── mcp/          # Basic call/error tests for every MCP tool
├── service/      # H2-backed integration tests
└── FacilitiesSchemaIsolationTest.java # Physical schema/table isolation checks
```

## 4. Database entities

The application continues to use one datasource and Hibernate `ddl-auto=update`. The schema layout is:

```text
same MySQL instance
├── main schema from MYSQL_URL (example default: campusLink_db)
│   └── users
└── facility
    ├── spaces
    ├── space_equipment
    ├── bookings
    └── maintenance_tickets
```

| Table | Purpose | Key relationships |
|---|---|---|
| `facility.spaces` | Space identity, location, type, capacity, hours, and status | Referenced by bookings and tickets |
| `facility.space_equipment` | Equipment values for each space | FK to `facility.spaces` |
| `facility.bookings` | Confirmed/cancelled/completed user reservations | Stores the authenticated `users.id` as `user_id`; FK to `facility.spaces` |
| `facility.maintenance_tickets` | User-reported maintenance problems | Stores the authenticated `users.id` as `user_id`; optional FK to `facility.spaces` |

There is no `facility.users` table and no cross-schema user foreign key. Booking and ticket rows store a scalar `Long userId`, always obtained server-side from the authenticated existing `User`; REST or MCP clients cannot choose another user ID. Ownership queries remain scoped by this authenticated ID.

`db/facilities-schema.sql` runs through Spring SQL initialization before Hibernate and executes `CREATE SCHEMA IF NOT EXISTS facility`. The production MySQL account therefore needs permission to create `facility` on the first startup; subsequent startups are idempotent. Hibernate then creates or updates only the four mapped Facilities tables inside that schema.

## 5. REST APIs

All endpoints require `Authorization: Bearer <CampusLink JWT>`.

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/facilities/spaces` | List/search spaces; optional filters and time window |
| `GET` | `/api/facilities/spaces/{spaceId}` | Space details |
| `GET` | `/api/facilities/spaces/{spaceId}/availability` | Availability and conflict check |
| `POST` | `/api/facilities/bookings` | Create a booking for the authenticated user |
| `GET` | `/api/facilities/bookings` | List the authenticated user's bookings |
| `GET` | `/api/facilities/bookings/{bookingId}` | Track one owned booking |
| `PATCH` | `/api/facilities/bookings/{bookingId}/cancel` | Cancel one owned future booking without deleting it |
| `POST` | `/api/facilities/maintenance` | Submit a maintenance ticket |
| `GET` | `/api/facilities/maintenance` | List the authenticated user's tickets |
| `GET` | `/api/facilities/maintenance/{ticketId}` | Track one owned ticket |
| `PATCH` | `/api/facilities/maintenance/{ticketId}/status` | ADMIN/SUPER_ADMIN-only ticket status update |

Search query parameters are `query`, `building`, `spaceType`, `minimumCapacity`, repeated `equipment`, `startDateTime`, and `endDateTime`. Date-times use ISO local format, for example `2026-08-10T14:00:00`.

### Booking request example

```json
{
  "spaceId": 1,
  "startDateTime": "2026-08-10T14:00:00",
  "endDateTime": "2026-08-10T16:00:00"
}
```

### Maintenance request example

```json
{
  "spaceId": 1,
  "facilityType": "projector",
  "description": "Projector cannot turn on",
  "priority": "HIGH"
}
```

If `spaceId` is unknown, replace it with both `building` and `roomNumber`.

## 6. Business rules

- Minimum capacity must be at least 1.
- Start must be before end and both must be on the same day.
- A booking is at most four hours and at most 14 days in advance.
- Booking must be in the future and within the space's opening hours.
- Only spaces with status `AVAILABLE` can be booked.
- `CONFIRMED` bookings block a time window.
- Cancellation is idempotent for an already `CANCELLED` booking, preserves the row for auditability, and is allowed only for its owner before the start time. `COMPLETED` bookings cannot be cancelled.
- `CANCELLED` bookings do not block availability or conflict checks.
- Overlap uses `existing.start < requested.end && existing.end > requested.start`; touching edges do not conflict.
- Booking creation holds a pessimistic database lock for the selected space to prevent concurrent double-booking.
- A user sees only their own bookings and maintenance tickets.
- Maintenance status updates require the existing `ADMIN` or `SUPER_ADMIN` role. Allowed transitions are `SUBMITTED -> IN_PROGRESS/CANCELLED` and `IN_PROGRESS -> RESOLVED/CANCELLED`; repeating the current status is idempotent.

Facilities errors include stable codes such as `SPACE_NOT_FOUND`, `INVALID_TIME`, `SPACE_UNAVAILABLE`, `BOOKING_CONFLICT`, `BOOKING_NOT_FOUND`, `BOOKING_CANCELLATION_NOT_ALLOWED`, `TICKET_NOT_FOUND`, and `INVALID_MAINTENANCE_TRANSITION`.

## 7. MCP tools

The official Spring AI MCP Server 2.0 starter exposes Streamable HTTP at `http://localhost:8080/mcp`. The MCP request must forward the user's CampusLink JWT.

| Tool | Main parameters | Purpose |
|---|---|---|
| `search_spaces` | query, building, spaceType, minimumCapacity, equipment, optional time window | Find matching/available spaces |
| `get_space_details` | spaceId | Read a space |
| `check_availability` | spaceId, startDateTime, endDateTime | Validate hours and conflicts |
| `create_booking` | spaceId, startDateTime, endDateTime | Book for current user |
| `list_user_bookings` | none | List current user's bookings |
| `get_booking_status` | bookingId | Track owned booking |
| `cancel_booking` | bookingId | Cancel an owned future booking |
| `submit_maintenance_request` | spaceId or location, facilityType, description, priority | Create a ticket |
| `get_maintenance_status` | ticketId | Track owned ticket |
| `list_user_maintenance_requests` | none | Resolve status questions without a ticket ID |

The Facilities MCP surface contains 10 student-facing tools. Administrative maintenance updates are deliberately REST-only; `update_maintenance_status` is not exposed through MCP. Both the controller and service enforce the existing CampusLink administrator roles.

Each tool returns:

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

Failures return `success: false` with a structured `error.code` and `error.message`.

The authoritative Agent-side discovery contract is `agent/schemas/facility-agent.json`. A CampusAgent MCP client should connect to the `/mcp` URL and expose the discovered tools to its model. Natural-language interpretation remains the main Agent's responsibility.

## 8. Run

From the repository root in PowerShell:

```powershell
Set-Location backend
if (-not (Test-Path .env)) { Copy-Item ..\.env.example .env }
# Fill MYSQL_PASSWORD and a JWT_SECRET of at least 32 bytes in backend\.env
.\mvnw.cmd spring-boot:run
```

If the Maven wrapper cannot write to the normal Maven cache, use an existing Maven installation with a workspace-local cache:

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2-repository" spring-boot:run
```

On the first run, ensure the account in `MYSQL_URL` has `CREATE` permission for the `facility` schema. If database provisioning is handled by an administrator instead, they may create the empty `facility` schema once before starting the application.

## 9. Test

```powershell
Set-Location backend
.\mvnw.cmd test -DskipDependencyCheck=true
```

The test profile uses the project's existing H2 test database in MySQL compatibility mode and runs the same schema initialization script. `FacilitiesSchemaIsolationTest` verifies that the four new tables exist under `facility`, that `users` remains outside it, and that no legacy `facility_*` table is created. H2 is not used in production.

## 10. Demo flow

Register or log in first and copy the returned JWT:

```powershell
$base = "http://localhost:8080"
$login = Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType "application/json" -Body '{"email":"admin@campuslink.com","password":"Admin123!"}'
$headers = @{ Authorization = "Bearer $($login.token)" }
```

Search and book:

```powershell
$spaces = Invoke-RestMethod -Headers $headers -Uri "$base/api/facilities/spaces?minimumCapacity=4&equipment=projector&building=COM2"
$spaceId = $spaces[0].spaceId
$date = (Get-Date).AddDays(1).ToString("yyyy-MM-dd")
$bookingBody = @{ spaceId=$spaceId; startDateTime="${date}T14:00:00"; endDateTime="${date}T16:00:00" } | ConvertTo-Json
$booking = Invoke-RestMethod -Method Post -Headers $headers -Uri "$base/api/facilities/bookings" -ContentType "application/json" -Body $bookingBody
Invoke-RestMethod -Headers $headers -Uri "$base/api/facilities/bookings/$($booking.bookingId)"
```

Submit and track maintenance:

```powershell
$ticketBody = @{ spaceId=$spaceId; facilityType="projector"; description="Projector cannot turn on"; priority="HIGH" } | ConvertTo-Json
$ticket = Invoke-RestMethod -Method Post -Headers $headers -Uri "$base/api/facilities/maintenance" -ContentType "application/json" -Body $ticketBody
Invoke-RestMethod -Headers $headers -Uri "$base/api/facilities/maintenance/$($ticket.ticketId)"
```

## 11. Mock data

Startup inserts 15 named spaces only when each name is absent. Data spans COM1/COM2/COM3, Central Library, UTown, Engineering Auditorium, Science, MPSH, and USC; it includes study rooms, seminar rooms, labs, sports venues, and a lecture room. Capacities, equipment, and status vary, including one `OUT_OF_SERVICE` space.

## 12. Known limitations

- The repository currently contains no executable Main CampusAgent/orchestrator, only Agent schema files. Facilities is MCP-ready and its discovery contract is updated, but a Main Agent client connection cannot be committed until that module exists.
- Automatic booking completion, recommendations, calendars, notifications, images, and analytics are outside this MVP.
- The project currently uses Hibernate `ddl-auto=update`; there is no Flyway/Liquibase migration framework to extend.
- `ddl-auto=update` does not delete legacy tables. An existing developer database may retain the old default-schema `facility_*` tables until they are manually verified and removed.
- All seeded spaces use fixed daily opening hours and do not yet model holidays or per-day schedules.

## 13. Verify and clean an existing development database

Replace `campusLink_db` below if `MYSQL_URL` selects a different main schema.

```sql
SELECT table_schema, table_name
FROM information_schema.tables
WHERE table_schema IN ('campusLink_db', 'facility')
  AND (table_name = 'users' OR table_name LIKE 'facility_%'
       OR table_name IN ('spaces', 'space_equipment', 'bookings', 'maintenance_tickets'))
ORDER BY table_schema, table_name;
```

Expected result: `users` is in the main schema and the four unprefixed Facilities tables are in `facility`. After backing up any development data and confirming the application is using the new tables, remove only the exact legacy tables from the main schema, with dependent tables first:

```sql
DROP TABLE IF EXISTS campusLink_db.facility_bookings;
DROP TABLE IF EXISTS campusLink_db.facility_maintenance_tickets;
DROP TABLE IF EXISTS campusLink_db.facility_space_equipment;
DROP TABLE IF EXISTS campusLink_db.facility_spaces;
```

Do not drop the main schema, `users`, or the new `facility` schema.
