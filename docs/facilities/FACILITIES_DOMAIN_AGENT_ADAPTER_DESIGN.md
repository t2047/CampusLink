# Facilities Domain Agent Adapter 设计

核心结论：Adapter 是一个“自然语言领域代理”，不是第二套 Facilities 业务服务。它负责理解用户、保存有限对话状态、组织 10 个 MCP tools、接入现有 HITL；所有权限、ownership、冲突和状态转换仍由 Spring Facilities backend 最终裁决。

实际兼容链路：

```text
Chat Core AgentClient
  → invoke(
      message,
      conversation_context,
      confirmed,
      confirmation_id,
      trace_parent
    )
Facilities Domain Agent Adapter
  → Spring Facilities MCP /mcp
  → FacilitiesService
  → facility DB
```

## 1. Adapter responsibility boundary

Adapter 应负责：

- Facilities intent recognition。
- 自然语言参数提取。
- 相对日期时间解析。
- 必填字段检查和追问。
- 从有限 `shared_context` 解析“first one”“that booking”。
- 调用现有 10 个 MCP tools。
- 将 `{success,data,error}` 转成 Domain Agent output。
- 写操作执行前创建 pending confirmation。
- 确认后执行冻结的 exact tool call。
- 生成自然语言结果摘要。

Adapter 不负责：

- 用户认证或签发 token。
- 从输入接受任意 `userId`。
- booking ownership。
- maintenance ownership。
- booking conflict 判定。
- opening hours、4 小时、14 天等最终校验。
- booking/maintenance 状态转换。
- ADMIN 权限判断。
- SQL、Repository 或数据库事务。
- 修改 FacilitiesService。
- 暴露第 11 个 Spring Facilities tool。

身份必须从当前 RS256 Delegation Token/MCP context 获取。

## 2. `invoke` input schema

Chat Core `049ed31` 的真实字段名是：

- `message`
- `conversation_context`
- `confirmed`
- `confirmation_id`
- `trace_parent`

不是 `request` 或 `user_message`。`user_id` 和 `role` 不属于 tool arguments，而是从 Delegation Token 获取。

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "message": {
      "type": "string",
      "minLength": 1,
      "maxLength": 4000
    },
    "conversation_context": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "previous_agent": {"type": ["string", "null"]},
        "session_id": {"type": ["string", "null"], "maxLength": 128},
        "shared_data": {"type": "object", "additionalProperties": true}
      }
    },
    "confirmed": {"type": "boolean", "default": false},
    "confirmation_id": {"type": ["string", "null"]},
    "trace_parent": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "trace_id": {"type": ["string", "null"]},
        "parent_span_id": {"type": ["string", "null"]}
      }
    }
  },
  "required": ["message"]
}
```

普通调用示例：

```json
{
  "message": "Book the first one.",
  "conversation_context": {
    "session_id": "session-3d8b",
    "shared_data": {
      "facilities": {
        "version": 1,
        "last_intent": "search_spaces",
        "search_results": {
          "startDateTime": "2026-08-10T14:00:00",
          "endDateTime": "2026-08-10T16:00:00",
          "candidates": [
            {
              "rank": 1,
              "spaceId": 4,
              "name": "COM2 Project Room 03",
              "building": "COM2",
              "roomNumber": "03"
            }
          ]
        }
      }
    }
  },
  "confirmed": false,
  "trace_parent": {
    "trace_id": "trace-123",
    "parent_span_id": "span-456"
  }
}
```

确认恢复调用：

```json
{
  "message": "Book the first one.",
  "conversation_context": {
    "session_id": "session-3d8b",
    "shared_data": {
      "facilities": {
        "version": 1,
        "last_intent": "create_booking"
      }
    }
  },
  "confirmed": true,
  "confirmation_id": "facility-confirm-x7K2",
  "trace_parent": {
    "trace_id": "trace-123",
    "parent_span_id": "span-789"
  }
}
```

确认调用中即使仍包含原始 `message`，Adapter 也不得重新解析它。

## 3. `invoke` output schema

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "properties": {
    "response": {"type": "string"},
    "status": {
      "type": "string",
      "enum": ["completed", "needs_more_info", "needs_confirmation", "failed"]
    },
    "confirmation_required": {
      "type": ["object", "null"],
      "properties": {
        "confirmation_id": {"type": "string"},
        "action": {
          "type": "string",
          "enum": ["create_booking", "cancel_booking", "submit_maintenance_request"]
        },
        "summary": {"type": "string"},
        "preview": {"type": "object"},
        "expires_at": {"type": "string", "format": "date-time"}
      },
      "required": ["confirmation_id", "action", "summary", "expires_at"]
    },
    "shared_context": {"type": "object", "additionalProperties": true},
    "actions_taken": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "action": {"type": "string"},
          "params_summary": {"type": ["string", "null"]},
          "result_summary": {"type": ["string", "null"]},
          "error_code": {"type": ["string", "null"]},
          "status": {"type": "string", "enum": ["success", "failed", "skipped"]}
        },
        "required": ["action", "status"]
      }
    },
    "request_id": {"type": "string"},
    "error": {"type": ["string", "null"]}
  },
  "required": ["response", "status", "shared_context", "actions_taken", "request_id"]
}
```

