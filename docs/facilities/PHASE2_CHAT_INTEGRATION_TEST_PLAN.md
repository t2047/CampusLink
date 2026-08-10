# Facilities Phase 2 Integration Test Plan

## 0. 范围与验收基线

目标分支：未来 `feature/facilities-chat-integration`。

认证路径：

```text
REST / direct MCP
  → HS256 login JWT
  → subject=email
  → UserRepository.findByEmail()

Chat Core MCP
  → RS256 Delegation Token
  → iss=token-service
  → aud=facility-agent
  → sub=numeric user ID
  → UserRepository.findById()
```

两条路径最终必须产生相同结果：

```text
SecurityContext principal = 现有 User
authority = ROLE_<数据库中的 User.role>
```

固定验收要求：

- FacilitiesService、数据库 schema 和业务规则不变。
- `/mcp` 仍只暴露现有 10 个 tools。
- `update_maintenance_status` 不出现在 MCP tools/list。
- HS256 REST/MCP 行为不回归。
- RS256 只能认证 `/mcp`，不能访问普通 REST。
- 认证失败不得产生任何 DB 写入。
- MCP 业务错误使用 `success=false/error`；认证错误在进入 MCP 前返回 HTTP 401。

## 1. 测试数据与环境

### 测试身份

| 身份 | 角色 | 用途 |
|---|---|---|
| User A | STUDENT | 创建和管理自己的 booking/ticket |
| User B | STUDENT | 验证隐私隔离和越权访问 |
| Admin A | ADMIN | 更新 maintenance status |
| Super Admin | SUPER_ADMIN | 管理权限回归 |
| Unknown ID | 不存在 | RS256 numeric sub 负向测试 |

### 时间数据

所有预约时间动态生成，避免硬编码过期日期：

- 日期：当前时间后 1–7 天。
- 原预约：14:00–16:00。
- 冲突窗口：15:00–17:00。
- 取消后复查窗口：15:00–17:00。
- 必须在 space opening hours 内。
- 不超过 4 小时。
- 不超过 14 天预订范围。

### 测试密钥

自动化测试使用独立测试 RSA key pair：

- 不使用真实运行环境 private key。
- private key 仅存在测试进程。
- public key 通过测试 bean/JWKS fixture 提供。
- 固定或可注入 `Clock`，保证 `iat/exp` 边界测试稳定。
- 测试输出不得打印完整 token、JWT secret 或 private key。

### 数据隔离

- 自动化测试使用 H2/test profile 或专用测试 schema。
- 多请求 MCP 测试不能依赖单个测试事务自动 rollback，应显式准备和清理测试数据。
- Runtime UAT 使用专用 UAT 用户、唯一时间窗口和描述前缀。
- 所有 DB 断言使用“执行前后记录数差值 + 具体 ID”，避免受已有数据影响。

# 自动化测试计划

## 2. Unit Tests：DelegationTokenVerifier

| Test ID | 测试内容 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| UT-DTV-001 | 合法 token | 完整 RS256 claims | 返回类型化 verified claims | P0 |
| UT-DTV-002 | 错误算法 | `alg=HS256/none/ES256` | 拒绝 | P0 |
| UT-DTV-003 | 错误签名 | 非 Token Service private key 签名 | 拒绝 | P0 |
| UT-DTV-004 | 缺少/错误 issuer | 空或非 `token-service` | 拒绝 | P0 |
| UT-DTV-005 | 缺少/错误 audience | 无 aud 或不含 `facility-agent` | 拒绝 | P0 |
| UT-DTV-006 | 合法 numeric sub | 正整数 Long 字符串 | 正确解析 userId | P0 |
| UT-DTV-007 | 非法 sub | email、空、0、负数、溢出 | 拒绝 | P0 |
| UT-DTV-008 | token 已过期 | `exp < now` | 拒绝 | P0 |
| UT-DTV-009 | future iat | 超出 clock skew | 拒绝 | P0 |
| UT-DTV-010 | 时间关系错误 | `exp <= iat` | 拒绝 | P0 |
| UT-DTV-011 | TTL 过长 | `exp-iat > max lifetime` | 拒绝 | P0 |
| UT-DTV-012 | 合法 role | STUDENT/ADMIN/SUPER_ADMIN | 映射现有 Role | P0 |
| UT-DTV-013 | 非法 role | STAFF/ROOT/空/null | 拒绝 | P0 |
| UT-DTV-014 | intended action | `invoke` | 通过 | P0 |
| UT-DTV-015 | 错误 action | 缺少或非 `invoke` | 拒绝 | P0 |
| UT-DTV-016 | 缺少必要 claim | 分别移除 sub/exp/iat/role | 拒绝 | P0 |
| UT-DTV-017 | clock skew 边界 | ±允许范围边界 | 范围内通过，范围外拒绝 | P1 |
| UT-DTV-018 | unknown kid | token 使用未知 kid | 刷新一次后拒绝 | P1 |
| UT-DTV-019 | key rotation | JWKS 更新后新 kid | 刷新后通过 | P1 |
| UT-DTV-020 | malformed JWT | 非三段、非法 Base64/JSON | 安全拒绝，不泄漏异常 | P0 |

