# Facilities REST API — Frontend Integration Guide

本文档面向 `frontend_web` 开发人员，以当前 `feature/facilities-chat-integration` 分支中的 `FacilitiesController`、DTO、Domain enum、`FacilitiesService`、`GlobalExceptionHandler` 和 `SecurityConfig` 为准。

## 1. Base URL 与认证

- 本地默认 Base URL：`http://localhost:8080`
- Facilities REST Base Path：`/api/facilities`
- 除 CORS 预检外，本文档中的全部 11 个 endpoint 都需要登录。
- Web 前端必须使用 `/api/auth/login` 或 `/api/auth/register` 返回的 CampusLink **HS256 login JWT**。
- 每次请求加入：

```http
Authorization: Bearer <token>
```

- 有 JSON body 的请求还应加入：

```http
Content-Type: application/json
```

不要让普通 Web 前端申请或使用 Chat Core 的 RS256 Delegation Token。RS256 token 只用于 Chat Core 调用 `/mcp`，并且不能通过 Facilities REST endpoint 建立登录身份。

当前 CampusLink 用户角色为：

| Role | Facilities REST 权限 |
|---|---|
| `STUDENT` | Space 查询、自己的 Booking、自己的 Maintenance 提交与查询 |
| `ADMIN` | 拥有 `STUDENT` 的能力，并可更新任意 Maintenance ticket 状态 |
| `SUPER_ADMIN` | 拥有 `STUDENT` 的能力，并可更新任意 Maintenance ticket 状态 |

未认证请求返回 HTTP `401`；已认证但角色不足返回 HTTP `403`。

## 2. 日期与时间格式

请求和响应中的 `startDateTime`、`endDateTime`、`createdAt`、`updatedAt` 使用 Java `LocalDateTime`，示例：

```text
2026-08-11T14:00:00
```

前端发送时：

- 不要添加 `Z`，例如不要发送 `2026-08-11T14:00:00Z`。
- 不要添加 timezone offset，例如不要发送 `2026-08-11T14:00:00+08:00`。
- 后端不会在 UTC 和浏览器时区之间自动换算；值代表 CampusLink 后端使用的本地日期时间。
- Query parameter 必须进行 URL 编码，建议使用 `URLSearchParams`。

Space 的 `openingTime`、`closingTime` 使用 Java `LocalTime`，响应格式为 ISO local time，例如 `08:00:00`、`22:00:00`。

Booking/availability 的时间规则：

- `startDateTime` 必须早于 `endDateTime`。
- 开始和结束必须在同一日期。
- 时间段最长 4 小时。
- 创建 booking 时，开始时间必须在未来，并且不能超过当前时间之后 14 天。
- 创建 booking 时，时间段必须在 space 开放时间内。
- Search 和 availability check 会校验同日及最长 4 小时，但不会要求时间必须在未来或 14 天内。

## 3. Enum

前端应发送和展示以下 canonical values。

### SpaceType

```text
STUDY_ROOM
SEMINAR_ROOM
SPORTS_VENUE
LAB
LECTURE_ROOM
```

`spaceType` 搜索参数不区分大小写，也会把空格或 `-` 转成 `_`；`ANY` 表示不按类型过滤。前端仍建议发送上面的 canonical values。

### SpaceStatus

```text
AVAILABLE
OUT_OF_SERVICE
INACTIVE
```

该字段当前只出现在响应中。只有 `AVAILABLE` space 可以成功预订。

### BookingStatus

```text
CONFIRMED
CANCELLED
COMPLETED
```

`CONFIRMED` 会阻挡重叠时段；`CANCELLED` 不参与冲突检测。

### MaintenancePriority

```text
LOW
MEDIUM
HIGH
```

提交时可省略；省略、`null` 或空字符串会使用 `MEDIUM`。非空值不区分大小写。

### MaintenanceStatus

```text
SUBMITTED
IN_PROGRESS
RESOLVED
CANCELLED
```

允许的状态转换：

```text
SUBMITTED  -> IN_PROGRESS | CANCELLED
IN_PROGRESS -> RESOLVED | CANCELLED
RESOLVED   -> 无后续状态
CANCELLED  -> 无后续状态
```

重复设置当前状态是幂等操作，返回 HTTP `200`。

## 4. 通用响应与错误格式

### Facilities 业务错误

```json
{
  "timestamp": "2026-08-10T16:30:00.123456",
  "status": 409,
  "code": "BOOKING_CONFLICT",
  "error": "The requested time overlaps an existing booking"
}
```

稳定的 Facilities error code 包括：