### 正常成功

```json
{
  "response": "I found three available study rooms.",
  "status": "completed",
  "confirmation_required": null,
  "shared_context": {
    "facilities": {"version": 1, "last_intent": "search_spaces"}
  },
  "actions_taken": [
    {
      "action": "search_spaces",
      "params_summary": "STUDY_ROOM, capacity 4, projector, 14:00–16:00",
      "result_summary": "3 spaces found",
      "status": "success"
    }
  ],
  "request_id": "req-001",
  "error": null
}
```

### 需要补充信息

```json
{
  "response": "Which building and room is the broken projector in?",
  "status": "needs_more_info",
  "confirmation_required": null,
  "shared_context": {
    "facilities": {
      "version": 1,
      "last_intent": "submit_maintenance_request",
      "pending_maintenance_info": {
        "facilityType": "projector",
        "description": "The projector is broken.",
        "priority": "MEDIUM",
        "missingFields": ["location"]
      }
    }
  },
  "actions_taken": [],
  "request_id": "req-002",
  "error": null
}
```

### 需要 HITL

```json
{
  "response": "Please confirm booking COM2 Project Room 03 tomorrow from 2:00 pm to 4:00 pm.",
  "status": "needs_confirmation",
  "confirmation_required": {
    "confirmation_id": "facility-confirm-x7K2",
    "action": "create_booking",
    "summary": "Book COM2 Project Room 03, 14:00–16:00",
    "preview": {
      "spaceId": 4,
      "spaceName": "COM2 Project Room 03",
      "startDateTime": "2026-08-10T14:00:00",
      "endDateTime": "2026-08-10T16:00:00"
    },
    "expires_at": "2026-08-09T16:20:00Z"
  },
  "shared_context": {
    "facilities": {"version": 1, "last_intent": "create_booking"}
  },
  "actions_taken": [
    {
      "action": "create_booking",
      "params_summary": "spaceId=4, 14:00–16:00",
      "result_summary": "Awaiting confirmation",
      "status": "skipped"
    }
  ],
  "request_id": "req-003",
  "error": null
}
```

### 确认后成功

```json
{
  "response": "Your booking is confirmed. Booking ID: 123.",
  "status": "completed",
  "confirmation_required": null,
  "shared_context": {
    "facilities": {
      "version": 1,
      "last_intent": "create_booking",
      "last_booking_id": 123
    }
  },
  "actions_taken": [
    {
      "action": "create_booking",
      "params_summary": "spaceId=4, 14:00–16:00",
      "result_summary": "Booking 123 CONFIRMED",
      "status": "success"
    }
  ],
  "request_id": "req-004",
  "error": null
}
```

### 业务失败

业务失败不能设置顶层 `error`，否则当前 Chat Core 会把它当成 Agent/service failure。

```json
{
  "response": "That room was booked by someone else before your confirmation. Please choose another room or time.",
  "status": "completed",
  "confirmation_required": null,
  "shared_context": {
    "facilities": {"version": 1, "last_intent": "create_booking"}
  },
  "actions_taken": [
    {
      "action": "create_booking",
      "params_summary": "spaceId=4, 14:00–16:00",
      "result_summary": "The requested time overlaps an existing booking",
      "error_code": "BOOKING_CONFLICT",
      "status": "failed"
    }
  ],
  "request_id": "req-005",
  "error": null
}
```

