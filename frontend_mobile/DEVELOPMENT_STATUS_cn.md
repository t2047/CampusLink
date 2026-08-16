# CampusLink Android Core Chat 开发状态与后续路线

> 最后更新：2026-08-16
>
> 当前开发分支：`main`（已合并移动端 Mail 第一阶段）
>
> Android 包名：`com.campuslink.mobile`
>
> 当前阶段：Core Chat、Facilities Mobile Phase 1/2/3、Lost & Found Native Phase 1、Mobile App Shell 与一级页面 UI Polish 已完成开发和本地自动化验证

本文档用于移动端开发交接。请在每次功能合并、接口变更或技术方案调整后同步更新，已经完成的事项保留历史记录，不要直接删除。

## 1. 当前目标与范围

移动端以统一 Core Chat 为基础，并按模块逐步补充高频业务的原生页面。Facilities 与 Lost & Found 使用同一套认证、网络和 Compose 导航基础，但各自保持独立的 API、Repository、ViewModel 和 UI 目录，方便多人并行开发。

当前调用链：

```text
Android Core Chat
→ https://campuslink.tokeninf.xyz/api/chat/*
→ Nginx
→ Spring Boot Chat Backend
→ Chat Core 编排层
→ Mail / Facilities / Lost & Found / Utility Agents
```

Core Chat 当前只支持文字消息；Lost & Found 原生发布页已支持从设备选择图片。聊天图片/文件/语音和推送通知仍未实现。Mail 原生页面已完成第一轮 REST 对接，Gmail OAuth 和云端真实联调仍取决于环境配置。

## 2. 已完成的工程基础

### 2.1 Android 技术栈

- Kotlin 原生 Android 项目；
- Jetpack Compose + Material 3；
- MVVM + Repository + StateFlow；
- OkHttp 负责认证请求和 SSE 长连接；
- Kotlin Serialization 负责 JSON 解析；
- Room 保存本地会话和消息；
- SQLCipher 加密 Room 数据库；
- Android Keystore 保护 JWT 和数据库密钥；
- CommonMark 解析聊天 Markdown，不使用 WebView，也不渲染原始 HTML；
- Coil 加载 Lost & Found 候选图片；
- 单 Activity `Screen` 导航已统一接入 Android System Back；顶部 Back 与系统 Back 使用同一返回规则；
- 当前 route、详情 ID、Chat conversation ID 和动态 return target 使用轻量 `rememberSaveable` 状态，configuration change 后可恢复；
- `minSdk 26`；
- `compileSdk/targetSdk 36`；
- 构建基线为 JDK 17。

### 2.2 工程目录

```text
frontend_mobile/
├── app/
│   ├── schemas/                      Room 导出架构
│   └── src/
│       ├── main/                     公共正式代码
│       ├── local/                    本地模拟器明文网络例外
│       ├── test/                     JVM 单元测试
│       └── androidTest/              模拟器/真机测试
├── config/detekt/                    Kotlin 静态检查配置
├── gradle/wrapper/                   固定 Gradle Wrapper
├── README.md
├── README_cn.md
└── DEVELOPMENT_STATUS_cn.md          本文档
```

## 3. 已完成功能

### 3.1 构建环境和 API 地址

| 构建变体 | API 地址 | 当前状态 | 用途 |
|---|---|---|---|
| `localDebug` | `http://10.0.2.2:8080/` | 已完成 | 模拟器连接开发者电脑的本地后端 |
| `demoDebug` | `https://campuslink.tokeninf.xyz/` | 已完成 | Debug 签名，供组员安装测试 |
| `prodRelease` | `https://campuslink.tokeninf.xyz/` | 已完成配置 | 正式签名发布版本 |

只有 `localDebug` 可以通过 Network Security Config 访问 `10.0.2.2` 的明文 HTTP。Demo 和生产版本禁止明文流量，不信任自签名证书，也没有关闭主机名或证书链校验。

`prodRelease` 必须通过 `CAMPUSLINK_RELEASE_*` 环境变量提供正式签名文件和密码，缺少配置时构建会主动失败，防止误发 Debug 或未签名 APK。

### 3.2 登录与注册

状态：**已完成**

- 调用现有 `POST /api/auth/login`；
- 调用现有 `POST /api/auth/register`；
- 登录和注册共用现有 Spring Boot 用户体系；
- 支持邮箱格式、密码最小长度和重复密码校验；
- 登录成功后保存 Token、邮箱和角色；
- App 重启后自动恢复登录状态；
- 收到 401 时清除登录状态并返回登录页；
- 网络或认证错误会显示在登录界面；
- 登录前也可以切换中英文。

### 3.3 本地会话管理

状态：**已完成**