```text
SPACE_NOT_FOUND
INVALID_SPACE_TYPE
INVALID_CAPACITY
INVALID_TIME
SPACE_UNAVAILABLE
BOOKING_CONFLICT
BOOKING_NOT_FOUND
BOOKING_CANCELLATION_NOT_ALLOWED
TICKET_NOT_FOUND
INVALID_LOCATION
INVALID_MAINTENANCE_REQUEST
INVALID_MAINTENANCE_STATUS
INVALID_MAINTENANCE_TRANSITION
INVALID_PRIORITY
AUTHENTICATION_REQUIRED
```

### Bean Validation 错误

```json
{
  "timestamp": "2026-08-10T16:30:00.123456",
  "status": 400,
  "errors": {
    "spaceId": "must not be null",
    "startDateTime": "must not be null"
  }
}
```

### 401 认证错误

Security filter 使用 `sendError(401, "Unauthorized")`。Spring Boot 生成的响应 body 可能包含 `timestamp`、`status`、`error`、`path` 等字段，例如：

```json
{
  "timestamp": "2026-08-10T16:30:00.123+08:00",
  "status": 401,
  "error": "Unauthorized",
  "path": "/api/facilities/spaces"
}
```

前端必须以 HTTP status 为准，不要依赖 401 body 的精确字段集合。收到 401 时清理失效登录状态并进入重新登录流程。

### 403 授权错误

```json
{
  "timestamp": "2026-08-10T16:30:00.123456",
  "status": 403,
  "error": "Access Denied"
}
```

Booking 和 Maintenance 成功响应中的 `success` 固定为 `true`。业务失败通过非 2xx HTTP status 和错误 JSON 返回，不会返回 `success: false` 的 REST response。

## 5. Space API

### 5.1 Search spaces

| 项目 | 内容 |
|---|---|
| 功能 | 搜索/筛选 spaces；提供时间窗口时只返回该时段可用的结果 |
| HTTP Method | `GET` |
| URL | `/api/facilities/spaces` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | `STUDENT`、`ADMIN` 或 `SUPER_ADMIN` |
| Request Body | 无 |

Path Parameters：无。

Query Parameters：

| 参数 | 类型 | Required | 说明 |
|---|---|---:|---|
| `query` | string | 否 | 对 space `name` 或 `roomNumber` 做去首尾空格、忽略大小写的包含匹配 |
| `building` | string | 否 | 对 building 做去首尾空格、忽略大小写的精确匹配 |
| `spaceType` | string | 否 | `SpaceType`；`ANY` 等同于不筛选 |
| `minimumCapacity` | integer | 否 | 最小容量，必须至少为 `1` |
| `equipment` | string[] | 否 | 必须同时包含全部指定设备；推荐使用重复参数，例如 `equipment=projector&equipment=whiteboard` |
| `startDateTime` | LocalDateTime | 否 | 可用性筛选开始；必须和 `endDateTime` 一起提供 |
| `endDateTime` | LocalDateTime | 否 | 可用性筛选结束；必须和 `startDateTime` 一起提供 |

Response JSON 示例（HTTP `200`）：

```json
[
  {
    "spaceId": 1,
    "name": "COM2 Project Room 1",
    "building": "COM2",
    "floor": "2",
    "roomNumber": "02-01",
    "spaceType": "STUDY_ROOM",
    "capacity": 6,
    "equipment": ["whiteboard", "display"],
    "openingTime": "08:00:00",
    "closingTime": "22:00:00",
    "status": "AVAILABLE"
  }
]
```

没有匹配结果时返回 `[]`，不是 404。结果没有分页，代码未承诺固定排序。

Error Response 示例（HTTP `400`）：

```json
{
  "timestamp": "2026-08-10T16:30:00.123456",
  "status": 400,
  "code": "INVALID_TIME",
  "error": "startDateTime and endDateTime must be provided together"
}
```

HTTP Status Code：

- `200 OK`：搜索成功，包括空数组。
- `400 Bad Request`：非法类型、容量、时间格式或时间范围。
- `401 Unauthorized`：未登录或 login JWT 无效。

前端调用注意事项：

- 使用 `URLSearchParams.append("equipment", value)` 发送多个设备。
- 提供时间筛选时必须同时发送 start/end。
- Equipment 匹配是“全部包含”，不是“任一包含”。
- 时间筛选会排除非 `AVAILABLE`、超出开放时间或已有冲突的 space。

### 5.2 Space details

| 项目 | 内容 |
|---|---|
| 功能 | 根据 ID 获取一个 space 的完整展示信息 |
| HTTP Method | `GET` |
| URL | `/api/facilities/spaces/{spaceId}` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | `STUDENT`、`ADMIN` 或 `SUPER_ADMIN` |
| Request Body | 无 |