单元测试额外断言：

- 异常消息不得包含 token 原文。
- verifier 不访问 FacilitiesService。
- verifier 不创建 User 或 Role。
- verifier 不信任未验证 header/payload。
- `alg` 必须由 verifier 再次严格限制为 RS256。

## 3. Spring Security Filter Tests

目标类：`FacilityMcpDelegationAuthFilter`。

| Test ID | 场景 | 预期 | 优先级 |
|---|---|---|---|
| FT-AUTH-001 | 非 `/mcp` + RS256 token | filter 跳过，不建立认证 | P0 |
| FT-AUTH-002 | `/mcp` 无 Bearer | filter 放行给后续流程 | P0 |
| FT-AUTH-003 | `/mcp` + HS256 token | 不由 RS filter 处理，交给 JwtAuthFilter | P0 |
| FT-AUTH-004 | `/mcp` + 合法 RS256 | 建立 Authentication，继续 filter chain | P0 |
| FT-AUTH-005 | 合法 numeric sub | 调用 `UserRepository.findById()` | P0 |
| FT-AUTH-006 | 用户不存在 | HTTP 401，filter chain 不继续 | P0 |
| FT-AUTH-007 | token role 与 DB role 一致 | authorities 使用 DB role | P0 |
| FT-AUTH-008 | token role 与 DB role 不一致 | HTTP 401 | P0 |
| FT-AUTH-009 | 错误 RS256 token | HTTP 401，不回退 HS256 | P0 |
| FT-AUTH-010 | 已有 Authentication | 不覆盖现有 principal | P0 |
| FT-AUTH-011 | RS256 认证后进入 JwtAuthFilter | JwtAuthFilter 不覆盖认证 | P0 |
| FT-AUTH-012 | `SecurityContext` principal | 类型严格为现有 `User` | P0 |
| FT-AUTH-013 | authorities | `ROLE_STUDENT/ADMIN/SUPER_ADMIN` | P0 |
| FT-AUTH-014 | 认证失败 | 清理 SecurityContext | P0 |
| FT-AUTH-015 | `/mcp/` 尾斜杠 | 与 `/mcp` 行为一致 | P1 |
| FT-AUTH-016 | stale X-Timestamp | 401 | P1 |
| FT-AUTH-017 | 缺少 X-Timestamp | 按最终安全策略明确通过或拒绝 | P1 |

过滤器通过标准：

- RS256 验证失败后绝不执行 `JwtAuthFilter` fallback。
- HS256 token 不被 RS filter 消费。
- 不读取或修改 Facilities DB。
- 不对 `/api/facilities/**` 建立 Delegation Authentication。

## 4. MCP Integration Tests

每个完整 MCP session 应覆盖：

```text
initialize
→ notifications/initialized
→ tools/list
→ tools/call
```

除 token 过期专门测试外，同一 RS256 token 应能完成上述多个请求，证明没有错误实施 `jti` 一次性消费。