### 真正的系统失败

只有 MCP transport、认证、timeout 或 Adapter 内部异常使用：

```json
{
  "response": "The facilities service is temporarily unavailable. Please try again later.",
  "status": "failed",
  "confirmation_required": null,
  "shared_context": {},
  "actions_taken": [],
  "request_id": "req-006",
  "error": "FACILITIES_MCP_UNAVAILABLE"
}
```

## 4. `shared_context` schema

应返回完整、有限的 Facilities context snapshot，而不是无限追加历史。

原因：Chat Core 会遍历历史 `agent_invocations` 并以 `shared.update()` 聚合。使用单一顶层 `facilities` 对象时，最新完整 snapshot 可以覆盖旧 snapshot。

```json
{
  "facilities": {
    "version": 1,
    "last_intent": "search_spaces",
    "search_results": {
      "startDateTime": "2026-08-10T14:00:00",
      "endDateTime": "2026-08-10T16:00:00",
      "expiresAt": "2026-08-09T17:00:00Z",
      "candidates": [
        {
          "rank": 1,
          "spaceId": 4,
          "name": "COM2 Project Room 03",
          "building": "COM2",
          "roomNumber": "03",
          "spaceType": "STUDY_ROOM",
          "capacity": 6,
          "equipment": ["projector", "whiteboard"]
        }
      ]
    },
    "selected_space": {
      "spaceId": 4,
      "name": "COM2 Project Room 03",
      "building": "COM2",
      "roomNumber": "03"
    },
    "booking_candidates": [
      {
        "rank": 1,
        "bookingId": 123,
        "spaceId": 4,
        "spaceName": "COM2 Project Room 03",
        "startDateTime": "2026-08-10T14:00:00",
        "endDateTime": "2026-08-10T16:00:00",
        "status": "CONFIRMED"
      }
    ],
    "last_booking_id": 123,
    "last_maintenance_ticket_id": 456,
    "pending_maintenance_info": {
      "spaceId": null,
      "building": null,
      "roomNumber": null,
      "facilityType": "projector",
      "description": "The projector is broken.",
      "priority": "MEDIUM",
      "missingFields": ["location"]
    },
    "updatedAt": "2026-08-09T16:10:00Z"
  }
}
```

### 应跨轮保存

- `last_intent`
- 最近一次 search 的时间范围
- 最多 5 个 search candidates
- 明确选中的 `selected_space`
- 最多 5–10 个最近展示的 booking candidates
- 明确选中/刚创建/刚操作的 `last_booking_id`
- `last_maintenance_ticket_id`
- 尚未完成的 maintenance draft
- context 更新时间和候选过期时间

### 不应跨轮保存

- Delegation Token 或其他 bearer token
- JWT claims、role、email
- confirmation exact arguments
- confirmation security record
- 完整聊天历史
- 所有历史搜索结果
- 所有 bookings/tickets
- 其他用户数据
- DB entity 原始结构
- private key、secret、SQL
- 超过上限或已过期的 candidates
- 已完成的 maintenance draft

`pending action` 必须保存在 Adapter 的受控 ConfirmationStore 中，而不是交给 LLM 可见的 `shared_context`。

## 5. Pending confirmation schema

```json
{
  "confirmation_id": "facility-confirm-x7K2",
  "user_id": "42",
  "session_id": "session-3d8b",
  "tool_name": "create_booking",
  "exact_arguments": {
    "spaceId": 4,
    "startDateTime": "2026-08-10T14:00:00",
    "endDateTime": "2026-08-10T16:00:00"
  },
  "preview": {
    "summary": "Book COM2 Project Room 03 tomorrow, 14:00–16:00",
    "spaceName": "COM2 Project Room 03"
  },
  "created_at": "2026-08-09T16:10:00Z",
  "expires_at": "2026-08-09T16:20:00Z",
  "consumed": false,
  "consumed_at": null,
  "state": "PENDING"
}
```

允许的 `tool_name` 只有：

- `create_booking`
- `cancel_booking`
- `submit_maintenance_request`

建议状态：

- `PENDING`
- `CONSUMED`
- `REJECTED`
- `EXPIRED`