Path Parameters：

| 参数 | 类型 | Required | 说明 |
|---|---|---:|---|
| `spaceId` | integer (int64) | 是 | Space ID |

Query Parameters：无。

Response JSON 示例（HTTP `200`）：

```json
{
  "spaceId": 1,
  "name": "COM2 Project Room 1",
  "building": "COM2",
  "floor": "2",
  "roomNumber": "02-01",
  "spaceType": "STUDY_ROOM",
  "capacity": 6,
  "equipment": ["whiteboard", "display"],
  "openingTime": "08:00:00",
  "closingTime": "22:00:00",
  "status": "AVAILABLE"
}
```

Error Response 示例（HTTP `404`）：

```json
{
  "timestamp": "2026-08-10T16:30:00.123456",
  "status": 404,
  "code": "SPACE_NOT_FOUND",
  "error": "Space not found: 99999"
}
```

HTTP Status Code：

- `200 OK`：查询成功。
- `400 Bad Request`：`spaceId` 不能转换为 Long 等请求格式错误。
- `401 Unauthorized`：未登录或 login JWT 无效。
- `404 Not Found`：space 不存在。

前端调用注意事项：

- `status` 不等于 `AVAILABLE` 时仍可展示 details，但不能成功预订。
- `equipment` 是 JSON array；不要假设其顺序固定。

### 5.3 Availability

| 项目 | 内容 |
|---|---|
| 功能 | 检查一个 space 在指定时间段的状态、开放时间和 booking 冲突 |
| HTTP Method | `GET` |
| URL | `/api/facilities/spaces/{spaceId}/availability` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | `STUDENT`、`ADMIN` 或 `SUPER_ADMIN` |
| Request Body | 无 |

Path Parameters：

| 参数 | 类型 | Required | 说明 |
|---|---|---:|---|
| `spaceId` | integer (int64) | 是 | Space ID |

Query Parameters：

| 参数 | 类型 | Required | 说明 |
|---|---|---:|---|
| `startDateTime` | LocalDateTime | 是 | 检查开始时间 |
| `endDateTime` | LocalDateTime | 是 | 检查结束时间 |

Response JSON 示例（HTTP `200`，可用）：

```json
{
  "available": true,
  "reasonCode": null,
  "space": {
    "spaceId": 1,
    "name": "COM2 Project Room 1",
    "building": "COM2",
    "floor": "2",
    "roomNumber": "02-01",
    "spaceType": "STUDY_ROOM",
    "capacity": 6,
    "equipment": ["whiteboard", "display"],
    "openingTime": "08:00:00",
    "closingTime": "22:00:00",
    "status": "AVAILABLE"
  },
  "startDateTime": "2026-08-11T14:00:00",
  "endDateTime": "2026-08-11T16:00:00"
}
```

不可用的合法检查仍返回 HTTP `200`，例如：

```json
{
  "available": false,
  "reasonCode": "BOOKING_CONFLICT",
  "space": {
    "spaceId": 1,
    "name": "COM2 Project Room 1",
    "building": "COM2",
    "floor": "2",
    "roomNumber": "02-01",
    "spaceType": "STUDY_ROOM",
    "capacity": 6,
    "equipment": ["whiteboard", "display"],
    "openingTime": "08:00:00",
    "closingTime": "22:00:00",
    "status": "AVAILABLE"
  },
  "startDateTime": "2026-08-11T14:00:00",
  "endDateTime": "2026-08-11T16:00:00"
}
```

`reasonCode` 可能为 `SPACE_UNAVAILABLE`、`INVALID_TIME` 或 `BOOKING_CONFLICT`。

Error Response 示例（HTTP `400`）：

```json
{
  "timestamp": "2026-08-10T16:30:00.123456",
  "status": 400,
  "code": "INVALID_TIME",
  "error": "Start time must be before end time"
}
```

HTTP Status Code：

- `200 OK`：检查完成；使用 `available` 判断是否可用。
- `400 Bad Request`：缺少时间、时间格式错误或时间范围非法。
- `401 Unauthorized`：未登录或 login JWT 无效。
- `404 Not Found`：space 不存在。

前端调用注意事项：

- 不要把 `available: false` 当作 HTTP error。
- Availability 是检查结果，不会锁定时段；创建 booking 时后端会再次检查冲突。
- 两个 booking 首尾相接不算重叠，例如已有 `14:00–15:00` 时可请求 `15:00–16:00`。

## 6. Booking API

### 6.1 Create booking