| Test ID | 场景 | 预期 | 优先级 |
|---|---|---|---|
| MCP-RS-001 | RS256 initialize | HTTP 200，返回 MCP session ID | P0 |
| MCP-RS-002 | initialized notification | 2xx | P0 |
| MCP-RS-003 | tools/list | 正好 10 个 `inputSchema` | P0 |
| MCP-RS-004 | 工具契约保护 | 包含现有 10 tools | P0 |
| MCP-RS-005 | admin tool 隔离 | 不包含 `update_maintenance_status` | P0 |
| MCP-RS-006 | `search_spaces` | `success=true`，返回数组 | P0 |
| MCP-RS-007 | `list_user_bookings` | 只返回 numeric sub 对应用户数据 | P0 |
| MCP-RS-008 | `get_booking_status` | 返回 owner booking | P0 |
| MCP-RS-009 | `list_user_maintenance_requests` | 只返回 owner tickets | P0 |
| MCP-RS-010 | `get_maintenance_status` | 返回 owner ticket | P0 |
| MCP-RS-011 | 写工具认证 principal | booking/ticket 的 user_id 等于 numeric sub | P0 |
| MCP-RS-012 | MCP 业务错误 | HTTP/JSON-RPC 正常，工具内容 `success=false` | P0 |
| MCP-RS-013 | wrong aud | HTTP 401，不能 initialize | P0 |
| MCP-RS-014 | expired token | HTTP 401 | P0 |
| MCP-RS-015 | unknown user sub | HTTP 401 | P0 |
| MCP-RS-016 | 同 token 多请求 | initialize/list/call 全部成功 | P0 |
| MCP-RS-017 | token 在 session 中过期 | 后续新请求 401，不复用旧认证 | P1 |
| MCP-RS-018 | 并发 MCP users | User A/B SecurityContext 不串号 | P1 |
| MCP-RS-019 | malformed arguments | 结构化 MCP 错误，不产生 DB 写入 | P1 |
| MCP-RS-020 | response convention | 所有工具保持 `{success,data,error}` | P0 |

## 5. REST Regression Tests

REST 必须继续只接受登录 JWT。

| Test ID | Endpoint | 身份 | 预期 | 优先级 |
|---|---|---|---|---|
| REST-REG-001 | `GET /api/facilities/spaces` | HS256 User A | 200 | P0 |
| REST-REG-002 | `GET /spaces/{id}` | HS256 User A | 200 | P0 |
| REST-REG-003 | `GET /spaces/{id}/availability` | HS256 User A | 200 | P0 |
| REST-REG-004 | `POST /bookings` | HS256 User A | 201，owner=A | P0 |
| REST-REG-005 | `GET /bookings` | HS256 User A | 只返回 A | P0 |
| REST-REG-006 | `PATCH /bookings/{id}/cancel` | HS256 owner | 200，CANCELLED | P0 |
| REST-REG-007 | `POST /maintenance` | HS256 User A | 201，owner=A | P0 |
| REST-REG-008 | `GET /maintenance` | HS256 User A | 只返回 A | P0 |
| REST-REG-009 | maintenance status PATCH | HS256 Student | 403 | P0 |
| REST-REG-010 | maintenance status PATCH | HS256 Admin | 200 | P0 |
| REST-REG-011 | 普通 REST | RS256 delegation | 不得认证成功 | P0 |
| REST-REG-012 | 缺少/错误 login JWT | 任意 REST | 401/现有约定错误 | P0 |

## 6. Role、Ownership 与 Privacy Tests

| Test ID | 场景 | 预期 | DB 变化 | 优先级 |
|---|---|---|---|---|
| AUTHZ-001 | Student 创建 booking | 成功，user_id=Student | 新增 1 booking | P0 |
| AUTHZ-002 | User B 获取 User A booking | BOOKING_NOT_FOUND/404 | 无 | P0 |
| AUTHZ-003 | User B 取消 User A booking | BOOKING_NOT_FOUND/404 | 原 booking 不变 | P0 |
| AUTHZ-004 | User B 列出 bookings | 不包含 A 的记录 | 无 | P0 |
| AUTHZ-005 | User B 获取 A ticket | TICKET_NOT_FOUND/404 | 无 | P0 |
| AUTHZ-006 | User B 列出 tickets | 不包含 A 的记录 | 无 | P0 |
| AUTHZ-007 | Student 更新 maintenance status | 403 | ticket 不变 | P0 |
| AUTHZ-008 | Admin 更新 status | 成功 | status/updated_at 更新 | P0 |
| AUTHZ-009 | Super Admin 更新 status | 成功 | status/updated_at 更新 | P0 |
| AUTHZ-010 | token role 与 DB role 不同 | 401 | 无 | P0 |
| AUTHZ-011 | RS256 ADMIN 调用 `/mcp` | 只能看到同样 10 tools | 无 admin MCP tool | P0 |
| AUTHZ-012 | 非 owner 猜测不存在/存在 ID | 返回不可区分的 not found | 无 | P1 |