安全规则：

- `confirmation_id` 使用高熵随机值。
- TTL 建议沿用现有 Lost & Found：600 秒。
- 创建时深拷贝 `exact_arguments`，之后不可修改。
- consume 必须原子执行。
- 必须匹配当前 authenticated user ID。
- 必须匹配当前 session ID。
- 必须未过期。
- 必须未 consumed。
- consumed 后重复调用必须拒绝。

异常处理：

| 情况 | 行为 |
|---|---|
| confirmation_id 不存在 | 不执行工具，提示重新发起 |
| 已过期 | 不执行，提示确认已过期 |
| wrong user | 不执行，记录安全事件 |
| wrong session | 不执行，记录安全事件 |
| 已 consumed | 不重复执行，提示已经处理 |
| 用户拒绝 | 不执行，状态设 REJECTED 或等待 TTL 清理 |

当前 Chat Core 在用户拒绝时不会重新调用 Adapter，而是在 `human_approval` 节点直接输出“操作已取消”。因此最小兼容实现中，Adapter 的 pending record 会留到 TTL 过期；如果未来增加 rejection callback，再立即标记 REJECTED。

确认后不能重新 parse 原话，原因：

- LLM 可能在两次解析中选择不同 space。
- 相对日期可能跨越午夜后变化。
- search result 排名可能变化。
- 用户看到的 preview 必须与实际执行完全一致。
- 重复解析可能改变时间、priority 或 booking ID。
- confirmation 的安全含义是确认一份冻结操作，而不是授权 LLM 再决定一次。

## 6. Intent → tool mapping

| 用户意图 | MCP tool | 类型 | HITL |
|---|---|---:|---:|
| 搜索空间 | `search_spaces` | READ | No |
| 查看空间详情 | `get_space_details` | READ | No |
| 检查可用性 | `check_availability` | READ | No |
| 创建预约 | `create_booking` | WRITE | Yes |
| 列出本人预约 | `list_user_bookings` | READ | No |
| 查看预约状态 | `get_booking_status` | READ | No |
| 取消预约 | `cancel_booking` | WRITE | Yes |
| 提交维修 | `submit_maintenance_request` | WRITE | Yes |
| 列出本人维修单 | `list_user_maintenance_requests` | READ | No |
| 查看维修状态 | `get_maintenance_status` | READ | No |

任何 maintenance admin status update 不属于 Adapter tool mapping，因为它没有暴露在当前 10 个 MCP tools 中。

## 7. Search → Booking flow

```text
Natural language
→ LLM intent/field extraction
→ normalize Singapore date/time
→ search_spaces
→ return bounded candidates
→ save search_results
```

推荐流程：

1. 提取 `spaceType`、`minimumCapacity`、`equipment`、`building` 和 start/end local datetime。
2. 缺少必要时间信息时追问。
3. `search_spaces` 已支持 availability window，因此通常不必逐个调用 availability。
4. 只保存前 5 个候选及必要字段，并附上稳定 `rank`。
5. 下一轮 “Book the first one” 读取 `rank=1`，检查 context 未过期并复用保存的 start/end。
6. 可再次调用 `check_availability`，降低展示过期结果的概率。
7. 冻结以下 exact arguments：

```json
{
  "spaceId": 4,
  "startDateTime": "2026-08-10T14:00:00",
  "endDateTime": "2026-08-10T16:00:00"
}
```

8. 保存 pending confirmation，返回 `needs_confirmation`，不执行 `create_booking`。
9. 用户确认后按 confirmation ID 加载 exact arguments，原子 consume，再调用 `create_booking`。
10. 后端再次执行锁、冲突和时间规则检查。
11. 成功后保存 `last_booking_id` 并清除 pending confirmation。
12. 若确认期间被别人预约，返回 `BOOKING_CONFLICT` 自然语言解释。

## 8. Booking tracking / cancellation flow

### “What bookings do I have?”

调用 `list_user_bookings`，保存最多 5–10 个刚展示的候选：

```json
[
  {
    "rank": 1,
    "bookingId": 123,
    "spaceName": "COM2 Project Room 03",
    "startDateTime": "2026-08-10T14:00:00",
    "endDateTime": "2026-08-10T16:00:00",
    "status": "CONFIRMED"
  }
]
```

