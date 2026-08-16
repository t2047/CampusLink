# CampusLink Android Core Chat 开发状态与后续路线

> 最后更新：2026-08-16
>
> 当前基线分支：`main`
>
> Android 包名：`com.campuslink.mobile`
>
> 当前阶段：Core Chat 第一版及 Facilities Mobile Phase 1、Phase 2、Phase 3 已完成

本文档用于移动端开发交接。请在每次功能合并、接口变更或技术方案调整后同步更新，已经完成的事项保留历史记录，不要直接删除。

## 1. 当前目标与范围

当前移动端优先实现统一 Core Chat，而不是把 Web 端所有业务页面逐一改写成原生 Android 页面。

当前调用链：

```text
Android Core Chat
→ https://campuslink.tokeninf.xyz/api/chat/*
→ Nginx
→ Spring Boot Chat Backend
→ Chat Core 编排层
→ Mail / Facilities / Lost & Found / Utility Agents
```

当前版本只支持文字消息。图片、文件、语音、推送通知和各业务模块的完整原生页面不在第一阶段范围内。

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

首版不会从卡片跳转到原生 Lost & Found 详情页，因为该业务页面尚未开发。候选数据由 Chat Core 和 Lost & Found Agent 提供，移动端不在本地重复执行匹配算法。

### 3.8 Markdown 与界面

状态：**已完成基础版本**

- 使用 CommonMark 解析；
- 不使用 WebView；
- 原始 HTML 被忽略；
- 支持标题、段落、列表、引用、代码和链接文本；
- 支持浅色与深色模式；
- 支持中文和英文；
- 设置页支持退出登录和清理本地历史。

当前 UI 以功能验证为主，尚未进行完整品牌设计、动画、无障碍和多尺寸视觉优化。

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
| JVM 单元测试 | 47 个通过 |
| 模拟器测试 | 9 个通过 |
| `assembleLocalDebug` | 通过 |
| Web Docker 镜像构建 | 通过 |
| Nginx `nginx -t` | 通过 |
| Docker Compose 配置 | 通过 |
| GitHub Actions YAML | 通过 |

JVM 测试覆盖 SSE 分片、CRLF、多行数据、未知事件、非法 JSON、认证 API、Room Repository、共享认证 HTTP 客户端、Facilities API 序列化，以及空间、可用性、预约创建、列表排序、详情、取消、维修提交、字段校验、重复提交、维修列表排序和安全详情 404。设备测试覆盖登录页启动、Room v1 架构创建、My Bookings 展示、预约创建/取消确认，以及维修表单、提交确认、列表和只读状态详情。

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

- 状态：**未开始**
- 可复用：现有认证、OkHttp、图片加载、数据模型和后端 API。
- 需要开发：浏览筛选、发布 LOST/FOUND、多图上传、详情、认领申请、收到的认领和审核状态。

#### 7.10 Facilities 原生页面

- 状态：**Phase 1、Phase 2 和 Phase 3 已完成**
- 已完成：Services 入口、设施搜索、空间详情、可用性查询、预约创建、我的预约、预约详情、取消预约、维修请求、我的维修请求、维修详情和状态跟踪，并已连接真实 Spring Backend。
- 当前用户端 Facilities 范围无缺失项；Admin Facilities 与维修状态更新不属于普通用户 Android 范围。

#### 7.11 Mail 原生页面

- 状态：**未开始**
- 依赖：云端 Gmail OAuth 稳定配置和移动端授权边界确认。
- 需要开发：邮件列表、搜索、详情、分类和管理操作。

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