隐私通过标准：

- 响应中不泄漏其他用户 booking/ticket 内容。
- 不因记录真实存在而返回不同的详细错误。
- 非 owner 操作后 `status`、`updated_at` 均不变。

## 7. Wrong Token 测试矩阵

以下测试至少在 verifier unit 层和 `/mcp` integration 层各覆盖一次：

| Token 问题 | 预期 HTTP | SecurityContext | DB |
|---|---:|---|---|
| wrong signature | 401 | 空 | 无变化 |
| `iss` 缺失 | 401 | 空 | 无变化 |
| wrong `iss` | 401 | 空 | 无变化 |
| `aud` 缺失 | 401 | 空 | 无变化 |
| wrong `aud` | 401 | 空 | 无变化 |
| `sub` 缺失 | 401 | 空 | 无变化 |
| non-numeric sub | 401 | 空 | 无变化 |
| unknown numeric user | 401 | 空 | 无变化 |
| `exp` 缺失 | 401 | 空 | 无变化 |
| expired | 401 | 空 | 无变化 |
| future `iat` | 401 | 空 | 无变化 |
| excessive TTL | 401 | 空 | 无变化 |
| role 缺失/非法 | 401 | 空 | 无变化 |
| role 与 DB 不符 | 401 | 空 | 无变化 |
| wrong intended_action | 401 | 空 | 无变化 |
| alg 非 RS256 | 401 或交给合法 HS 流程 | 不得产生错误认证 | 无变化 |
| malformed token | 401 | 空 | 无变化 |
| unknown kid | 刷新一次后 401 | 空 | 无变化 |

日志断言：

- 允许记录错误类别、issuer/audience mismatch、request path。
- 不记录完整 bearer token。
- 不记录 private key、login JWT secret 或 token exchange secret。

## 8. HS256 Backward Compatibility Tests

| Test ID | 场景 | 预期 | 优先级 |
|---|---|---|---|
| HS-BC-001 | 登录生成的 token subject=email | 行为不变 | P0 |
| HS-BC-002 | REST 通过 email 加载 User | 成功 | P0 |
| HS-BC-003 | direct MCP initialize | 成功 | P0 |
| HS-BC-004 | direct MCP tools/list | 正好 10 tools | P0 |
| HS-BC-005 | direct MCP tools/call | 成功 | P0 |
| HS-BC-006 | HS Student ownership | 只访问本人数据 | P0 |
| HS-BC-007 | HS Admin REST 权限 | status update 成功 | P0 |
| HS-BC-008 | expired HS token | 仍拒绝 | P0 |
| HS-BC-009 | wrong HS signature | 仍拒绝 | P0 |
| HS-BC-010 | MockMvc `.with(authentication())` | 不被新 filter 覆盖 | P0 |
| HS-BC-011 | REST 与 MCP 使用同一 HS token | 两条原路径均正常 | P1 |

回归门槛：

- 原 Facilities test suite 零失败。
- 原 REST status code 除明确统一 401 的负向测试外不变化。
- 工具名称、参数、返回结构不变化。
- 原 seed、schema isolation 和 15 spaces 不变化。

# Real Runtime Verification

## 9. Runtime 前置条件

- phpStudy MySQL 已启动于 `localhost:3306`。
- Backend `.env` 已配置，但测试过程不打印敏感值。
- `campuslink_db` 与独立 `facility` schema 可用。
- Chat Core merge 后 Token Service/JWKS endpoint 可用。
- User A、User B、Admin A 已准备。
- 使用专用未来时间窗口，避免与已有 booking 冲突。
- 不启动 Docker。
- token 只保存在进程内变量，不复制到测试报告。