### “Cancel booking 123.”

- ID 明确。
- 调用 `get_booking_status(123)` 获取 preview 并让后端验证 ownership。
- 若可取消，冻结 `{bookingId:123}`。
- 返回 HITL。
- 确认后调用 `cancel_booking(123)`。

### “Cancel the first one.”

- 使用最近一次 `booking_candidates` 中 `rank=1`。
- context 缺失或过期时重新调用 `list_user_bookings`。
- 如果列表为空，直接说明没有 booking。
- 冻结 booking ID 后 HITL。

### “Cancel that booking.”

只有以下情况可以解析：

- `last_booking_id` 来自刚创建或用户明确选择的 booking；或
- 最近一次只展示了一个 booking。

如果最近展示多个 booking 且没有明确选择，不能猜，应返回 `needs_more_info`，询问 booking ID 或列表序号。

取消前预检查：

- 若已 CANCELLED：返回已取消，不创建 HITL。
- 若 COMPLETED：说明不能取消。
- 若 start time 已过去：说明不能取消。
- 可取消时才生成 pending confirmation。

取消成功后保留 `last_booking_id`，将对应 candidate status 更新为 CANCELLED，不删除 DB record。

## 9. Maintenance flow

### 完整信息

用户：`The projector in COM2-03 is broken.`

提取：

```json
{
  "building": "COM2",
  "roomNumber": "03",
  "facilityType": "projector",
  "description": "The projector in COM2-03 is broken.",
  "priority": "MEDIUM"
}
```

推荐流程：

1. 以 building/query 调用 `search_spaces`。
2. 唯一匹配时使用 `spaceId`。
3. 多个匹配时追问具体房间。
4. 没有 registered space，但 building 和 roomNumber 都明确时，可按 Spring MCP 契约使用显式位置。
5. 构造 exact `submit_maintenance_request` arguments。
6. 返回 HITL。
7. 确认后提交。

### 信息不完整

用户：`The projector is broken.`

Adapter 保存：

```json
{
  "pending_maintenance_info": {
    "facilityType": "projector",
    "description": "The projector is broken.",
    "priority": "MEDIUM",
    "missingFields": ["location"]
  }
}
```

并询问具体 building 和 room。下一轮收到 `COM2-03` 后合并 draft 与 location，搜索并解析空间，构造 exact arguments，再进入 HITL。

### Priority 策略

Spring backend 当前在 priority 缺失时默认 `MEDIUM`，因此 Adapter 可以使用同样默认，不必强制追问：

- 明确 urgent/emergency/serious 可提取为 HIGH。
- 明确 minor/not urgent 可提取为 LOW。
- 未说明时使用 MEDIUM。
- confirmation preview 必须显示最终 priority。
- exact arguments 中建议显式保存 `MEDIUM`，确保 preview 与执行一致。

需要追问：

- 不知道什么设施损坏。
- 没有 description。
- 没有 spaceId，也缺少 building 或 roomNumber。
- location 对应多个候选。
- 用户给出的房间格式存在明显歧义。

## 10. Error mapping

基本原则：

```text
Facilities success=true
→ Domain status=completed
→ action status=success

Facilities success=false，属于业务结果
→ Domain status=completed 或 needs_more_info
→ action status=failed
→ 顶层 error=null

MCP transport/auth/timeout/Adapter exception
→ Domain status=failed
→ 顶层 error=技术错误分类
```