- 创建新会话；
- 展示当前账号的会话列表；
- 打开和切换会话；
- 删除单个会话；
- 清除当前账号全部本地历史；
- 首条用户消息自动生成本地会话标题；
- 每个会话使用固定 UUID 作为 `sessionId`；
- 不同账号的本地会话按邮箱隔离；
- App 异常退出后，未结束的流式消息会标记为 `INTERRUPTED`；
- 当前历史只保存在设备，不会上传或跨设备同步。

### 3.4 Core Chat SSE

状态：**已完成**

使用现有接口：

```http
GET  /api/chat/stream?message=...&sessionId=...&traceId=...
POST /api/chat/resume?traceId=...
```

- 每条用户消息生成独立 UUID `traceId`；
- 请求自动携带 Bearer JWT；
- OkHttp 增量读取 SSE，不等待完整响应；
- 支持流式 Token 逐步追加；
- 支持主动停止生成；
- 支持失败或中断后的重新发送；
- 新消息到达后自动滚动到最新内容；
- 网络流结束但服务端未发送显式 `done` 时，客户端会安全补全结束状态；
- 错误不会导致聊天页面崩溃；
- 未知事件会降级为普通消息，不阻塞后续事件。

SSE 解析器已经处理：

- `\n` 和 `\r\n`；
- 一个事件跨多个网络分片；
- 多行 `data:`；
- SSE 注释行；
- 非法 JSON；
- 未知事件名；
- 重复 `done`；
- 无显式 `done` 的连接关闭。

已支持事件：

```text
intent_detected
token
agent_start
agent_step
agent_done
agent_error
match_results
utility_start
utility_result
utility_done
confirm_required
error
done
message
```

### 3.5 Agent 与 Utility 执行过程

状态：**已完成**

- Agent 开始、执行步骤、完成和错误会保存到本地消息；
- Utility 开始、结果和完成会保存到本地消息；
- 聊天气泡提供可折叠的执行时间线；
- 默认收起，避免执行细节遮挡主要回复；
- Agent 错误会标记显示，但不会终止整个 App。

### 3.6 HITL 确认流程

状态：**已完成**

- 收到 `confirm_required` 后保存待确认状态；
- 弹出确认面板，展示 Agent、摘要和结构化详情；
- 用户未确认前禁止发送新消息；
- 确认或取消调用 `/api/chat/resume`，并复用原会话 `sessionId`；
- 处理期间禁用按钮，避免重复点击执行同一写操作；
- 确认状态保存到 Room，App 重启后仍能恢复；
- 409、过期或重复执行会以错误消息结束当前操作，并恢复输入状态。

注意：后端目前没有 SSE 断点续传 ID。网络中断后的“恢复”是恢复本地状态并允许重新发送，不是从丢失的某个 Token 继续下载。

### 3.7 Lost & Found 匹配卡片

状态：**已完成 Chat 内展示**

收到 `match_results` 后可以展示：

- 记录 ID；
- LOST / FOUND 类型；
- 物品名称；
- 类别；
- 描述；
- 颜色；
- 地点；
- 日期；
- 图片；
- 总匹配分数；
- 匹配原因；
- 分项分数；
- 匹配模式。

Lost & Found 原生详情页已经开发，但 Chat 内匹配卡片尚未接入原生详情导航。候选数据仍由 Chat Core 和 Lost & Found Agent 提供，移动端不在本地重复执行匹配算法。

### 3.8 Markdown 与界面

状态：**已完成基础版本**

- 使用 CommonMark 解析；
- 不使用 WebView；
- 原始 HTML 被忽略；
- 支持标题、段落、列表、引用、代码和链接文本；
- 支持浅色与深色模式；
- 支持中文和英文；
- 设置页支持退出登录和清理本地历史。

Home、Agent Core、Profile 与 Bottom Navigation 已完成第一轮品牌视觉、明暗主题、动态字体和多尺寸优化；Facilities、Lost & Found 等业务内页仍以功能验证为主，尚未完成同等级的视觉一致性改造。

### 3.9 Facilities Mobile Phase 1

状态：**已完成**

- Chat 和会话页提供统一 Services 入口，Facilities 作为首个原生服务模块；
- Facilities 首页和 Search Spaces 入口沿用现有单 Activity、Compose 手动导航；
- 空间搜索调用真实 `GET /api/facilities/spaces`，支持关键词、楼宇、空间类型、最小容量和多个设备条件；
- 搜索页包含加载、空结果、错误、重试和重置状态；
- 空间详情调用真实 `GET /api/facilities/spaces/{spaceId}`；
- 可用性查询调用真实 `GET /api/facilities/spaces/{spaceId}/availability`；
- 日期时间按 Spring `LocalDateTime` 契约发送 `yyyy-MM-dd'T'HH:mm:ss`，不附加 `Z` 或时区偏移；
- 日期或时间变化会立即清除旧可用性结果，并防止较慢的旧请求覆盖新选择；
- 新增共享认证 HTTP 客户端，自动附加现有 SessionStore JWT，并统一处理 JSON、401、网络错误和请求取消；
- 已在 Android 模拟器使用 `localDebug` 对本地 Spring Backend 和 phpStudy MySQL 完成真实搜索、详情及可用性只读验证。
- 本阶段开始前，Android Central Agent → Facilities Agent → MCP 的搜索及预约 HITL/Confirm 链路已完成人工验证；该链路继续由 Core Chat 复用，不在原生页面重复实现 Agent 业务逻辑。

