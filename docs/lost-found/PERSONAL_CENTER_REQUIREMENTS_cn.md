# Lost & Found 个人中心需求说明

## 1. 背景

CampusLink 现有 Lost & Found 模块已支持浏览失物招领、发布 LOST/FOUND 报告、提交认领申请、查看 `My Claims` 与收到的认领申请。当前普通用户入口分散在顶部导航和各业务页面中，缺少一个统一的个人中心，用户无法集中查看个人资料、自己提交的认领、自己发布的失物/拾物记录和帮助信息。

本需求用于在 Lost & Found 板块新增用户个人中心页面，作为后续设计、前端、后端和测试开发依据。

## 2. 目标

- 为登录用户提供统一的 Lost & Found 个人中心入口。
- 展示用户个人昵称、头像和基础账号信息。
- 在“我的服务”板块集中展示：
  - My Claims
  - 我的失物
  - 我的拾物
- 在“其他”板块提供 FAQ 入口。
- 复用现有 Lost & Found 业务能力，减少重复列表逻辑。

## 3. 非目标

- 本期不做社交主页、用户公开资料页或其他用户资料查看。
- 本期不做复杂账号设置，例如修改密码、绑定手机、隐私偏好。
- 本期不做站内消息中心，除非后续单独提出通知需求。
- 本期 FAQ 先作为静态帮助页或本地配置内容，不接入 CMS。

## 4. 用户角色

### 普通登录用户

可以进入 Lost & Found 个人中心，查看和管理与自己有关的 Lost & Found 记录。

### 管理员用户

管理员仍保留现有 `/admin/lost-found` 管理后台。本个人中心面向用户侧，即使管理员账号访问，也展示其作为普通用户的个人 Lost & Found 数据。

## 5. 页面入口与路由建议

### 入口

- 在用户侧顶部导航新增个人中心入口。
- 当前顶部导航显示用户 email，可改为头像/昵称按钮，点击进入个人中心。
- 移动端或窄屏时，入口应保持可访问，可以放入折叠菜单或保留头像按钮。

### 路由

建议新增：

| 页面 | 路由 | 说明 |
| --- | --- | --- |
| Lost & Found 个人中心 | `/lost-found/profile` | 个人中心首页 |
| My Claims | `/claims/mine` | 复用现有页面 |
| 我的失物 | `/lost-found/profile/lost` | 当前用户发布的 LOST 报告 |
| 我的拾物 | `/lost-found/profile/found` | 当前用户发布的 FOUND 报告 |
| FAQ | `/lost-found/faq` | Lost & Found 常见问题 |

说明：不通过 `?owner=me` 查询参数复用 Browse 的 `ReportsPage`（原因见 §10.1），改为独立的 `MyReportsPage`；`owner=me` 仅作为 `GET /api/lost-found/reports` 的后端查询参数使用。

## 6. 页面信息架构

### 6.1 个人资料区

展示内容：

- 头像
- 个人昵称
- 账号 email
- 用户角色，可选展示

交互：

- 若用户没有上传头像，展示默认头像，默认头像可使用昵称首字母或 email 首字母生成。
- 若用户没有设置昵称，默认展示 email 前缀。
- 本期建议支持头像和昵称展示；是否允许编辑见“待确认问题”。

建议字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `nickname` | string | 否 | 用户昵称，1-30 字符 |
| `avatarUrl` | string | 否 | 头像图片 URL |
| `email` | string | 是 | 账号邮箱 |
| `role` | string | 是 | 用户角色 |

### 6.2 我的服务

以列表或卡片形式展示三个入口，每个入口应包含名称、简短状态信息和跳转动作。

| 入口 | 展示名称 | 跳转目标 | 状态信息 |
| --- | --- | --- | --- |
| My Claims | My Claims | `/claims/mine` | 我提交的认领申请数量，可按状态显示待处理数量 |
| 我的失物 | My Lost Items | 我的 LOST 报告列表 | 当前用户发布的 LOST 报告数量 |
| 我的拾物 | My Found Items | 我的 FOUND 报告列表 | 当前用户发布的 FOUND 报告数量 |

MVP 状态信息：

- My Claims：展示我提交的认领申请总数；可选展示 `SUBMITTED` 待处理数量。
- 我的失物：展示当前用户发布的 LOST 报告总数。
- 我的拾物：展示当前用户发布的 FOUND 报告总数。

MVP 统计口径：