| Facilities error code | Adapter status | 用户响应方向 |
|---|---|---|
| `BOOKING_CONFLICT` | completed | 该时间刚被占用，请选择其他候选/时间 |
| `BOOKING_NOT_FOUND` | completed | 找不到该预约或它不属于当前用户 |
| `BOOKING_CANCELLATION_NOT_ALLOWED` | completed | 已开始/已完成，无法取消 |
| `SPACE_NOT_FOUND` | completed 或 needs_more_info | 未找到空间，请核对 building/room |
| `SPACE_UNAVAILABLE` | completed | 空间当前不可预订 |
| `INVALID_SPACE_TYPE` | needs_more_info | 请使用受支持空间类型 |
| `INVALID_CAPACITY` | needs_more_info | 人数必须至少为 1 |
| `INVALID_TIME` | needs_more_info 或 completed | 说明过去时间、时长、14 天或开放时间问题 |
| `TICKET_NOT_FOUND` | completed | 找不到该维修单或不属于当前用户 |
| `INVALID_LOCATION` | needs_more_info | 需要 spaceId 或 building+roomNumber |
| `INVALID_MAINTENANCE_REQUEST` | needs_more_info | 补充 facility type/description |
| `INVALID_PRIORITY` | needs_more_info | LOW/MEDIUM/HIGH |
| `INVALID_MAINTENANCE_STATUS` | completed | 当前 Adapter 不应发起 admin 状态更新 |
| `INVALID_MAINTENANCE_TRANSITION` | completed | 解释状态不可逆转 |
| `AUTHENTICATION_REQUIRED` | failed | 认证链异常，请重新登录/稍后重试 |
| 参数绑定/`VALIDATION_ERROR` | needs_more_info | 指出缺失或格式错误字段 |

业务失败不得设置顶层 `error`，避免 Chat Core 错误显示“Facilities service unavailable”。

## 11. Relative date/time strategy

当前 Chat Core 没有统一校园时区配置；`get_current_time` utility 目前硬编码 `Asia/Shanghai`。

项目实际地点是 Singapore，推荐未来明确配置：

```text
CAMPUS_TIMEZONE=Asia/Singapore
```

虽然 Singapore 和 Shanghai 目前都是 UTC+8 且无夏令时，但应使用语义正确的时区名称。

| 输入 | 规则 |
|---|---|
| today | Singapore 当前 calendar date |
| tomorrow | Singapore 当前日期 + 1 天 |
| next Monday | 严格取下一个 Monday；若今天是 Monday，则取 7 天后 |
| 2 pm | 14:00 |
| 2–4 pm | 14:00–16:00 |
| 14:00–16:00 | 直接使用 |
| “at 2” | am/pm 不明确时追问 |
| 只有开始时间 | booking/search 需要结束时间，应追问 |
| 过去时间 | 不构造写操作，提示选择未来时间 |

实现规则：

1. 每次 invoke 开始时只读取一次 Singapore `now`。
2. 用 timezone-aware datetime 做自然语言解析。
3. 发给 Facilities 时转成无 offset 的 ISO LocalDateTime，如 `2026-08-10T14:00:00`。
4. 不发送 `Z` 或 `+08:00`，因为 Spring 参数是 `LocalDateTime`。
5. Adapter 可提前检查明显错误，但最终规则由 FacilitiesService enforce。
6. 4 小时、14 天、opening hours 和 conflict 仍以后端为准。
7. confirmation preview 和 exact arguments 必须使用同一绝对 local datetime。

## 12. LLM responsibility boundary

### LLM 可以负责

- intent recognition。
- 中英文自然语言理解。
- 参数提取。
- 设备名称归一化。
- room/building 文本解析。
- 相对日期时间语义解析建议。
- 判断缺少哪些用户信息。
- 生成 clarification wording。
- 生成 confirmation preview 文案。
- 将工具结果总结成自然语言。
- 根据有限候选解释 “first one”。

### LLM 不得负责

- 判断最终 availability。
- 决定是否存在 booking conflict。
- 验证 booking owner。
- 验证 maintenance owner。
- 决定用户权限。
- 信任用户提供的 user ID。
- booking/maintenance 状态转换。
- 直接写数据库。
- 绕过 confirmation。
- 在 confirmation 后重新生成 arguments。
- 将 Spring business failure 改写成成功。
- 发明不存在的 space/booking/ticket。
- 生成 SQL 或访问 Repository。
- 决定认证是否有效。

所有 LLM 输出都必须经过 schema、enum、ID/type、context 和 backend tool validation。

# 13. 五个完整 Demo conversations

以下示例中的 user identity 均来自 Delegation Token，不出现在 invoke arguments 中。

## Demo A：Search → Book → Confirm

### A1. Search

用户：`Find me a study room for 4 people with a projector tomorrow 2–4 pm.`

Invoke input：

```json
{
  "message": "Find me a study room for 4 people with a projector tomorrow 2–4 pm.",
  "conversation_context": {"session_id": "demo-a", "shared_data": {}},
  "confirmed": false
}
```