| 项目 | 内容 |
|---|---|
| 功能 | 为当前登录用户创建一个 `CONFIRMED` booking |
| HTTP Method | `POST` |
| URL | `/api/facilities/bookings` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | `STUDENT`、`ADMIN` 或 `SUPER_ADMIN` |

Path Parameters：无。

Query Parameters：无。

Request Body：

| 字段 | JSON 类型 | Required | 说明 |
|---|---|---:|---|
| `spaceId` | number (int64) | 是 | 目标 space ID，`@NotNull` |
| `startDateTime` | string | 是 | ISO local date-time，`@NotNull` |
| `endDateTime` | string | 是 | ISO local date-time，`@NotNull` |

```json
{
  "spaceId": 1,
  "startDateTime": "2026-08-11T14:00:00",
  "endDateTime": "2026-08-11T16:00:00"
}
```

Response JSON 示例（HTTP `201`）：

```json
{
  "success": true,
  "bookingId": 42,
  "space": {
    "spaceId": 1,
    "name": "COM2 Project Room 1",
    "building": "COM2",
    "floor": "2",
    "roomNumber": "02-01",
    "spaceType": "STUDY_ROOM",
    "capacity": 6,
    "equipment": ["whiteboard", "display"],
    "openingTime": "08:00:00",
    "closingTime": "22:00:00",
    "status": "AVAILABLE"
  },
  "startDateTime": "2026-08-11T14:00:00",
  "endDateTime": "2026-08-11T16:00:00",
  "status": "CONFIRMED",
  "createdAt": "2026-08-10T16:30:00.123456",
  "updatedAt": "2026-08-10T16:30:00.123456"
}
```

Error Response 示例（HTTP `409`）：

```json
{
  "timestamp": "2026-08-10T16:30:00.123456",
  "status": 409,
  "code": "BOOKING_CONFLICT",
  "error": "The requested time overlaps an existing booking"
}
```

HTTP Status Code：

- `201 Created`：booking 创建成功。
- `400 Bad Request`：缺少字段、时间非法、超出开放时间、超过 4 小时、不是未来时间或超过 14 天。
- `401 Unauthorized`：未登录或 login JWT 无效。
- `404 Not Found`：space 不存在。
- `409 Conflict`：space 状态不可预订或时间与已有 `CONFIRMED` booking 冲突。

前端调用注意事项：

- 不要发送 `userId`；后端只使用当前登录用户 ID。
- 即使刚检查过 availability，仍需处理创建时的 409，因为其他用户可能已抢先预订。
- `SPACE_UNAVAILABLE` 和 `BOOKING_CONFLICT` 都是 409；使用 `code` 区分。
- 建议成功后保存 `bookingId`，用于详情、追踪和取消。

### 6.2 List my bookings

| 项目 | 内容 |
|---|---|
| 功能 | 列出当前登录用户自己的全部 bookings |
| HTTP Method | `GET` |
| URL | `/api/facilities/bookings` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | `STUDENT`、`ADMIN` 或 `SUPER_ADMIN` |
| Request Body | 无 |

Path Parameters：无。

Query Parameters：无。

Response JSON 示例（HTTP `200`）：

```json
[
  {
    "success": true,
    "bookingId": 42,
    "space": {
      "spaceId": 1,
      "name": "COM2 Project Room 1",
      "building": "COM2",
      "floor": "2",
      "roomNumber": "02-01",
      "spaceType": "STUDY_ROOM",
      "capacity": 6,
      "equipment": ["whiteboard", "display"],
      "openingTime": "08:00:00",
      "closingTime": "22:00:00",
      "status": "AVAILABLE"
    },
    "startDateTime": "2026-08-11T14:00:00",
    "endDateTime": "2026-08-11T16:00:00",
    "status": "CONFIRMED",
    "createdAt": "2026-08-10T16:30:00.123456",
    "updatedAt": "2026-08-10T16:30:00.123456"
  }
]
```

没有 booking 时返回 `[]`。

Error Response 示例（HTTP `401`，body 为代表性示例）：

```json
{
  "timestamp": "2026-08-10T16:30:00.123+08:00",
  "status": 401,
  "error": "Unauthorized",
  "path": "/api/facilities/bookings"
}
```

HTTP Status Code：

- `200 OK`：查询成功，包括空数组。
- `401 Unauthorized`：未登录或 login JWT 无效。

前端调用注意事项：

- 后端按 `startDateTime` 降序返回。
- 当前没有分页参数。
- 返回范围已经按 authenticated user 隔离；前端不需要也不能传 `userId`。

### 6.3 Booking status/details

| 项目 | 内容 |
|---|---|
| 功能 | 获取当前登录用户拥有的一个 booking |
| HTTP Method | `GET` |
| URL | `/api/facilities/bookings/{bookingId}` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | `STUDENT`、`ADMIN` 或 `SUPER_ADMIN` |
| Request Body | 无 |