- 我的失物/拾物：复用 `owner=me` 搜索，取 `size=1` 的 `totalElements` 作为总数，不逐状态发 N 次请求。
- My Claims：`GET /api/lost-found/claims/mine` 返回全量列表，前端聚合总数；如展示待处理数量，仅聚合 `SUBMITTED` 数量。

暂不在个人中心首页展示 LOST/FOUND 报告的 `OPEN`、`CLAIMED`、`CLOSED` 分状态数量，避免前端为统计发起多次列表请求。若后续需要分状态统计，应新增聚合接口，例如 `GET /api/lost-found/profile/summary`。

若短期不做任何统计，首页可以先展示入口，不展示数量；列表页再加载详细数据。

### 6.3 其他

展示 FAQ 入口。

| 入口 | 展示名称 | 跳转目标 | 说明 |
| --- | --- | --- | --- |
| FAQ | FAQ | `/lost-found/faq` | 查看 Lost & Found 使用说明、认领规则和常见问题 |

FAQ 建议内容：

- 如何发布失物报告？
- 如何发布拾物报告？
- 如何提交认领申请？
- 为什么我不能认领自己发布的物品？
- 认领申请提交后由谁审核？
- 报告状态 `OPEN`、`CLAIMED`、`CLOSED` 分别代表什么？
- 图片上传有什么限制？
- 如何关闭或删除我发布的报告？
- 如果发现虚假信息或敏感信息怎么办？
- 如何联系失主/拾主？
- 我的报告被管理员下架意味着什么？

## 7. 关键用户流程

### 7.1 进入个人中心

1. 用户登录 CampusLink。
2. 用户点击顶部导航的头像、昵称或个人中心入口。
3. 系统打开 `/lost-found/profile`。
4. 页面展示头像、昵称、email 和“我的服务”“其他”两个板块。

### 7.2 查看 My Claims

1. 用户在个人中心点击 `My Claims`。
2. 系统跳转到 `/claims/mine`。
3. 页面展示当前用户提交的认领申请。
4. 用户可以查看申请状态和对应报告摘要。

### 7.3 查看我的失物

1. 用户在个人中心点击“我的失物”。
2. 系统展示当前用户发布的 `LOST` 类型报告。
3. 用户可以进入详情页。
4. 对于自己发布的报告，保留现有编辑、关闭、删除能力。

### 7.4 查看我的拾物

1. 用户在个人中心点击“我的拾物”。
2. 系统展示当前用户发布的 `FOUND` 类型报告。
3. 用户可以进入详情页。
4. 对于自己发布的报告，保留现有编辑、关闭、删除能力。
5. 用户可通过现有 `Claims received` 能力处理别人对自己拾物报告提交的认领申请；是否在本期个人中心显式增加“收到的认领”入口见“待确认问题”。

### 7.5 查看 FAQ

1. 用户在个人中心点击 FAQ。
2. 系统打开 `/lost-found/faq`。
3. 页面按问题分组展示常见问题和答案。

## 8. 功能需求

### FR-1 个人中心页面

- 系统必须为已登录用户提供 `/lost-found/profile` 页面。
- 未登录用户访问时，必须沿用现有受保护路由逻辑跳转登录页。
- 页面必须展示个人资料区、“我的服务”和“其他”板块。
- 页面必须能在桌面端和移动端正常展示。

### FR-2 昵称展示

- 系统必须展示用户昵称。
- 如果没有昵称，系统必须使用 email 前缀作为默认昵称。
- 昵称展示不得影响登录身份，登录仍以现有账号体系为准。

### FR-3 头像展示

- 系统必须展示头像。
- 如果没有头像，系统必须展示默认头像。
- 默认头像应稳定生成，避免每次刷新变化。

### FR-4 My Claims 入口

- 个人中心必须提供 `My Claims` 入口。
- 点击后必须进入当前用户提交的认领申请列表。
- 优先复用现有 `/claims/mine` 页面和 `GET /api/lost-found/claims/mine` 接口。

### FR-5 我的失物

- 个人中心必须提供“我的失物”入口。
- 进入后只展示当前用户发布的 `LOST` 报告。
- 列表项应至少展示物品名称、分类、地点、日期、状态和首图。
- 用户可以进入报告详情页。

### FR-6 我的拾物

- 个人中心必须提供“我的拾物”入口。
- 进入后只展示当前用户发布的 `FOUND` 报告。
- 列表项应至少展示物品名称、分类、地点、日期、状态和首图。
- 用户可以进入报告详情页。