## 10. Runtime 执行步骤

### A. Backend 与数据库

1. 启动 Backend。
2. 确认 health endpoint 正常。
3. 确认 `facility` schema 存在。
4. 确认四张表：
   - `facility.spaces`
   - `facility.space_equipment`
   - `facility.bookings`
   - `facility.maintenance_tickets`
5. 确认 15 条 spaces seed。
6. 记录 bookings/tickets 初始数量。

### B. HS256 回归

1. User A 正常登录，在内存中保存 HS256 token。
2. 使用 HS256 调用 REST search。
3. 使用同一 HS256 token 执行 MCP initialize。
4. 发送 initialized notification。
5. 执行 tools/list，确认 10 tools。
6. 调用 `search_spaces` 和 `list_user_bookings`。
7. 确认 principal 映射仍使用 email 对应的 User。

### C. RS256 验证

1. 通过真实 Chat Core/Token Service 获取 Delegation Token。
2. 只记录 claims 元数据，不记录 token 原文。
3. 确认：
   - alg=RS256
   - iss=token-service
   - aud 包含 facility-agent
   - sub 是 User A 数字 ID
   - intended_action=invoke
   - TTL 约 30 秒
4. 使用该 token 执行完整 MCP session。
5. 确认 tools/list 仍为 10。
6. 调用 `list_user_bookings`，验证 User A 身份。
7. 调用一个写工具，验证 DB user_id 为 User A。
8. 确认同一 token 可完成 initialize/list/call。
9. 等待 token 过期后发起新请求，确认 401。

### D. Negative security runtime

使用测试签发器生成非生产负向 token，分别验证：

- wrong issuer
- wrong audience
- wrong signature
- expired
- unknown numeric user
- role mismatch
- wrong intended_action

每次确认：

- HTTP 401
- 没有 MCP tool 执行
- bookings/tickets 数量不变
- 日志没有 token 原文

### E. REST isolation

1. 尝试用 RS256 Delegation Token 调用 `/api/facilities/bookings`。
2. 确认不能认证成功。
3. 使用原 HS256 token 重试。
4. 确认 REST 正常。

### F. 最终检查

- DB 变更与 UAT 记录一致。
- 没有额外 booking/ticket。
- 没有其他用户数据泄漏。
- tools/list 仍为 10。
- Backend/Chat Core 日志无 secret。
- `git status` 无 runtime response、token 或临时文件。

# Facilities 最终 UAT

## UAT-FAC-001：Search Space

| 字段 | 内容 |
|---|---|
| Test ID | UAT-FAC-001 |
| Preconditions | User A 已认证；15 条 seed 存在；有容量≥4且带 projector 的 space |
| User input/action | “Find me a study room for 4 people with a projector tomorrow from 2 to 4 pm.” |
| Expected MCP/REST action | MCP `search_spaces`；参数包括 `spaceType=STUDY_ROOM`、`minimumCapacity=4`、`equipment=["projector"]`、ISO 时间窗口 |
| Expected result | `success=true`；只返回满足类型、容量、设备和时间条件的 spaces |
| Expected DB change | 无 |
| Pass criteria | 返回至少一个合法结果；每项 capacity≥4、equipment 包含 projector；无 booking/ticket 新增 |

## UAT-FAC-002：Availability

| 字段 | 内容 |
|---|---|
| Test ID | UAT-FAC-002 |
| Preconditions | 已从搜索获得 spaceId；测试窗口合法且无冲突 |
| User input/action | 检查该 space 在 14:00–16:00 是否可用 |
| Expected MCP/REST action | MCP `check_availability` 或 REST `GET /spaces/{id}/availability` |
| Expected result | `available=true`，space 和时间窗口正确 |
| Expected DB change | 无 |
| Pass criteria | availability 与 space status、opening hours、booking 数据一致；请求不产生写入 |

## UAT-FAC-003：Book