Path Parameters：

| 参数 | 类型 | Required | 说明 |
|---|---|---:|---|
| `bookingId` | integer (int64) | 是 | Booking ID |

Query Parameters：无。

Response JSON 示例（HTTP `200`）：

```json
{
  "success": true,
  "bookingId": 42,
  "space": {
    "spaceId": 1,
    "name": "COM2 Project Room 1",
    "building": "COM2",
    "floor": "2",
    "roomNumber": "02-01",
    "spaceType": "STUDY_ROOM",
    "capacity": 6,
    "equipment": ["whiteboard", "display"],
    "openingTime": "08:00:00",
    "closingTime": "22:00:00",
    "status": "AVAILABLE"
  },
  "startDateTime": "2026-08-11T14:00:00",
  "endDateTime": "2026-08-11T16:00:00",
  "status": "CONFIRMED",
  "createdAt": "2026-08-10T16:30:00.123456",
  "updatedAt": "2026-08-10T16:30:00.123456"
}
```

Error Response 示例（HTTP `404`）：

```json
{
  "timestamp": "2026-08-10T16:30:00.123456",
  "status": 404,
  "code": "BOOKING_NOT_FOUND",
  "error": "Booking not found"
}
```

HTTP Status Code：

- `200 OK`：owned booking 查询成功。
- `400 Bad Request`：`bookingId` 格式错误。
- `401 Unauthorized`：未登录或 login JWT 无效。
- `404 Not Found`：booking 不存在，或 booking 属于其他用户。

前端调用注意事项：

- 为避免泄露其他用户 booking 是否存在，访问他人的 booking 也返回同样的 404。
- 不要把 Admin 身份理解为可通过此 endpoint 查看所有人的 booking；该接口仍按当前用户 ownership 查询。

### 6.4 Cancel booking

| 项目 | 内容 |
|---|---|
| 功能 | 取消当前用户拥有的未来 booking，保留原记录用于审计 |
| HTTP Method | `PATCH` |
| URL | `/api/facilities/bookings/{bookingId}/cancel` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | `STUDENT`、`ADMIN` 或 `SUPER_ADMIN` |
| Request Body | 无 |

Path Parameters：

| 参数 | 类型 | Required | 说明 |
|---|---|---:|---|
| `bookingId` | integer (int64) | 是 | Booking ID |

Query Parameters：无。

Response JSON 示例（HTTP `200`）：

```json
{
  "success": true,
  "bookingId": 42,
  "space": {
    "spaceId": 1,
    "name": "COM2 Project Room 1",
    "building": "COM2",
    "floor": "2",
    "roomNumber": "02-01",
    "spaceType": "STUDY_ROOM",
    "capacity": 6,
    "equipment": ["whiteboard", "display"],
    "openingTime": "08:00:00",
    "closingTime": "22:00:00",
    "status": "AVAILABLE"
  },
  "startDateTime": "2026-08-11T14:00:00",
  "endDateTime": "2026-08-11T16:00:00",
  "status": "CANCELLED",
  "createdAt": "2026-08-10T16:30:00.123456",
  "updatedAt": "2026-08-10T16:40:00.654321"
}
```

Error Response 示例（HTTP `409`）：

```json
{
  "timestamp": "2026-08-10T16:40:00.654321",
  "status": 409,
  "code": "BOOKING_CANCELLATION_NOT_ALLOWED",
  "error": "A booking cannot be cancelled after its start time"
}
```

HTTP Status Code：

- `200 OK`：取消成功；已经 `CANCELLED` 时也幂等返回 200。
- `400 Bad Request`：`bookingId` 格式错误。
- `401 Unauthorized`：未登录或 login JWT 无效。
- `404 Not Found`：booking 不存在，或属于其他用户。
- `409 Conflict`：booking 已 `COMPLETED`，或开始时间已经到达/过去。

前端调用注意事项：

- 请求不需要 `{}` body。
- 取消不会 DELETE 数据；成功后用响应更新本地 `status` 和 `updatedAt`。
- 取消成功后该 booking 不再阻挡 availability。
- 前端可隐藏明显不可取消的按钮，但最终规则由后端强制执行。

## 7. Maintenance API

### 7.1 Submit maintenance request

| 项目 | 内容 |
|---|---|
| 功能 | 为当前用户提交 maintenance ticket |
| HTTP Method | `POST` |
| URL | `/api/facilities/maintenance` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | `STUDENT`、`ADMIN` 或 `SUPER_ADMIN` |