Facilities MCP call：

```json
{
  "tool": "search_spaces",
  "arguments": {
    "spaceType": "STUDY_ROOM",
    "minimumCapacity": 4,
    "equipment": ["projector"],
    "startDateTime": "2026-08-10T14:00:00",
    "endDateTime": "2026-08-10T16:00:00"
  }
}
```

Shared context change：保存 last intent、时间范围及最多 5 个 candidates。HITL：无。

Invoke output：`completed`，展示带稳定序号的候选。

### A2. Book first candidate

用户：`Book the first one.`

Adapter 从 context 解析 rank 1，可选调用 `check_availability`，冻结：

```json
{
  "tool_name": "create_booking",
  "exact_arguments": {
    "spaceId": 4,
    "startDateTime": "2026-08-10T14:00:00",
    "endDateTime": "2026-08-10T16:00:00"
  }
}
```

HITL：返回 `needs_confirmation`，尚未调用 `create_booking`。

### A3. Yes

Chat Core resume 后调用 `confirmed=true` 和 confirmation ID。Adapter 验证 user/session/TTL/consumed，原子 consume，不调用 LLM重新解析，然后使用冻结参数调用 `create_booking`。

Shared context：保存 `last_booking_id`，清理 pending confirmation。Output：`completed`，booking status CONFIRMED。

## Demo B：List bookings → Cancel → Confirm

### B1. List

用户：`What bookings do I have?`

调用 `list_user_bookings`，保存带稳定 rank 的最多 5–10 个 `booking_candidates`。HITL：无。Output：`completed`。

### B2. Cancel first

用户：`Cancel the first one.`

Adapter 解析 `booking_candidates[rank=1]`，调用 `get_booking_status` 验证 ownership 和当前状态，然后冻结：

```json
{
  "tool_name": "cancel_booking",
  "exact_arguments": {"bookingId": 123}
}
```

HITL：返回取消 preview，不执行写操作。

### B3. Yes

Adapter consume confirmation，调用 `cancel_booking({"bookingId":123})`。Shared context 保存 `last_booking_id=123` 并把 candidate status 更新为 CANCELLED。Output：`completed`。

## Demo C：Maintenance clarification → Confirm → Submit

### C1. Missing room

用户：`The projector is broken.`

MCP call：无。Shared context 保存 facilityType、description、priority=MEDIUM、missing location。HITL：无。Output：`needs_more_info`，询问 building 和 room。

### C2. Room supplied

用户：`COM2-03.`

Adapter 合并 draft，调用：

```json
{
  "tool": "search_spaces",
  "arguments": {"query": "03", "building": "COM2"}
}
```

唯一匹配 spaceId 4 后冻结：

```json
{
  "tool_name": "submit_maintenance_request",
  "exact_arguments": {
    "spaceId": 4,
    "facilityType": "projector",
    "description": "The projector is broken.",
    "priority": "MEDIUM"
  }
}
```

HITL：`needs_confirmation`。

### C3. Yes

Adapter 使用冻结参数调用 `submit_maintenance_request`。Shared context 保存 `last_maintenance_ticket_id`，清除 draft 和 pending confirmation。Output：`completed`，ticket status SUBMITTED。

## Demo D：Show maintenance requests

用户：`Show my maintenance requests.`

Invoke input 包含 session ID 和当前 shared data。Adapter 调用 `list_user_maintenance_requests`。HITL：无。Shared context 更新 `last_intent`，仅在唯一或用户明确选择时设置 `last_maintenance_ticket_id`。Output：`completed`，自然语言展示本人 ticket 和状态。

## Demo E：确认后发生 Booking Conflict

1. 用户搜索并选择 spaceId 4、14:00–16:00，Adapter 返回确认。
2. 等待确认期间，另一个用户成功预订同一 space/time。
3. 用户确认后，Adapter 不重新搜索或修改参数，直接使用冻结 exact arguments 调用 `create_booking`。
4. Facilities 返回 `success=false`、`BOOKING_CONFLICT`。
5. Adapter 返回：