本阶段没有实现预约创建、我的预约、取消预约和维修请求，也没有在移动端复制后端冲突检测或权限规则。

### 3.10 Facilities Mobile Phase 2

状态：**已完成**

- 空间可用时显示 `Book This Space`，用户必须先通过原生 Compose 确认对话框，确认后才调用 `POST /api/facilities/bookings`；
- 创建过程提供提交锁定、成功摘要、Booking Details 和 My Bookings 入口；
- Backend 返回 `BOOKING_CONFLICT` 时清除旧 availability，要求用户重新检查；
- My Bookings 调用真实用户 ownership API，提供加载、空结果、错误、重试和业务排序；
- Booking Details 展示空间、时间、状态和审计时间，404 不区分不存在或非 owner；
- Cancel Booking 必须经过确认对话框，成功后详情和列表立即刷新为 `CANCELLED`，且取消按钮消失；
- Android 不复制冲突检测、ownership、取消资格或持久化规则，Spring Backend 仍是最终 authority；
- 已在 `localDebug` 模拟器通过真实 Spring Backend 和 phpStudy MySQL 验证 Create → List → Details → Cancel；
- 重叠时段在真实运行中由 Availability preflight 返回 `BOOKING_CONFLICT` 并阻止 Book 按钮；测试创建的预约最终均已取消。

Phase 2 当时未包含 Maintenance 原生页面；该部分现已在 Phase 3 完成。Facilities 原生确认属于 REST 页面误操作防护，不替代 Central Agent 的 HITL `/api/chat/resume`。

### 3.11 Facilities Mobile Phase 3

状态：**已完成**

- Facilities 首页新增 Report Maintenance 和 My Maintenance Requests 两个入口；
- Report Maintenance 复用真实 `GET /api/facilities/spaces` 加载空间，不使用硬编码房间或手工 ID；
- Space Details 新增 `Report a facility issue`，进入表单时自动预选当前真实空间，同时仍可更改；
- 提交严格使用用户端 `POST /api/facilities/maintenance`，只发送 `spaceId`、`facilityType`、`description` 和真实 `LOW / MEDIUM / HIGH` 优先级，不发送 `userId`；
- 提交前显示原生确认对话框，提交期间锁定重复点击；成功后保留 Backend response，并提供详情和列表入口；
- 表单覆盖必填、255/2000 字符上限、Backend 字段校验错误、401、空间 404、网络、超时、5xx 和解析失败；
- My Maintenance Requests 使用真实 ownership API `GET /api/facilities/maintenance`，包含加载、空状态、错误、重试、活动请求优先及更新时间倒序；
- Maintenance Details 使用 `GET /api/facilities/maintenance/{ticketId}`，不存在与非 owner 都按相同安全 404 展示；
- 普通用户只查看 `SUBMITTED / IN_PROGRESS / RESOLVED / CANCELLED`，没有状态修改入口，也不调用 Admin-only status API；
- Spring `LocalDateTime` 继续作为 Singapore wall-clock string 处理，不添加 `Z` 或时区偏移；
- 已在 Pixel 7 Android Emulator（API 36）使用 `localDebug` 对本地 Spring Backend 和 phpStudy MySQL 完成 Submit → List → Details 真实运行时验证；创建并读回 Ticket #4：COM2-03-12、Projector、HIGH、SUBMITTED；
- Space Details → Report Issue 的 COM2-03-12 自动预选联动也已完成真实模拟器验证；
- Central Agent Facilities Search / Booking HITL 代码本阶段没有修改。

### 3.12 Lost & Found Native Phase 1

状态：**已完成开发和本地自动化验证，等待真实后端/设备联调与 PR 合并**

已完成的用户流程：

```text
Services
→ Lost & Found 首页
→ 浏览 LOST / FOUND OPEN 记录
→ 按关键词、类别、颜色、地点和日期筛选
→ 查看记录详情和图片
→ 发布 LOST / FOUND（0–5 张图片）
→ 对 OPEN FOUND 提交认领证明
→ 查看 My Claims / Received Claims
→ FOUND 发布者批准或拒绝认领
```

页面与导航：