Path Parameters：无。

Query Parameters：无。

Request Body：

| 字段 | JSON 类型 | Required | 约束与行为 |
|---|---|---:|---|
| `spaceId` | number (int64) 或 null | 条件必填 | 有已知 space 时提供；提供后，后端使用该 space 的 building/room，忽略请求中的 location |
| `building` | string 或 null | 条件必填 | `spaceId` 为空时必须与 `roomNumber` 同时提供；最长 255 |
| `roomNumber` | string 或 null | 条件必填 | `spaceId` 为空时必须与 `building` 同时提供；最长 255 |
| `facilityType` | string | 是 | 非空，最长 255；这是问题类别文本，不是 `SpaceType` enum |
| `description` | string | 是 | 非空，最长 2000 |
| `priority` | string 或 null | 否 | `LOW`、`MEDIUM`、`HIGH`；默认 `MEDIUM` |

使用已知 `spaceId`：

```json
{
  "spaceId": 1,
  "facilityType": "projector",
  "description": "Projector cannot turn on",
  "priority": "HIGH"
}
```

使用手工 location：

```json
{
  "spaceId": null,
  "building": "COM2",
  "roomNumber": "02-15",
  "facilityType": "air conditioning",
  "description": "The room is unusually warm",
  "priority": "MEDIUM"
}
```

Response JSON 示例（HTTP `201`）：

```json
{
  "success": true,
  "ticketId": 88,
  "spaceId": 1,
  "spaceName": "COM2 Project Room 1",
  "building": "COM2",
  "roomNumber": "02-01",
  "facilityType": "projector",
  "description": "Projector cannot turn on",
  "priority": "HIGH",
  "status": "SUBMITTED",
  "createdAt": "2026-08-10T16:30:00.123456",
  "updatedAt": "2026-08-10T16:30:00.123456"
}
```

手工 location ticket 的 `spaceId` 和 `spaceName` 为 `null`。

Error Response 示例（HTTP `400`）：

```json
{
  "timestamp": "2026-08-10T16:30:00.123456",
  "status": 400,
  "code": "INVALID_LOCATION",
  "error": "Provide either spaceId or both building and roomNumber"
}
```

HTTP Status Code：

- `201 Created`：ticket 创建成功，初始状态为 `SUBMITTED`。
- `400 Bad Request`：location 不完整、必填文本为空、长度超限或 priority 非法。
- `401 Unauthorized`：未登录或 login JWT 无效。
- `404 Not Found`：提供了不存在的 `spaceId`。

前端调用注意事项：

- 两种 location 模式二选一。只要 `spaceId` 非 null，后端优先使用 space 数据。
- 如果 `spaceId` 无效，即使同时提供 building/room 也会返回 404，不会自动 fallback。
- `facilityType` 是自由文本，例如 `projector`，不要发送 `STUDY_ROOM` 作为问题类别，除非这确实是 UI 选择的文本。
- 建议成功后保存 `ticketId` 用于追踪。

### 7.2 List my maintenance requests

| 项目 | 内容 |
|---|---|
| 功能 | 列出当前登录用户提交的全部 maintenance tickets |
| HTTP Method | `GET` |
| URL | `/api/facilities/maintenance` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | `STUDENT`、`ADMIN` 或 `SUPER_ADMIN` |
| Request Body | 无 |

Path Parameters：无。

Query Parameters：无。

Response JSON 示例（HTTP `200`）：

```json
[
  {
    "success": true,
    "ticketId": 88,
    "spaceId": 1,
    "spaceName": "COM2 Project Room 1",
    "building": "COM2",
    "roomNumber": "02-01",
    "facilityType": "projector",
    "description": "Projector cannot turn on",
    "priority": "HIGH",
    "status": "SUBMITTED",
    "createdAt": "2026-08-10T16:30:00.123456",
    "updatedAt": "2026-08-10T16:30:00.123456"
  }
]
```

没有 ticket 时返回 `[]`。

Error Response 示例（HTTP `401`，body 为代表性示例）：

```json
{
  "timestamp": "2026-08-10T16:30:00.123+08:00",
  "status": 401,
  "error": "Unauthorized",
  "path": "/api/facilities/maintenance"
}
```

HTTP Status Code：

- `200 OK`：查询成功，包括空数组。
- `401 Unauthorized`：未登录或 login JWT 无效。

前端调用注意事项：

- 后端按 `createdAt` 降序返回。
- 当前没有分页参数。
- 即使是 Admin，该接口也只列出 Admin 自己提交的 tickets，不是管理端全量列表。

### 7.3 Maintenance status/details