### FR-7 FAQ

- 个人中心必须提供 FAQ 入口。
- FAQ 页面必须至少包含 Lost & Found 发布、搜索、认领、状态、图片和管理规则相关问题。
- FAQ 内容应支持后续维护，推荐先使用前端静态数组或 Markdown 源文件。

## 9. 数据与接口需求

### 9.1 当前可复用接口

| 能力 | 现有接口 |
| --- | --- |
| 查询报告 | `GET /api/lost-found/reports` |
| 查看报告详情 | `GET /api/lost-found/reports/{reportId}` |
| 我提交的认领 | `GET /api/lost-found/claims/mine` |
| 我收到的认领 | `GET /api/lost-found/claims/received` |
| 编辑报告 | `PUT /api/lost-found/reports/{reportId}` |
| 关闭报告 | `POST /api/lost-found/reports/{reportId}/close` |
| 删除报告 | `DELETE /api/lost-found/reports/{reportId}` |

### 9.2 需要新增或扩展的接口

#### 方案 A：扩展报告查询接口

在 `GET /api/lost-found/reports` 增加可选查询参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `owner` | `me` | 只返回当前登录用户发布的报告 |

示例：

```http
GET /api/lost-found/reports?owner=me&reportType=LOST&page=0&size=20&sort=createdAt,desc
GET /api/lost-found/reports?owner=me&reportType=FOUND&page=0&size=20&sort=createdAt,desc
```

优点：复用现有搜索、分页、排序和前端列表逻辑。

#### 方案 B：新增我的报告接口

新增：

```http
GET /api/lost-found/reports/mine?reportType=LOST&page=0&size=20
GET /api/lost-found/reports/mine?reportType=FOUND&page=0&size=20
```

优点：语义清晰，权限边界明确。

推荐：方案 A。当前 `LostFoundReportResponse` 已包含 `createdByMe`，`LostFoundReport` 也已有 `createdBy` 关联，按当前查询能力扩展 owner 过滤成本较低。

`owner=me` 与 `adminHidden` 的交互：公开搜索始终过滤 `adminHidden=true` 的报告；但在“我的报告”列表中，用户自己的报告应仍可见（包括被管理员下架的），并返回 `adminHidden` 标识。owner 模式实现时豁免 `adminHidden` 过滤即可，避免出现“列表看不到、URL 直达又能打开”的矛盾。

为支持该标识，需要同步扩展用户侧报告响应契约：

- 后端 `LostFoundReportResponse` 增加 `boolean adminHidden` 字段。
- 前端 `LostFoundReport` 类型增加 `adminHidden: boolean` 字段。
- 普通公开 Browse 页可以不展示该字段；`MyReportsPage` 在 `adminHidden=true` 时展示“已下架”或等价状态提示。

### 9.3 用户资料接口

当前认证返回和前端 `AuthContext` 主要包含 `email`、`role`，后端 `User` 实体当前也只包含 email、password、role、createdAt。为支持昵称和头像，建议新增用户资料能力。

新增字段（落库方案：直接加到 `users` 表，两个可空列）：

| 字段 | 存储位置 | 说明 |
| --- | --- | --- |
| `nickname` | `users.nickname` | 用户昵称，可空，1-30 字符 |
| `avatar_url` | `users.avatar_url` | 头像地址，可空 |

说明：昵称/头像属于跨模块用户资料（Mail、Calendar、Facilities 后续都可能展示），统一放 `users` 表并由 `GET /api/users/me/profile` 暴露，避免做成 L&F 私有的独立 profile 表。

接口建议：

```http
GET /api/users/me/profile
```

响应：

```json
{
  "email": "student@example.edu",
  "role": "STUDENT",
  "nickname": "Alex",
  "avatarUrl": "/api/users/me/avatar"
}
```

若本期支持编辑：

```http
PUT /api/users/me/profile
Content-Type: application/json

{
  "nickname": "Alex"
}
```

```http
POST /api/users/me/avatar
Content-Type: multipart/form-data
```

本期最小实现可只读展示：前端用现有 `email` 和默认头像生成展示，不新增资料编辑接口。阶段 2 接入昵称/头像编辑时，建议同步扩展 `AuthResponse` 与前端 `AuthContext`，使顶部导航与全站组件都能读取资料，而不仅是个人中心。

## 10. 前端开发建议

### 10.1 新增页面