- Services 新增 Lost & Found 入口，不改变 Facilities 的入口和返回路径；
- 新增 Lost & Found 首页、浏览筛选、详情、发布和 Claims 页面；
- Browse 默认显示 `FOUND + OPEN`，可切换 LOST，支持重置、错误重试和每页 20 条的继续加载；
- 详情页展示名称、类型、类别、描述、颜色、地点、日期、时间和多图；
- 只有 `FOUND + OPEN + 非本人发布` 的记录显示 Submit Claim；
- Claims 页面支持 My Claims 与 Received 两种视图；
- 收到的 `SUBMITTED` 申请可以批准或拒绝，批准前明确提示会把报告改为 `CLAIMED` 并拒绝其他待处理申请。

API 与分层：

- `GET /api/lost-found/reports`：浏览、组合筛选与分页；
- `GET /api/lost-found/reports/{reportId}`：详情；
- `POST /api/lost-found/reports`：multipart 发布报告；
- `POST /api/lost-found/reports/{reportId}/claims`：提交认领；
- `GET /api/lost-found/claims/mine`：我提交的认领；
- `GET /api/lost-found/claims/received`：我收到的认领；
- `POST /api/lost-found/claims/{claimId}/approve|reject`：审核认领；
- 新增 `LostFoundApi`、`LostFoundRepository`、Lost & Found ViewModel 与 Compose UI 目录；
- 复用共享 `AuthenticatedHttpClient`，仅增加 multipart RequestBody 支持；JWT、401 清理和后端结构化错误映射保持统一；
- Facility API、Repository、ViewModel 和页面没有被 Lost & Found 代码依赖或改写。

输入与安全校验：

- 物品名 2–100 字符、描述 10–2000 字符、地点必填；
- 日期使用 `YYYY-MM-DD` 且不能晚于当前日期；
- 颜色最多 50 字符，时间描述最多 100 字符；
- 图片最多 5 张，仅接受 JPEG、PNG、WebP，每张最大 10 MB；
- 认领证明 10–1000 字符，审核意见最多 500 字符；
- 移动端校验用于即时反馈，权限、重复认领、状态冲突和最终图片规则仍由 Spring Backend 决定；
- 不把 JWT、认领证明、图片字节或完整请求 Header写入日志或本地数据库。

本阶段明确未实现：

- 编辑、关闭、删除本人报告；
- “我的发布”独立列表和通知中心；
- Chat 匹配卡片点击跳转原生详情；
- 原生 Agent 自然语言输入与多模态匹配入口；
- 上传进度百分比、图片压缩/裁剪、相机拍摄、断点续传；
- 日期选择器、本地化文案和无障碍专项优化；
- 真机和云端 HTTPS 环境的完整人工验收。

### 3.13 Mail Mobile Phase 1

状态：**已完成第一轮开发、自动化验证；Gmail OAuth 云端联调待完成**

本阶段以 `docs/mail/MOBILE_DEVELOPMENT_cn.md` 为后端契约，新增独立的 Mail Repository、API、ViewModel 和 Compose 页面，未修改现有 Chat、Facilities、Lost & Found 业务规则。

#### 已完成内容

- Home、Services 和 Mail 页面入口已经接入统一导航；Mail 页面不会再把用户误导到 Agent Core；
- Gmail 连接状态查询、授权链接获取和断开接口已接入；未连接时能够识别 409 `GMAIL_NOT_CONNECTED`，自动打开服务端返回的 `auth_url`；
- 授权链接通过系统浏览器打开，回调由服务端完成，回到 App 后会自动轮询当前用户状态，超时后仍可使用 `Check again`；App 不保存 Google code/state；
- 收件箱、已发送、归档、回收站和垃圾邮件文件夹；
- 关键字搜索、未读筛选、加星筛选、0 起始页码分页和加载更多；
- 邮件列表卡片、详情页、打开详情自动标记已读、标记已读/未读、加星/取消加星、归档和移入回收站；
- 新建邮件：多个收件人、主题和正文校验，发送中按钮锁定，成功后返回上一页；
- 日历事件列表、新建、编辑、删除；删除正确处理 HTTP 204 空响应；
- 从邮件抽取日程建议，支持逐条勾选后再导入，避免服务端在用户确认前写入日历；抽取和导入使用长超时；
- 邮件 API 使用独立 `MAIL_API_BASE_URL`：`localDebug` 直连 `http://10.0.2.2:5000/`，`demoDebug/prodRelease` 使用 `https://campuslink.tokeninf.xyz/`；登录、Chat、Facilities、Lost & Found 仍使用原 `API_BASE_URL`；
- 网络层增加 DELETE、逐请求读写超时、204 空响应和 `auth_url` 错误字段解析；动态 ID 使用编码路径段，防止斜杠破坏路由；
- 不渲染 `body_html` 原始 HTML，详情页只显示纯文本正文；不在 Logcat、本地数据库或 UI 中输出 JWT、OAuth code、密码或完整请求头；
- 新增 Mail API 契约测试和 Mail ViewModel 测试，覆盖分页筛选、授权错误、204 删除、收件人校验、日历时间范围校验和状态刷新。