| 项目 | 内容 |
|---|---|
| 功能 | 获取当前登录用户拥有的一个 maintenance ticket |
| HTTP Method | `GET` |
| URL | `/api/facilities/maintenance/{ticketId}` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | `STUDENT`、`ADMIN` 或 `SUPER_ADMIN` |
| Request Body | 无 |

Path Parameters：

| 参数 | 类型 | Required | 说明 |
|---|---|---:|---|
| `ticketId` | integer (int64) | 是 | Maintenance ticket ID |

Query Parameters：无。

Response JSON 示例（HTTP `200`）：

```json
{
  "success": true,
  "ticketId": 88,
  "spaceId": 1,
  "spaceName": "COM2 Project Room 1",
  "building": "COM2",
  "roomNumber": "02-01",
  "facilityType": "projector",
  "description": "Projector cannot turn on",
  "priority": "HIGH",
  "status": "IN_PROGRESS",
  "createdAt": "2026-08-10T16:30:00.123456",
  "updatedAt": "2026-08-10T17:00:00.654321"
}
```

Error Response 示例（HTTP `404`）：

```json
{
  "timestamp": "2026-08-10T16:30:00.123456",
  "status": 404,
  "code": "TICKET_NOT_FOUND",
  "error": "Maintenance ticket not found"
}
```

HTTP Status Code：

- `200 OK`：owned ticket 查询成功。
- `400 Bad Request`：`ticketId` 格式错误。
- `401 Unauthorized`：未登录或 login JWT 无效。
- `404 Not Found`：ticket 不存在，或 ticket 属于其他用户。

前端调用注意事项：

- 为保护隐私，访问其他用户 ticket 与真正不存在的 ticket 都返回相同 404。
- Admin 也不能通过这个详情 endpoint 查看他人 ticket；管理端状态更新 endpoint 是另一条独立路径。
- 手工 location ticket 的 `spaceId`、`spaceName` 为 null，UI 必须允许这两个字段为空。

### 7.4 Admin update maintenance status

| 项目 | 内容 |
|---|---|
| 功能 | 管理员更新任意现有 maintenance ticket 的工作流状态 |
| HTTP Method | `PATCH` |
| URL | `/api/facilities/maintenance/{ticketId}/status` |
| 是否需要登录 | 是，HS256 login JWT |
| 所需角色 | 仅 `ADMIN` 或 `SUPER_ADMIN` |

Path Parameters：

| 参数 | 类型 | Required | 说明 |
|---|---|---:|---|
| `ticketId` | integer (int64) | 是 | Maintenance ticket ID |

Query Parameters：无。

Request Body：

| 字段 | JSON 类型 | Required | 说明 |
|---|---|---:|---|
| `status` | string | 是 | 非空 `MaintenanceStatus`；推荐发送 canonical value |

```json
{
  "status": "IN_PROGRESS"
}
```

Response JSON 示例（HTTP `200`）：

```json
{
  "success": true,
  "ticketId": 88,
  "spaceId": 1,
  "spaceName": "COM2 Project Room 1",
  "building": "COM2",
  "roomNumber": "02-01",
  "facilityType": "projector",
  "description": "Projector cannot turn on",
  "priority": "HIGH",
  "status": "IN_PROGRESS",
  "createdAt": "2026-08-10T16:30:00.123456",
  "updatedAt": "2026-08-10T17:00:00.654321"
}
```

Error Response 示例（HTTP `409`）：

```json
{
  "timestamp": "2026-08-10T17:00:00.654321",
  "status": 409,
  "code": "INVALID_MAINTENANCE_TRANSITION",
  "error": "Maintenance status cannot change from SUBMITTED to RESOLVED"
}
```

角色不足示例（HTTP `403`）：

```json
{
  "timestamp": "2026-08-10T17:00:00.654321",
  "status": 403,
  "error": "Access Denied"
}
```

HTTP Status Code：

- `200 OK`：状态更新成功，或目标状态与当前状态相同。
- `400 Bad Request`：status 缺失、空白或不是有效 enum。
- `401 Unauthorized`：未登录或 login JWT 无效。
- `403 Forbidden`：已登录，但角色为 `STUDENT`。
- `404 Not Found`：ticket 不存在。
- `409 Conflict`：状态转换不允许。

前端调用注意事项：

- 权限由后端 `@PreAuthorize` 和 Service 双重强制执行；隐藏按钮不能替代后端授权。
- 当前没有“列出所有用户 tickets”的 Admin Facilities endpoint。管理 UI 必须通过合法来源获得 `ticketId`，不能把 `GET /maintenance` 当作全量管理列表。
- 只向用户展示当前状态允许的下一状态，以减少 409；仍必须处理并刷新服务器最新状态。
- 该管理员操作没有对应的普通 Facilities MCP tool。