| 字段 | 内容 |
|---|---|
| Test ID | UAT-FAC-003 |
| Preconditions | User A；space 在 14:00–16:00 可用；时间在未来 14 天内 |
| User input/action | 确认预订该 space 14:00–16:00 |
| Expected MCP/REST action | MCP `create_booking` 或 REST `POST /bookings` |
| Expected result | `success=true`；返回 bookingId；status=`CONFIRMED`；owner 为 User A |
| Expected DB change | `facility.bookings` 新增 1 行；user_id=A；space_id、时间正确；created_at/updated_at 非空 |
| Pass criteria | 只新增 1 条 booking；返回值与 DB 一致；没有修改 space 或其他用户数据 |

## UAT-FAC-004：Booking Conflict

| 字段 | 内容 |
|---|---|
| Test ID | UAT-FAC-004 |
| Preconditions | UAT-FAC-003 已创建 14:00–16:00 CONFIRMED booking |
| User input/action | 尝试预订同一 space 15:00–17:00 |
| Expected MCP/REST action | `check_availability` 后尝试 `create_booking` |
| Expected result | availability=false 或创建返回 `BOOKING_CONFLICT` |
| Expected DB change | bookings 数量不增加；原 booking 不变 |
| Pass criteria | 重叠预约绝不创建；错误结构稳定；原 booking 仍为 CONFIRMED |

## UAT-FAC-005：Track Booking

| 字段 | 内容 |
|---|---|
| Test ID | UAT-FAC-005 |
| Preconditions | User A 已有 UAT-FAC-003 booking |
| User input/action | “What bookings do I have?”，随后查询具体 bookingId |
| Expected MCP/REST action | `list_user_bookings`，必要时 `get_booking_status` |
| Expected result | 列表和详情包含该 booking；status=CONFIRMED；不包含 User B 数据 |
| Expected DB change | 无 |
| Pass criteria | 返回值与 DB 一致；调用前后 bookings 内容和数量不变 |

## UAT-FAC-006：Cancel Booking

| 字段 | 内容 |
|---|---|
| Test ID | UAT-FAC-006 |
| Preconditions | User A 拥有未来 CONFIRMED booking；记录原 updated_at |
| User input/action | 取消该 booking |
| Expected MCP/REST action | MCP `cancel_booking` 或 REST `PATCH /bookings/{id}/cancel` |
| Expected result | `success=true`；status=`CANCELLED` |
| Expected DB change | 不删除记录；status 更新为 CANCELLED；updated_at 增大 |
| Pass criteria | audit record 保留；再次取消为现有幂等结果；重新检查 15:00–17:00 时 available=true |

## UAT-FAC-007：Submit Maintenance

| 字段 | 内容 |
|---|---|
| Test ID | UAT-FAC-007 |
| Preconditions | User A 已认证；使用有效 spaceId，或同时提供 building 和 roomNumber |
| User input/action | 报告指定房间 projector 无法启动，priority=HIGH |
| Expected MCP/REST action | MCP `submit_maintenance_request` 或 REST `POST /maintenance` |
| Expected result | `success=true`；返回 ticketId；status=`SUBMITTED`；priority=HIGH |
| Expected DB change | `facility.maintenance_tickets` 新增 1 行；user_id=A；位置、描述、priority 正确 |
| Pass criteria | 只新增 1 条 ticket；返回值与 DB 一致；没有更新其他 ticket |

## UAT-FAC-008：Track Maintenance

| 字段 | 内容 |
|---|---|
| Test ID | UAT-FAC-008 |
| Preconditions | User A 已创建 UAT-FAC-007 ticket |
| User input/action | 查询自己的 maintenance requests 和具体 ticket |
| Expected MCP/REST action | `list_user_maintenance_requests`、`get_maintenance_status` |
| Expected result | 返回该 ticket；初始 status=SUBMITTED |
| Expected DB change | 无 |
| Pass criteria | 只返回 User A tickets；查询不改变 status/updated_at |

## UAT-FAC-009：Student/Admin Authorization