#### 当前待完成内容

- 使用真实 Gmail OAuth 配置完成 Android 模拟器和真机云端验收；
- 授权浏览器返回 App 后已具备最多 60 次、每 2 秒一次的基础轮询；系统杀进程、跨设备回调和更完整的生命周期恢复仍待完成；
- 日历月视图/周视图、日期时间选择器和时区显示，目前使用 ISO 文本输入和列表视图；
- 邮件正文附件、图片、HTML 安全富文本和附件下载，目前只支持纯文本正文；
- 批量操作、草稿、永久删除、邮件分类筛选和本地邮件缓存；
- 断网重试队列、分页下拉刷新、后台同步与推送通知；
- 邮件 Agent 自然语言操作仍通过现有 Core Chat SSE/MCP 链路，Mail 原生页不直接调用 `POST /api/mail/agent/chat`；
- 真实账号隔离、OAuth 断开/重新授权、Gmail API 限流和超时场景的真机验收；
- 完整中英文 UI 文案、TalkBack、动态字体、平板和横屏适配。

#### 已执行验证

- `compileDemoDebugKotlin`：通过；
- `testDemoDebugUnitTest`：87 个测试通过（包含新增 Mail API/ViewModel 测试）；
- `detekt`、`lintDemoDebug`、`assembleDemoDebug`：通过；具备 Gmail 配置的云端人工验收仍待完成。

### 3.14 Mobile App Shell

状态：**已完成开发、自动化验证和 Pixel 7 模拟器 smoke**

- 登录后一级信息架构统一为 Home、Agent Core、Profile 三个 Material 3 底部导航项；
- Home 聚合 CampusAgent、Facilities、Lost & Found、原生 Mail，以及 My Bookings、My Maintenance、My Claims 快捷入口；
- Agent Core 继续使用原 Conversation List → Chat 架构，未改动 Chat SSE、Room、HITL、ViewModel 或 Repository；
- Profile 复用现有语言、深色模式、清聊天记录和退出登录逻辑，并展示 SessionStore 中真实存在的邮箱和角色；
- `NavigationState`、route key、System Back 和 recreation 保存已扩展到三个一级 tab；Chat 和业务深层页面不显示底部导航；
- 原 Services/Settings route 暂时保留用于旧保存状态兼容，新入口不再依赖它们；
- 主题增加统一的 CampusLink mint/green 明暗配色，保留现有 dark mode。

### 3.14 Mobile UI Polish Phase 2（一级页面）

状态：**已完成开发、自动化验证和 Pixel 7 模拟器视觉审计；业务内页尚未纳入本阶段**

- 新增 `CampusSpacing`、`CampusCorners`、统一 Typography/Shapes、Section Header 与 Icon Container 等轻量 design tokens/components；
- Home 使用本地时间问候、CampusAgent Hero、两列 Campus Services 与次级 Quick Access；只展示 Facilities、Lost & Found、Agent Mail 和三个已有真实快捷入口；
- Agent Core 建立 Page Title → New Chat → Recent Conversations → List 的视觉层级，空状态提供明确说明和 Start New Chat；会话卡只展示真实 title 与更新时间；
- Profile 使用真实 email/role、邮箱前缀 initials、统一 Settings Row、真实 BuildConfig 版本，并为本地聊天记录清理和退出登录增加确认；
- Bottom Navigation 接入应用内中英文文案，统一选中 indicator、未选中颜色、icon/label 对齐及系统导航 inset；
- Light/Dark color scheme 分别定义 background、surface、surface variant、outline 与 error 层级；状态栏和导航栏图标随应用主题切换；
- Home、Agent Core、Profile、Bottom Navigation 新增文案已接入项目既有 `UiStrings + AppLanguage` 运行时中英文机制；历史业务内页尚未在本阶段全量本地化；
- 可点击服务卡和设置行补充 button role/整行触控语义，交互 icon 补充 content description；Home/Profile 保持滚动，新增 320dp small phone Preview；
- Pixel 7 API 36 已检查浅色、深色、横屏、滚动与 1.3× 动态字体；首屏、两列 grid、Hero、Quick Access 和底栏标签未发现裁切或遮挡；
- Chat 仅统一背景、TopAppBar、返回语义和输入框圆角；SSE、HITL、Retry、Room、ViewModel、Repository 与业务契约均未修改。

下一阶段视觉工作：Facilities / Lost & Found 业务内页视觉一致性，不应将当前状态描述为整个 Mobile UI 已全部 polish。

## 4. 本地数据与安全

### 4.1 已完成的安全措施