## 8. Endpoint 总览

| # | Method | URL | Role | Success |
|---:|---|---|---|---|
| 1 | `GET` | `/api/facilities/spaces` | Any authenticated user | `200` |
| 2 | `GET` | `/api/facilities/spaces/{spaceId}` | Any authenticated user | `200` |
| 3 | `GET` | `/api/facilities/spaces/{spaceId}/availability` | Any authenticated user | `200` |
| 4 | `POST` | `/api/facilities/bookings` | Any authenticated user | `201` |
| 5 | `GET` | `/api/facilities/bookings` | Any authenticated user | `200` |
| 6 | `GET` | `/api/facilities/bookings/{bookingId}` | Owner | `200` |
| 7 | `PATCH` | `/api/facilities/bookings/{bookingId}/cancel` | Owner | `200` |
| 8 | `POST` | `/api/facilities/maintenance` | Any authenticated user | `201` |
| 9 | `GET` | `/api/facilities/maintenance` | Any authenticated user; own data only | `200` |
| 10 | `GET` | `/api/facilities/maintenance/{ticketId}` | Owner | `200` |
| 11 | `PATCH` | `/api/facilities/maintenance/{ticketId}/status` | `ADMIN`, `SUPER_ADMIN` | `200` |

## 9. Frontend Quick Start

以下示例假设登录响应中的 `token` 已保存在应用的认证状态中。示例直接传入 token，避免规定项目必须使用 `localStorage`；实际项目应遵循 `frontend_web` 当前认证存储方案。

### 9.1 公共 request helper

```js
const API_BASE_URL = "http://localhost:8080";

async function facilitiesFetch(path, token, options = {}) {
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  headers.set("Authorization", `Bearer ${token}`);

  if (options.body !== undefined) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers,
  });

  const contentType = response.headers.get("content-type") ?? "";
  const payload = contentType.includes("application/json")
    ? await response.json()
    : null;

  if (!response.ok) {
    const error = new Error(payload?.error ?? `Request failed: ${response.status}`);
    error.status = response.status;
    error.code = payload?.code;
    error.details = payload;
    throw error;
  }

  return payload;
}
```

### 9.2 示例 1：Search spaces

```js
async function searchSpaces(token) {
  const params = new URLSearchParams();
  params.set("building", "COM2");
  params.set("spaceType", "STUDY_ROOM");
  params.set("minimumCapacity", "4");
  params.append("equipment", "whiteboard");
  params.append("equipment", "display");
  params.set("startDateTime", "2026-08-11T14:00:00");
  params.set("endDateTime", "2026-08-11T16:00:00");

  return facilitiesFetch(
    `/api/facilities/spaces?${params.toString()}`,
    token,
    { method: "GET" },
  );
}
```

预期结果是 `SpaceResponse[]`；没有匹配时是 `[]`。

### 9.3 示例 2：Create booking

```js
async function createBooking(token, spaceId) {
  return facilitiesFetch("/api/facilities/bookings", token, {
    method: "POST",
    body: JSON.stringify({
      spaceId,
      startDateTime: "2026-08-11T14:00:00",
      endDateTime: "2026-08-11T16:00:00",
    }),
  });
}
```

成功返回 HTTP `201` 和 `BookingResponse`。必须处理 `BOOKING_CONFLICT`/HTTP `409`，并提示用户重新选择时间。

### 9.4 示例 3：Submit maintenance request

```js
async function submitMaintenanceRequest(token, spaceId) {
  return facilitiesFetch("/api/facilities/maintenance", token, {
    method: "POST",
    body: JSON.stringify({
      spaceId,
      facilityType: "projector",
      description: "Projector cannot turn on",
      priority: "HIGH",
    }),
  });
}
```

成功返回 HTTP `201` 和 `MaintenanceResponse`，初始状态为 `SUBMITTED`。

## 10. Frontend implementation checklist

- 所有 Facilities REST 请求使用 HS256 login JWT，不使用 RS256 Delegation Token。
- 统一处理 401（重新登录）、403（权限不足）、404（资源不存在/ownership 隔离）、409（业务冲突）。
- LocalDateTime 不添加 `Z` 或 offset。
- 创建 booking 前可先检查 availability，但仍处理最终创建时的 409。
- 不向任何 booking/maintenance 用户接口提交 `userId`。
- 对 `spaceId`、`spaceName` 等 nullable response 字段做空值处理。
- 不假设搜索结果或 `equipment` 集合有稳定顺序。
- Admin UI 不要假设当前已有全量 maintenance list endpoint。