```json
{
  "response": "That room was booked before your confirmation. No booking was created. Please choose another room or time.",
  "status": "completed",
  "shared_context": {
    "facilities": {"version": 1, "last_intent": "create_booking"}
  },
  "actions_taken": [
    {
      "action": "create_booking",
      "error_code": "BOOKING_CONFLICT",
      "result_summary": "No booking was created",
      "status": "failed"
    }
  ],
  "request_id": "demo-e-3",
  "error": null
}
```

HITL 已消费，同一 confirmation ID 不得重试；用户需重新选择候选并产生新 confirmation。

## 14. 推荐未来文件结构

```text
agent/
├─ facilities_agent/
│  ├─ pyproject.toml
│  ├─ facilities_agent/
│  │  ├─ __init__.py
│  │  ├─ models.py
│  │  ├─ planner.py
│  │  ├─ context.py
│  │  ├─ confirmation.py
│  │  ├─ datetime_parser.py
│  │  ├─ result_mapper.py
│  │  ├─ tool_client.py
│  │  └─ service.py
│  └─ tests/
│     ├─ test_models.py
│     ├─ test_context.py
│     ├─ test_confirmation.py
│     ├─ test_datetime_parser.py
│     ├─ test_result_mapper.py
│     ├─ test_booking_flow.py
│     ├─ test_cancellation_flow.py
│     └─ test_maintenance_flow.py
├─ mcp_servers/
│  └─ facilities_server.py
└─ schemas/
   ├─ facility-agent.json
   └─ facility-domain-agent.json
```

职责：

- `facilities_server.py`：FastMCP bootstrap、`invoke`、公共 security middleware。
- `models.py`：invoke/context/output Pydantic models。
- `planner.py`：LLM intent/argument extraction。
- `context.py`：bounded shared context 更新和过期。
- `confirmation.py`：冻结工具调用、TTL、owner/session、原子 consume。
- `datetime_parser.py`：Asia/Singapore 时间解析。
- `result_mapper.py`：Facilities response → Domain output。
- `tool_client.py`：连接 Spring `/mcp`，不包含业务规则。
- `service.py`：编排 read/write/HITL 流程。

应复用：

- `mcp_servers.security.McpSecurityMiddleware`
- `identity_from_context`
- Chat Core Delegation Token
- 当前 `invoke` output contract
- Chat Core HITL/SSE/resume
- 现有 MCP client transport 逻辑

现有 `facility-agent.json` 1.2.0 应继续作为 Spring 10-tool server 的真实 contract。新的单一 `invoke` Adapter contract 应使用独立文件，不能用 Chat Core 旧 1.1.0 mock contract 覆盖 1.2.0。

## 15. 可以现在实现的部分

只能在新的独立 adapter/integration worktree 中实现，不能修改当前 Facilities PR branch：

- `InvokeRequest/InvokeResponse` models。
- bounded shared context model。
- pending confirmation store。
- exact arguments freeze/consume。
- error result mapper。
- Asia/Singapore relative datetime parser。
- intent-to-tool policy。
- tool client interface。
- 使用 fake MCP client 的 booking/cancellation/maintenance flow tests。
- confirmation wrong user/session/expired/consumed tests。
- schema 和 contract tests。
- Demo conversation unit tests。
- LLM structured-output schema 和 mock tests。

这些部分可以通过接口和 mock 与 Chat Core 解耦。

## 16. 必须等待 Chat Core merge 后实现

- 在真实 `services.yaml` 注册 Facilities adapter。
- 确认最终 Python package/dependency 版本。
- 复用最终 `McpSecurityMiddleware` 和 Delegation Token identity helper。
- 处理/转发真实 RS256 bearer token 到 Spring Facilities `/mcp`。
- 与最终 `AgentClient.invoke_agent()` 进行 contract integration。
- 验证 `shared_context` 在真实 LangGraph checkpoint 中跨轮传播。
- HITL `interrupt → SSE confirm_required → /chat/resume` 全链路。
- 用户拒绝时 pending confirmation 的最终清理策略。
- Frontend confirmation UI 联调。
- dual-auth `FacilityMcpDelegationAuthFilter` runtime。
- Chat Core → Adapter → Spring MCP 的真实 initialize/list/call。
- 五个 Demo 的端到端 UAT。
- 两个 feature 分支共享文件的最终冲突处理。

本设计不要求修改现有 Facilities 业务代码、数据库或 10 个 MCP tools。