- JWT 使用 Android Keystore 中的 AES-GCM 密钥加密；
- Room 数据库使用 SQLCipher；
- SQLCipher 密钥为随机 256 位数据；
- 数据库密钥经 Android Keystore 包装后再保存；
- 禁止 Android 系统备份和数据迁移导出；
- Demo/Prod 禁止明文 HTTP；
- 不提供自签证书信任或 TLS 绕过；
- 不向 Logcat 主动输出 JWT、密码、完整 Header、邮件正文或认领证明；
- 正式签名文件、Keystore、`local.properties` 和构建产物已被 Git 忽略。

### 4.2 当前安全边界

- App 被卸载后 Keystore 密钥会丢失，本地加密数据库无法继续读取，这是预期行为；
- 本地会话以账号邮箱隔离，但尚未实现服务端设备撤销列表；
- JWT 过期依赖服务端返回 401，客户端暂未解析 Token 过期时间做提前刷新；
- 当前后端没有 Refresh Token，因此过期后需要重新登录；
- Root 或被攻破的 Android 设备不属于当前安全保证范围。

## 5. HTTPS 和云端状态

### 5.1 仓库内已完成

- Nginx 使用正式域名 `campuslink.tokeninf.xyz`；
- 保留 `/.well-known/acme-challenge/`；
- 当前 HTTP 已 301 跳转 HTTPS；
- TLS 仅允许 1.2/1.3；
- 已配置 HSTS；
- `deploy/bootstrap_https.sh` 会验证 DNS 指向当前服务器；
- 脚本只清理当前域名明确标记的自签占位证书；
- 使用 Certbot Webroot 申请 Let’s Encrypt；
- Certbot 每 12 小时检查续期；
- Web 容器每小时检查证书摘要，更新后执行 Nginx 平滑 reload；
- CD 在证书环境变量缺失时会在重启容器前失败，避免把现有网站跳转到自签 HTTPS。

### 5.2 当前线上状态与剩余验收

状态：**可信 HTTPS 基础部署已完成，专项联调待完成**

截至 2026-08-16，线上基础验收结果如下：

- `campuslink.tokeninf.xyz` 已解析到当前 AWS EC2；
- HTTPS 首页返回 `200`；
- HTTP 自动返回 `301` 并跳转到 HTTPS；
- 证书由 Let’s Encrypt 签发，证书域名为 `campuslink.tokeninf.xyz`；
- 当前证书有效期为 2026-08-15 至 2026-11-13；
- 正式域名和证书维护邮箱保存在 GitHub Secrets，由 CD 安全同步到服务器 `.env`，仓库不保存真实邮箱；
- CD 已完成镜像拉取、容器健康检查、证书复用和 Nginx 重启后的就绪检查；
- 浏览器访问无证书警告，Android Demo/Prod 继续使用系统证书链校验，不包含自签名信任或 TLS 绕过。

可重复执行以下命令检查公开端点和证书：

```bash
curl -I http://campuslink.tokeninf.xyz/
curl -I https://campuslink.tokeninf.xyz/
openssl s_client -connect campuslink.tokeninf.xyz:443 \
  -servername campuslink.tokeninf.xyz </dev/null
```

预期 HTTP 返回 301，HTTPS 证书域名正确、浏览器无警告、证书发行者不是域名自身。

以下专项验收仍需完成并记录结果：

- 在真实 Android 设备上完成登录、Core Chat 和至少一个 Agent 的完整云端链路；
- 对经过 Nginx 的 SSE 连接执行长时间稳定性、断网和重连测试；
- 在服务器执行并记录 `certbot renew --dry-run` 续期演练。

## 6. 构建与测试

### 6.1 本地命令

```bash
cd frontend_mobile
./gradlew detekt
./gradlew lintDemoDebug
./gradlew testDemoDebugUnitTest
./gradlew assembleDemoDebug
./gradlew connectedDemoDebugAndroidTest
```

Demo APK：

```text
app/build/outputs/apk/demo/debug/app-demo-debug.apk
```

### 6.2 当前测试结果

| 检查 | 当前结果 |
|---|---|
| Detekt | 通过 |
| Android Lint | 通过，0 个阻断错误 |
| JVM 单元测试 | 84 个通过 |
| 模拟器测试 | 28 个通过 |
| `assembleLocalDebug` | 通过 |
| `assembleDemoDebug` | 通过 |
| Web Docker 镜像构建 | 通过 |
| Nginx `nginx -t` | 通过 |
| Docker Compose 配置 | 通过 |
| GitHub Actions YAML | 通过 |