- `ProfilePage.tsx`：个人中心首页。
- `MyReportsPage.tsx`：我的失物/拾物列表页（推荐，见下）。
- `LostFoundFaqPage.tsx`：FAQ 页面。

列表实现策略（拍板）：新建 `MyReportsPage`，复用 `ReportCard` 与 `searchReports`，请求 `owner=me` + `reportType`；状态筛选默认 `ALL`（不传 `status`），可切换 `OPEN`/`CLAIMED`/`CLOSED`；分页 `size=20`、`sort=createdAt,desc`。不改动现有 `ReportsPage`。

不复用 `ReportsPage` 的原因：其默认 `reportType=FOUND`、`status=OPEN`，且固定渲染“Report lost/found”按钮、以图搜物与 Agent 面板（见 `ReportsPage.tsx`）；owner 模式需要条件隐藏这些 UI，会让组件背负大量分支，复用收益低且可能影响 Browse 页。

### 10.2 路由更新

在用户侧受保护路由中新增：

```tsx
<Route path="/lost-found/profile" element={<ProfilePage />} />
<Route path="/lost-found/profile/lost" element={<MyReportsPage reportType="LOST" />} />
<Route path="/lost-found/profile/found" element={<MyReportsPage reportType="FOUND" />} />
<Route path="/lost-found/faq" element={<LostFoundFaqPage />} />
```

### 10.3 导航更新

- 顶部导航可将当前 email 展示替换为头像 + 昵称入口。
- 阶段 1 无资料接口时，导航入口使用默认头像（昵称/email 首字母生成）+ email 前缀。
- 保留 Logout。
- Claims 入口可以保留，也可以移动到个人中心；为降低改动风险，建议本期保留顶部 Claims，同时在个人中心新增入口。

### 10.4 UI 展示建议

个人中心结构：

- 顶部个人资料区：头像、昵称、email。
- “我的服务”：三个服务入口，使用图标、标题、状态摘要、进入按钮或整块点击。
- “其他”：FAQ 入口。

空状态：

- My Claims 无数据：提示还没有提交认领申请，并提供浏览拾物入口。
- 我的失物无数据：提示还没有发布失物报告，并提供发布失物入口。
- 我的拾物无数据：提示还没有发布拾物报告，并提供发布拾物入口。

## 11. 后端开发建议

### 11.1 我的报告过滤

在 `LostFoundReportController.search` 增加 `owner` 参数，或新增 `/reports/mine`。

如果采用 `owner=me`：

- 当 `owner=me` 时，查询条件增加 `createdBy.id = currentUser.id`。
- 当 `owner` 缺省时，保持现有公开报告搜索逻辑。
- 非法 owner 值返回 `422 INVALID_OWNER_FILTER`。
- `owner=me` 时豁免 `adminHidden` 过滤：用户能看到自己发布的报告（含被管理员下架的），并在响应中返回 `adminHidden` 标识；其余场景保持现有 `adminHidden = false` 过滤不变（见 §9.2）。
- 同步更新 `LostFoundReportResponse`、前端 `LostFoundReport` 类型和相关测试样例，避免响应新增字段后前后端契约不一致。

### 11.2 用户资料

最小实现：

- 不改数据库。
- 前端从 `AuthContext.user.email` 生成默认昵称和默认头像。

完整实现（落库方案：`users` 表，见 §9.3）：

- 扩展 `User` 实体，增加可空的 `nickname`、`avatarUrl`。
- 扩展 auth response 或新增 profile API；建议同时扩展 `AuthResponse` 与前端 `AuthContext`，使全站可读。
- 头像上传复用对象存储或保存为后端静态代理 URL；校验规则复用现有 `LostFoundImageRules`（类型/大小/扩展名），并限制头像尺寸。

建议分阶段：

1. 阶段 1：个人中心页面 + 默认昵称/默认头像 + 我的服务入口 + FAQ + 我的报告过滤。
2. 阶段 2：昵称编辑和头像上传。

## 12. 权限与安全

- 个人中心所有页面必须要求登录。
- 我的失物/拾物只能返回当前用户创建的报告。
- 用户不能通过修改 URL 查看其他用户的“我的报告”列表。
- 头像上传如实现，必须限制文件类型、文件大小和文件扩展名；建议复用现有 `LostFoundImageRules` 的校验约束。
- FAQ 页面不展示敏感系统配置或内部审核规则细节。

## 13. 验收标准

### AC-1 个人中心访问