| 字段 | 内容 |
|---|---|
| Test ID | UAT-FAC-009 |
| Preconditions | 存在 SUBMITTED ticket；Student 和 Admin 均有有效 HS256 login JWT |
| User input/action | Student 和 Admin 分别调用 `PATCH /maintenance/{ticketId}/status`，目标 IN_PROGRESS |
| Expected MCP/REST action | REST admin endpoint；MCP tools/list 中不应有 admin update tool |
| Expected result | Student=403；Admin=200，status=IN_PROGRESS |
| Expected DB change | Student 请求后无变化；Admin 请求后 status/updated_at 更新 |
| Pass criteria | 后端真实 enforce 权限；前端隐藏与否不影响结果；MCP 仍只有 10 tools |

## UAT-FAC-010：Privacy Isolation

| 字段 | 内容 |
|---|---|
| Test ID | UAT-FAC-010 |
| Preconditions | User A 拥有 booking 和 ticket；User B 已认证 |
| User input/action | User B 列出、查看并尝试取消 A 的 booking；查看 A 的 ticket |
| Expected MCP/REST action | list/get/cancel booking 和 list/get maintenance |
| Expected result | B 的列表不含 A 数据；按 ID 获取/取消返回 BOOKING_NOT_FOUND；ticket 返回 TICKET_NOT_FOUND |
| Expected DB change | 无；A 的 booking/ticket 状态和 updated_at 不变 |
| Pass criteria | 不泄漏 A 的内容或记录是否存在；所有越权请求无 DB 副作用 |

# 优先级划分

## P0：必须通过

### Security

- UT-DTV-001 至 016、020。
- FT-AUTH-001 至 014。
- 所有 wrong issuer/audience/signature/expired/sub/role/action 测试。
- RS256 失败不回退 HS256。
- RS256 不能访问普通 REST。
- SecurityContext principal 必须为现有 User。
- User role 必须来自现有 Role/数据库。
- 无效 token 无 DB 变化。

### Protocol

- MCP initialize/notification/tools/list/tools/call。
- RS256 和 HS256 两条 MCP 路径。
- tools/list 正好 10 tools。
- 不暴露 admin maintenance tool。
- 同一个短期 token 能完成一个正常 MCP session。

### Business

- UAT-FAC-001 至 UAT-FAC-010 全部通过。
- Booking conflict。
- Cancel 后 availability 恢复。
- Maintenance Student 403、Admin 成功。
- Booking/ticket privacy isolation。

### Regression

- 原 Facilities tests 全部通过。
- REST HS256 全回归。
- direct MCP HS256 全回归。
- schema isolation 和 15 条 seed 不变。

## P1：推荐通过

- JWKS unknown kid 刷新。
- RSA key rotation。
- clock skew 边界。
- token 在 MCP session 中途过期。
- User A/B 并发请求 SecurityContext 隔离。
- 同一 space 并发 booking，仅一个成功。
- 预约 4 小时和 14 天边界。
- opening hours 边界。
- invalid enum/date/priority/maintenance transition。
- 重复取消幂等性。
- `SUBMITTED → CANCELLED`、`IN_PROGRESS → CANCELLED`。
- `RESOLVED → SUBMITTED` 被拒绝。
- `/mcp` 与 `/mcp/` 路径兼容性。
- JWKS/verification 不进行每请求网络获取。
- 日志与异常信息脱敏检查。

## Demo 场景

### Demo 1：完整 Booking 闭环

```text
自然语言搜索 space
→ availability
→ booking
→ 冲突验证
→ track booking
→ cancel
→ availability 恢复
```

### Demo 2：Maintenance 闭环

```text
Student submit maintenance
→ Student track SUBMITTED
→ Student 尝试更新：403
→ Admin 更新 IN_PROGRESS
→ Student 再次 track：IN_PROGRESS
→ Admin 更新 RESOLVED
```

### Demo 3：Dual-auth 与隐私

```text
HS256 login JWT 调用 REST/MCP 成功
→ RS256 Delegation Token 调用 MCP 成功
→ 同一 User principal/相同个人数据
→ wrong audience 返回 401
→ User B 无法查看或修改 User A 数据
```

最终发布门槛：所有 P0 自动化测试通过、10 个 UAT 全部通过、原 HS256 测试零回归、tools/list 保持 10、无 secret 泄漏、无越权和非预期 DB 写入。