JVM 测试覆盖 SSE 分片、CRLF、多行数据、未知事件、非法 JSON、认证 API、Room Repository、共享认证 HTTP 客户端、Facilities API、Lost & Found 搜索/详情/multipart 发布/认领 API，导航 back reducer/route 保存，以及空间、可用性、预约、维修和 Lost & Found 业务 ViewModel 的成功、空结果、校验、错误、排序、重复提交、安全 404 与审核状态；新增覆盖 Home 时间问候、Profile initials 与一级页面中英文关键文案。设备测试覆盖登录页启动、Room v1 架构创建、Home Hero/Services/Agent Mail/Quick Access 与回调、Agent Core 空/列表状态、Profile identity/preferences/危险操作确认、Bottom Navigation 选中行为、My Bookings 展示、预约创建/取消确认、维修表单/提交确认/列表/只读状态详情、Lost & Found 首页/详情，以及三个一级 tab、Facilities、Lost & Found、Chat 的系统 Back 与 Activity recreation 导航恢复。

Pixel 7 API 36 模拟器已使用 `demoDebug` 保留真实登录态完成运行时 smoke：Home → Agent Core → Profile → Home、Facilities/Lost & Found → Back → Home、Mail 入口 → 原生 Mail 页面、三个 Quick Access、Conversation → Chat → Back，以及 Home/Profile/Agent Core 横竖屏状态恢复均通过。由于该 smoke 账号未配置 Gmail OAuth，邮件真实列表、发信和日历云端操作仍需单独授权验收。为避免删除现有模拟器聊天与凭据，Clear Chat History 和 Log Out 使用 Compose 回调测试验证，未在 smoke 中实际执行破坏性操作。

### 6.3 CI/CD

`.github/workflows/mobile-ci.yml` 已实现：

- Gradle Wrapper 校验；
- Detekt；
- Android Lint；
- Kotlin/JVM 单元测试；
- `assembleDemoDebug`；
- Java/Kotlin CodeQL；
- PR 依赖审查；
- 上传 `CampusLink-core-chat-demo.apk`，保留 14 天。

`.github/workflows/mobile-nightly.yml` 已实现夜间或手动模拟器测试，包括 Room 架构和 Compose UI 测试。

APK 不会打包进 Docker。Docker CD 只负责服务器，Android CI 单独生成 APK。

## 7. 待办路线

### P0：必须先完成

#### 7.1 可信 HTTPS 与真实云端专项验收

- 状态：**基础部署已完成，专项验收进行中**
- 已完成：AWS EC2、DNS、80/443、Let’s Encrypt 可信证书、HTTP 301、GitHub Secrets、自动 CD 和浏览器验证
- 依赖：真实 Android 设备、可用测试账号、云端 Chat Core、服务器续期演练权限
- 建议负责人：DevOps / 云端负责人
- 剩余验收标准：
  - 真实 Android 设备通过系统证书链完成云端登录和 Chat 请求；
  - SSE 长连接经过 Nginx 不缓冲、不提前断开，并完成断网场景验证；
  - `certbot renew --dry-run` 续期演练成功并保存结果。

#### 7.2 使用真实 Android 设备完成 Core Chat 验收

- 状态：**云端可信 HTTPS 已可用；本地模拟器已完成主要 Chat/Facilities 链路验证，真实物理设备端到端验收待完成**
- 依赖：真实 Android 设备、可用测试账号、云端 Chat Core 和至少一个 Agent
- 建议负责人：Android 开发者 + Chat Core 开发者
- 验收标准：
  - 登录、注册、退出和 401 流程正常；
  - 普通问答可以流式显示；
  - Utility 时间查询可用；
  - Facilities 搜索可用；
  - Lost & Found 搜索能展示卡片；
  - 写操作分别完成取消和确认；
  - 停止、重试和网络中断恢复可用；
  - App 重启后历史和待确认状态可恢复。

#### 7.3 将移动端功能通过 PR 合并到 `main`

- 状态：**已完成**
- 已完成：Core Chat 第一版、HTTPS/CD、Facilities Phase 1 和 Phase 2 已通过独立提交或 PR 进入 `main`
- 开发中：Lost & Found Native Phase 1 已在 `feature/mobile-lost-found` 完成开发和本地验证，尚未合并
- 持续要求：后续移动端功能仍需通过功能分支和 PR；所有 GitHub 检查通过后再合并，提交、PR 标题、描述和协作备注使用中文。

### P1：Core Chat 第二阶段

#### 7.4 增加 Mock Chat Backend 的端到端测试

- 状态：**未开始**
- 目标：在 CI 中稳定模拟完整 SSE、HITL、401、409、断流和 `match_results`，不依赖云端环境。
- 验收标准：主要聊天状态机和边界场景均可自动回归。

#### 7.5 改进网络恢复

- 状态：**未开始**
- 当前限制：中断后只能重新发送整条消息。
- 依赖：后端提供 SSE Event ID、幂等消息 ID 或恢复协议。
- 验收标准：短暂断网后不会重复写操作，并可从服务端确认的事件位置恢复。

#### 7.6 改进认证生命周期