- 已登录用户访问 `/lost-found/profile` 可以看到个人资料、“我的服务”和“其他”板块。
- 未登录用户访问 `/lost-found/profile` 会跳转登录页。

### AC-2 昵称与头像

- 有昵称和头像时展示真实资料。
- 没有昵称时展示 email 前缀。
- 没有头像时展示默认头像。

### AC-3 My Claims

- 点击 `My Claims` 后进入 `/claims/mine`。
- 页面只展示当前用户提交的认领申请。

### AC-4 我的失物

- 点击“我的失物”后，只展示当前用户发布的 `LOST` 报告。
- 列表支持进入详情。
- 自己发布的报告详情仍可编辑、关闭或删除。

### AC-5 我的拾物

- 点击“我的拾物”后，只展示当前用户发布的 `FOUND` 报告。
- 列表支持进入详情。
- 自己发布的报告详情仍可编辑、关闭或删除。

### AC-6 FAQ

- 点击 FAQ 后进入 FAQ 页面。
- FAQ 至少包含发布、搜索、认领、状态、图片、关闭/删除相关问题。

### AC-7 响应式

- `frontend_web` 的桌面端与窄屏（移动端浏览器）下均无明显遮挡、溢出或不可点击入口。
- 本期 `frontend_mobile` 不实现个人中心（见 §15），后续单独排期。

## 14. 测试建议

### 前端测试

- `ProfilePage` 渲染个人资料和服务入口。
- 未登录访问个人中心跳转登录页。
- 点击 My Claims、我的失物、我的拾物、FAQ 能跳转到正确路由。
- 无昵称/无头像时默认展示正确。
- 我的失物/拾物空状态展示正确。

### 后端测试

- `GET /api/lost-found/reports?owner=me&reportType=LOST` 只返回当前用户 LOST 报告。
- `GET /api/lost-found/reports?owner=me&reportType=FOUND` 只返回当前用户 FOUND 报告。
- 其他用户发布的报告不会出现在我的报告列表。
- 非法 owner 参数返回预期错误。
- 未登录访问受保护接口返回 401。
- `owner=me` 不影响公开搜索：`owner` 缺省时结果与现有行为一致（向后兼容）。
- `owner=me` 与 `status` 组合过滤：如 `owner=me&reportType=LOST&status=CLOSED` 只返回当前用户已关闭的 LOST 报告。
- `owner=me` 时包含被管理员下架的报告，并正确返回 `adminHidden` 标识。
- 用户侧报告响应包含 `adminHidden` 字段；前端 `MyReportsPage` 能识别并展示下架提示。

### 集成测试

- 用户 A 发布 LOST，用户 B 发布 FOUND；用户 A 的“我的失物”只看到自己的 LOST。
- 用户 A 提交认领申请；个人中心 My Claims 入口能进入并看到该申请。
- 用户刷新页面后个人中心仍能展示登录用户信息。

## 15. 待确认问题

1. 昵称和头像本期是否需要支持编辑，还是只做展示和默认生成？（MVP 默认只展示，见 §16）
2. “我的拾物”下是否需要同时显示别人对我拾物报告提交的认领申请入口，例如 `Claims Received`？
3. FAQ 内容是否需要中英文双语，还是先使用英文界面文案？
4. 个人中心入口名称使用 `Profile`、`Me` 还是用户昵称？
5. 我的失物/拾物列表是否需要保留现有 Browse 的搜索筛选栏，还是只保留状态筛选和排序？（§10.1 已建议：只保留状态筛选 + 排序，不保留完整搜索栏）
6. 用户自己的报告被管理员下架后，是否在“我的报告”列表中仍显示并加 `adminHidden` 标识？（§9.2 已建议：显示）
7. 个人中心本期是否覆盖 `frontend_mobile`？（§13 AC-7 已限定本期为 `frontend_web`）

## 16. 推荐 MVP 范围

建议本期先交付：

- `/lost-found/profile` 个人中心首页。
- 默认昵称和默认头像，不做资料编辑。
- “我的服务”三个入口：My Claims、我的失物、我的拾物。
- `owner=me` 报告过滤能力（含 `adminHidden` 标识，见 §9.2）。
- `/lost-found/faq` 静态 FAQ 页面。
- `frontend_web` 桌面端与窄屏基础适配。

昵称编辑、头像上传、服务统计数字、收到的认领申请聚合、`Claims Received` 入口以及 Lost & Found 通知中心（后端 `/api/lost-found/notifications` 已存在但前端未接）可以作为后续增强。