- 状态：**未开始**
- 依赖：后端 Refresh Token 或重新认证方案。
- 验收标准：Token 即将过期时安全续期；刷新失败才退出；多设备撤销可控。

#### 7.7 完善聊天体验

- 状态：**未开始**
- 内容：
  - 会话重命名；
  - 搜索本地历史；
  - 复制消息；
  - 更明确的重试位置；
  - 代码块复制；
  - 可点击安全链接；
  - 时间戳；
  - 更完整的加载、空状态和离线提示；
  - 长列表性能优化。

#### 7.8 无障碍和多设备适配

- 状态：**未开始**
- 验收标准：TalkBack、动态字体、横屏、平板、不同 DPI、深色对比度和触控区域检查通过。

### P2：原生业务页面

#### 7.9 Lost & Found 原生页面

- 状态：**Phase 1 已完成开发和本地自动化验证；真机/云端联调和 PR 合并待完成**
- 已完成：Services 入口、浏览筛选、分页、详情、多图发布 LOST/FOUND、认领申请、My Claims、Received Claims 和批准/拒绝。
- 下一阶段：我的发布、编辑/关闭/删除、通知、Chat 卡片跳转、日期选择器、图片压缩/拍摄、完整中英文文案、更完整的 Compose UI 覆盖与真机验收。

#### 7.10 Facilities 原生页面

- 状态：**Phase 1、Phase 2 和 Phase 3 已完成**
- 已完成：Services 入口、设施搜索、空间详情、可用性查询、预约创建、我的预约、预约详情、取消预约、维修请求、我的维修请求、维修详情和状态跟踪，并已连接真实 Spring Backend。
- 当前用户端 Facilities 范围无缺失项；Admin Facilities 与维修状态更新不属于普通用户 Android 范围。

#### 7.11 Mail 原生页面

- 状态：**第一轮原生页面已完成，云端 OAuth 和产品化功能进行中**
- 已完成：Gmail 连接入口、邮件文件夹、搜索、未读/加星筛选、分页、详情、已读/未读、加星、归档、回收站、发信、日历 CRUD、邮件日程抽取与确认导入。
- 依赖：云端 Gmail OAuth、正式设备验收和邮件服务稳定性。
- 下一阶段：OAuth 回调自动轮询、日历月/周视图、附件、草稿、批量操作、离线缓存、推送通知和完整无障碍适配。

### P3：产品化与发布

#### 7.12 图片、文件和语音消息

- 状态：**暂缓**
- 依赖：Chat Backend 和 Agent 契约支持上传、存储、病毒检查、大小限制和权限控制。

#### 7.13 推送通知

- 状态：**未开始**
- 建议方案：FCM。
- 场景：匹配结果、认领审批、预约状态、维修状态和重要邮件。

#### 7.14 崩溃、性能与隐私监控

- 状态：**未开始**
- 要求：不得上传 JWT、邮件正文、认领证明或其他敏感数据；需先定义脱敏和用户同意策略。

#### 7.15 Play Store 发布

- 状态：**未开始**
- 内容：正式 Keystore 托管、AAB、版本策略、隐私政策、数据安全表、截图、测试轨道和回滚方案。

## 8. 协作规范

- 不要把真实 API Key、JWT、密码、OAuth Token、签名文件或证书私钥提交到 Git；
- 不要为了调试在 Demo/Prod 中关闭 TLS 校验；
- 不要硬编码 EC2 IP，统一使用正式域名；
- 修改 Chat SSE 事件时，同时更新：
  - `Models.kt`；
  - `SseParser.kt`；
  - `ChatRepository.kt`；
  - 对应测试；
  - 本文档；
- 修改 Room 实体时必须：
  - 提升数据库版本；
  - 增加 Migration；
  - 更新导出 Schema；
  - 增加迁移测试；
- 新增网络请求时必须处理 401、超时、断网和取消；
- 所有 GitHub 提交说明、PR 标题、PR 描述和协作备注使用中文；
- 合并前至少运行 Detekt、Lint、单元测试和 Demo APK 构建；
- 不直接向 `main` 提交移动端大功能，应通过独立功能分支和 PR。

## 9. 快速接手步骤

1. 安装 Android Studio、SDK 36 和 JDK 17；
2. 用 Android Studio 打开 `frontend_mobile/`；
3. 等待 Gradle 同步；
4. 先运行 `testDemoDebugUnitTest`、`lintDemoDebug` 和 `detekt`；
5. 本地后端联调选择 `localDebug`；
6. 云端证书签发完成后选择 `demoDebug`；
7. 不要在本地创建或提交真实 Release Keystore；
8. 开发新功能前先阅读本文档、根目录 `DEPLOYMENT.md` 和 Chat Backend Controller；
9. 完成工作后更新“已完成功能”“当前测试结果”和“待办路线”。
