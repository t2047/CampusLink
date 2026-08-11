# Lost & Found 图片上传与图片匹配 — 开发记录与计划

> 最后更新：2026-08-11
> 创建分支：`feature/lost-found-image-matching`（基线 b20ac26）
> 状态：**阶段 1（图片显示修复）与阶段 2（Agent 图片上传 + 图片匹配）已完成并通过真实运行验证**，阶段 3（Browse 以图搜物）未开始（本文件是后续开发的唯一事实来源，随每个阶段 PR 更新）

## 1. 背景与需求

在 Lost & Found 板块实现：

1. **Agent 图片上传**：用户可在 Agent 聊天面板上传图片，随消息一起发给 Agent。
2. **图片匹配**：以上传图片为查询条件，与反方向记录（报失 ↔ 拾获）做图片相似度匹配，返回可解释的 Top 5 候选。
3. **修复 Browse 页面图片显示 bug**：当前上传图片后，Browse/详情/Agent 候选卡片的图片都加载不出来。

用户确认过的需求细节（2026-08-11 逐条确认）：

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 图片匹配技术方案 | **先用现有确定性颜色直方图指纹跑通端到端**（离线、无外部依赖、Java/Python 字节一致），作为匹配 2.0 基线，接口预留可插拔升级 |
| 2 | Agent 图片用途 | **保存记录 + 匹配**：创建报失/拾获时图片入库并参与双向匹配；单独发图也可按图检索反方向 |
| 3 | Browse 图片显示修复方案 | **后端图片代理接口** `/api/lost-found/images/{id}`，前端统一用同源代理 URL |
| 4 | 匹配入口范围 | **Agent 面板 + Browse 页面"以图搜物"都加** |
| 5 | Agent 创建记录时图片落库 | **暂存 → 确认时关联**：图先经 Spring Boot 暂存，确认创建时内部 API 传 objectKey 列表，后端把暂存图正式关联到记录；未确认的暂存图 TTL 清理 |
| 6 | Browse 以图搜物交互形态 | **筛选区"上传图片搜索"按钮 + 现有报告卡片网格展示结果** |
| 7 | 迭代顺序 | **分阶段推进**：阶段1 修图片显示 → 阶段2 Agent 上传+匹配 → 阶段3 Browse 以图搜物 |

## 2. 现状调查结论

### 2.1 Browse 图片显示 bug 根因（已定位并修复 ✅ 阶段 1）

**根因**：后端 `MinioObjectStorageService.createPresignedGetUrl()`（`backend/.../storage/MinioObjectStorageService.java:67`）用 MinioClient 的 endpoint（`http://minio:9000`，Docker 内网服务名）生成预签名 URL。

- 列表/详情/候选响应全部直接返回该 URL：`LostFoundReportService.toResponse()`（`:400`）、`searchCandidates()`（`:182`）。
- 前端 `ReportCard.tsx:15`、`ReportDetailPage.tsx:100`、Agent 面板 `LostFoundAgentPanel.tsx:166` 直接把 `image.url` 塞进 `<img src>`。
- 浏览器解析不了 Docker 内网主机名 `minio`；HTTPS 下还会被 mixed-content 拦截。且预签名 URL 仅 15 分钟有效。
- 全链路无任何 URL 重写（nginx 只代理 `/api/`，没有图片代理或 MinIO 反代）。

**影响面**：Browse 列表卡片、详情页、Agent 候选卡片、编辑弹窗里的旧图预览，全部加载失败。

**修复（2026-08-11，阶段 1）**：新增后端图片代理端点 `GET /api/lost-found/images/{imageId}`（`LostFoundImageController`），统一由后端从 MinIO 读取字节返回；列表/详情/候选/管理端响应全部改为返回该同源代理 URL（`LostFoundImageResponse.of()` 统一构造）。安全上 `/api/lost-found/images/**` 走 permitAll（`<img>` 不携带 JWT，权衡见 §6）。`MinioObjectStorageService.createPresignedGetUrl` 主代码不再调用，接口保留。

### 2.2 Agent 图片上传现状（缺失）

- `InvokeRequest.message` 是纯文本（`models.py:31`，`min_length=1, max_length=4000`），没有图片字段。
- 前端 `LostFoundAgentPanel.tsx` 只发文本；`lostFoundAgent.ts` 的 invoke 请求只有 `message / conversationContext`。
- Spring Boot 代理 `LostFoundAgentGateway` / `AgentWebInvokeRequest` 同样只有文本。

### 2.3 图片匹配基础设施（已搭好但两端都没接通，实际是死代码）

| 组件 | 现状 | 位置 |
|---|---|---|
| 图片指纹计算（上传时） | **已完成**，上传即算 64 维颜色直方图 `visual_fingerprint` 存 DB，还有历史回填服务 | `VisualFingerprintExtractor.java`、`LostFoundReportService.create()`（`:103`）、`LostFoundImageBackfillService.java` |
| 指纹算法 Java/Python 一致 | **已完成**，字节级一致（WebP/解码失败走 SHA-256 fallback） | `embeddings.py` ↔ `VisualFingerprintExtractor.java` |
| 匹配打分 visual 分量 | **已完成但永远不触发**：`matching.py:157 _visual_similarity` 要求 query 有 `visual_fingerprint`、候选有 `visualFingerprints`，权重 0.10 | `matching.py:110` |
| 上下文白名单 | **已预留** `visual_fingerprint` | `rules.py:120 ALLOWED_CONTEXT_FIELDS` |
| 候选返回指纹 | **缺失**：`AgentCandidateResponse` 只返回 `imageUrls`，不含 `visualFingerprints` | `dto/agent/AgentCandidateResponse.java`、`LostFoundReportService.searchCandidates()`（`:171`） |
| 查询方指纹来源 | **缺失**：`InvokeRequest` 不能带图，query 永远没有 `visual_fingerprint` | `models.py:31`、`rules.py:_search_candidates` |
| 创建记录带图 | **缺失**：内部 API `report_lost/found` 只接受纯 JSON，`create()` 传入 `List.of()` 空图 | `LostFoundAgentInternalController.java:50/67` |

**结论**：图片指纹的"计算"和"打分"两层都已存在，缺的是"候选端返回指纹 + 查询端注入指纹 + 上传/存储/关联"三层。图片匹配是典型的"架子搭好、两端没接"。

## 3. 目标架构与数据流

```text
React Web（Agent 面板 / Browse 以图搜物）
   │  登录 JWT
   ▼
Spring Boot（chat-backend:8080）★ 唯一写入口，唯一碰 MinIO
   │ ① 图片暂存：POST /api/lost-found/agent/upload-image（multipart）
   │     → MinIO lost-found-staging/ + 算指纹 → 返回 {objectKey, visualFingerprint, url}
   │ ② invoke：POST /api/lost-found/agent/invoke（JSON 含 images/visual_fingerprint）
   │     下发 HS256 Delegation Token（30s）
   ▼
Lost & Found Agent（lost-found-agent:8083，REST）
   │ ③ 匹配：内部 API GET candidates（带 visualFingerprints）→ rank_candidates 综合打分
   │ ④ 确认创建：内部 API POST reports/lost|found（JSON 含 imageKeys）
   ▼
Spring Boot 内部 API（/api/internal/lost-found/**，AGENT_LOST_FOUND 角色）
   └─ 把暂存 objectKey 关联为报告图片（下载→建 LostFoundImage 行，指纹复用）

图片回显统一走：GET /api/lost-found/images/{imageId}（同源代理，无内网地址/mixed-content）
```

关键约束（沿用架构边界）：
- **Agent 不直接访问 MySQL / MinIO**——图片上传/存储/指纹入库全部由 Spring Boot 完成，Agent 只接收和传递 objectKey 与 fingerprint 字符串。
- 匹配双向复用现有 `rank_candidates`（文字 28% / 类别 28% / 颜色 14% / 地点 14% / 日期 6% / 视觉 10%，缺失分量自动归一化），只在查询和候选两端补上视觉指纹。
- 全部图片回显 URL 由后端统一签发，不再下发 MinIO 预签名地址。

## 4. 分阶段开发计划

### 阶段 1：修复 Browse / 详情 / 编辑 / Agent 候选卡片图片显示（独立可验证）

**目标**：上传后的图片在所有页面正常回显。

| # | 任务 | 涉及文件 |
|---|---|---|
| 1.1 | 后端新增图片代理端点 `GET /api/lost-found/images/{imageId}`：查 `LostFoundImage` → `storageService.download(objectKey)` → 返回字节流 + 原 contentType + 合理 `Cache-Control` | 新建 `LostFoundReportController` 或独立 `LostFoundImageController`；`LostFoundImageRepository` 已有 |
| 1.2 | `toResponse()`（详情/列表/编辑）与 `searchCandidates()`（Agent 候选）改为返回 `/api/lost-found/images/{imageId}`，不再签发 MinIO 预签名 URL | `LostFoundReportService.java:400 / :182`；`LostFoundImageResponse.java` |
| 1.3 | 安全配置：图片代理端点鉴权策略（见 §7 开放决策，默认建议 permitAll） | `SecurityConfig.java:72` |
| 1.4 | 前端：`ReportCard` / `ReportDetailPage` / `EditReportDialog` 无需改动（URL 由后端换掉即好）；`LostFoundAgentPanel` 候选卡不变 | 前端（仅验证） |
| 1.5 | 删除或保留 `createPresignedGetUrl`：响应不再使用，接口保留给潜在外部用途（不删，标注 dead-in-response） | `MinioObjectStorageService.java:67` |

**验收**：
- 上传 ≥1 张图创建记录后，Browse 列表卡片、详情页、编辑弹窗均能显示图片；Agent 候选卡片同理。
- 图片请求带正确 Content-Type，浏览器无 mixed-content 报错；无 `minio` 主机名请求。
- 图片代理端点后端集成测试通过。

**风险**：`<img>` 标签不携带 JWT——若走 blob-fetch 鉴权方案，前端改动面扩大到所有图片渲染点（见 §7）。

---

### 阶段 2：Agent 图片上传 + 图片匹配（端到端接通）

**目标**：Agent 面板可带图发消息；图片用于（a）创建记录时入库（b）参与双向匹配打分。

#### 2A. 前端（Agent 面板）

| # | 任务 | 涉及文件 |
|---|---|---|
| 2.1 | `LostFoundAgentPanel` 增加图片选择（≤5 张，JPEG/PNG/WebP，≤10MB，与 CreateReportPage 同规则）；选中即调暂存接口，本地 preview 用返回的代理 URL | `components/LostFoundAgentPanel.tsx` |
| 2.2 | `lostFoundAgent.ts` 新增 `uploadAgentImage()`（multipart）与 `invokeLostFoundAgent` 请求体增加 `images` | `api/lostFoundAgent.ts`、`api/lostFound.ts` |
| 2.3 | 消息气泡展示已上传图片（点击可移除）；发送时携带 images | `components/LostFoundAgentPanel.tsx` |

#### 2B. Spring Boot（暂存 + 代理 + 内部 API）

| # | 任务 | 涉及文件 |
|---|---|---|
| 2.4 | 新增 `POST /api/lost-found/agent/upload-image`（登录 JWT + multipart）：校验类型/大小（复用 `LostFoundReportService` 的图片校验）→ 上传 MinIO `lost-found-staging/` 前缀 → 算指纹 → 返回 `{objectKey, visualFingerprint, url}` | 新建 `LostFoundAgentWebController` 端点 + `LostFoundImageStagingService`（新建） |
| 2.5 | 暂存清理：TTL 任务删除超过 N 小时的 `lost-found-staging/` 对象（未确认即放弃的孤儿图） | 新建定时任务，或复用现有 scheduler 模式 |
| 2.6 | `AgentWebInvokeRequest` 增加 `images: List<AgentImage>`（objectKey/fingerprint/url），`toAgentPayload` 透传给 Agent | `dto/agent/AgentWebInvokeRequest.java` |
| 2.7 | 内部 API：`AgentCreateLostReportRequest` / `AgentCreateFoundReportRequest` 增加 `imageKeys`；`reportService` 新增"按已暂存 objectKey 关联图片"的创建路径（下载暂存字节 → 复用指纹 → 建 `LostFoundImage` 行） | `dto/agent/AgentCreate*Request.java`、`LostFoundAgentInternalController.java`、`LostFoundReportService.create()` |
| 2.8 | `AgentCandidateResponse` 增加 `visualFingerprints: List<String>`；`searchCandidates()` 组装（从 DB 读，与 imageUrls 同序） | `dto/agent/AgentCandidateResponse.java`、`LostFoundReportService.searchCandidates()` |

#### 2C. Agent（Python）

| # | 任务 | 涉及文件 |
|---|---|---|
| 2.9 | `InvokeRequest` 增加可选 `images: List[AgentImage]`（object_key / visual_fingerprint / url） | `models.py:31` |
| 2.10 | `RuleEngine.handle()` 把 `payload.images` 并入 context（fingerprint 归入 `visual_fingerprint` 白名单；objectKey 列表单独保留），多轮共享 | `rules.py`（`ALLOWED_CONTEXT_FIELDS` 增加 `images`） |
| 2.11 | `matching.py` 查询端支持单/多图：query 注入 `visual_fingerprint`（单图）或取多图 best | `matching.py` |
| 2.12 | `ReportLostInput` / `ReportFoundInput` 增加可选 `images`（objectKey 列表）；`CampusApiClient.report_lost/found` 把 `imageKeys` 写入内部 API body；确认创建后把图片指纹并入自动匹配 query | `tools.py:26/44/124/144` |
| 2.13 | 契约 JSON Schema 同步更新 | `agent/schemas/lost-found-agent.json` |

**验收**：
- 面板发图报失：确认前**零写入**；确认后记录含图片，详情可回显；自动匹配结果含"图片特征相似"理由。
- 面板单独发图（无文字或仅"帮我找这个"）：走 `search_found_items`/`search_lost_items`，视觉指纹参与打分，返回 Top 5。
- 后端集成测试 + Agent 契约测试通过；`matching_eval.py` 增加带 visual 的样例，视觉分量确实影响排序。
- 暂存孤儿图能按 TTL 清理。

**风险**：颜色直方图区分度有限，视觉分量权重仅 0.10，实际召回提升可能不明显——本阶段目标是"链路跑通 + 打分有据"，召回质量留到匹配 2.1。

---

### 阶段 3：Browse 页面"以图搜物"

**目标**：Browse 筛选区可上传图片搜索，结果以报告卡片网格展示。

| # | 任务 | 涉及文件 |
|---|---|---|
| 3.1 | Browse 筛选区增加"上传图片搜索"按钮（可叠加现有文字/类别等筛选） | `pages/ReportsPage.tsx` |
| 3.2 | 复用阶段 2 的暂存接口上传图片拿 fingerprint；新增后端搜索端点（算指纹 → 走 Agent `_search` 同款链路 或 后端直查候选 + 复用打分） | 后端新端点；前端 `api/lostFound.ts` |
| 3.3 | 结果以现有 `ReportCard` 网格渲染；清空/更换图片、状态提示（无匹配/匹配中） | `pages/ReportsPage.tsx` |
| 3.4 | 匹配入口与 Agent 共用同一套打分权重与阈值，保证结果一致 | 复用 `matching.py` / 后端对应逻辑 |

**验收**：
- Browse 上传图片可搜到视觉相似的 FOUND/LOST 记录；与 Agent 面板同图搜索结果一致。
- 图片搜索与文字筛选可组合使用；图片可移除、可更换。

## 5. 契约变更清单

| 契约 | 变更 |
|---|---|
| Agent `InvokeRequest` | 新增可选 `images: [{object_key, visual_fingerprint, url}]` ✅ 阶段 2 |
| Agent `InvokeResponse` | 不变（`match_results[].image_urls` 现在指向代理 URL） |
| Agent JSON Schema | 同步 `images` 字段（版本 1.5.0 → 1.6.0）✅ 阶段 2 |
| Web `AgentWebInvokeRequest` | 新增 `images`；`POST /agent/upload-image` 暂存接口 ✅ 阶段 2 |
| 内部 `AgentCreateLostReportRequest` / `AgentCreateFoundReportRequest` | 新增 `imageKeys: List<String>` ✅ 阶段 2 |
| 内部 `AgentCandidateResponse` | 新增 `visualFingerprints: List<String>`（与 imageUrls 同序）✅ 阶段 2 |
| 内部候选/详情 `imageUrls` | 由 MinIO 预签名 URL 改为 `/api/lost-found/images/{id}`（阶段 1） |
| 暂存图预览 | 新增 `GET /api/lost-found/images/staging/{objectName}`（随机 UUID 文件名，不可枚举）✅ 阶段 2 |
| 内部 API `reports/lost|found` 创建 | 支持关联已暂存 objectKey（`createFromStaged` 新增服务路径，原纯 JSON 路径保留）✅ 阶段 2 |
| 前端 `AgentInvokeRequest` | 新增 `images` ✅ 阶段 2 |

## 6. 安全与边界

- **Agent 不碰 MinIO/MySQL**：图片一律先经 Spring Boot 暂存，Agent 只拿字符串；创建时由 Spring Boot 关联落库。
- **内部 API 权限不变**：`/api/internal/lost-found/**` 仍要求 `AGENT_LOST_FOUND` 角色 + 一次性 Delegation Token + `intended_action` 校验。
- **暂存图片**：仅限登录用户；随附 TTL 清理，防孤儿对象累积；校验沿用报告图片规则（类型/数量/10MB）。
- **图片代理端点鉴权（开放决策，阶段 1 开工前确认）**：
  - 方案 A（推荐）：`/api/lost-found/images/**` 加入 `permitAll`。理由：`<img>` 不携带 JWT；物品照片敏感度低；imageId 为自增数字可枚举——如介意，改为随机 objectKey 查询而非自增 id。
  - 方案 B：保持 `authenticated()`，前端用 axios blob 拉图（JWT 随 header）生成 objectURL——鉴权最严格，但 `ReportCard`/`ReportDetailPage`/`EditReportDialog`/Agent 候选卡全部要改用一个共享图片组件/hook，工作量明显更大。
- **回显 URL 统一代理**：彻底消除内网 `minio` 主机名与 mixed-content，预签名 URL 不再下发。

## 7. 已知风险与技术债

- 颜色直方图（64 桶）区分度有限：同色系不同物品易混淆，视觉分量权重 0.10 时对总分影响小。→ 匹配 2.1 可插拔换更强 embedding（CLIP 等），`matching.py` 已按字符串指纹设计，替换点集中。
- `AgentCandidateResponse` 增加 `visualFingerprints` 后候选体积增大（每候选最多 5 × 88 字符），100 候选量级可接受，需观察。
- 暂存图片 TTL 目前计划单实例定时任务，横向扩容前与现有 Nonce/SSE 存储同属内存/单机债（见 TECHNICAL_ROADMAP §8）。
- 图片代理接口（`/api/lost-found/images/**`，含 `/staging/{objectName}`）已按方案 A 放开鉴权并记录在此。已关联图片用自增 id 可枚举（权衡已记录）；**暂存图预览用随机 UUID objectName，不可枚举**。对象为物品照片、敏感度低，维持现状。
- 阶段 2 内部 API 创建路径保证：确认创建后若图片关联失败（如暂存对象已被 TTL 清理），记录创建整体回滚，不产生"有记录无图"或"有图无记录"的半态（真实运行验证：`createFromStaged` 在检索暂存对象失败时抛出并使事务回滚）。
- **真实运行发现**：`lost_found_images.object_key` 唯一约束使同一暂存对象只能关联一个报告；重复关联同一 objectKey 会触发唯一键冲突（`createFromStaged` 回滚）。面板在确认创建成功后清空暂存，真实 UI 流程每个上传对象只用一次，故该行为是预期的防"双挂"保护而非缺陷。单实例 TTL 定时任务与 Nonce/SSE 存储同属单机债（见 TECHNICAL_ROADMAP §8）。

## 8. 开发进度跟踪

| 阶段 | 任务 | 状态 | 负责人 | 备注 |
|---|---|---:|---|---|
| 1 | 图片显示修复（后端代理接口） | ✅ 已完成 2026-08-11 | Lost & Found 后端 | 真实运行验证通过 |
| 2A | Agent 面板图片上传（前端） | ✅ 已完成 2026-08-11 | Web | 含暂存预览/移除/多轮共享 |
| 2B | Spring Boot 暂存/代理/内部 API | ✅ 已完成 2026-08-11 | Lost & Found 后端 | 含暂存 TTL 清理 |
| 2C | Agent 匹配端到端（Python） | ✅ 已完成 2026-08-11 | Agent 开发 | 候选端返回指纹 + 查询端注入指纹（含多图 best） |
| 3 | Browse 以图搜物 | 未开始 | Web + 后端 | 依赖阶段 2 |

阶段 1 改动文件：
- 新增 `backend/.../controller/LostFoundImageController.java`（图片代理端点）
- 新增 `backend/.../controller/LostFoundImageControllerTest.java`
- `backend/.../dto/LostFoundImageResponse.java`：新增 `of()` 统一构造代理 URL
- `backend/.../service/LostFoundReportService.java`：`toResponse()` 与 `searchCandidates()` 改用代理 URL
- `backend/.../service/LostFoundAdminService.java`：`toDetail()` 改用代理 URL，移除不再使用的 `storageService` 注入
- `backend/.../config/SecurityConfig.java`：`/api/lost-found/images/**` 加入 permitAll
- 测试更新：`LostFoundReportServiceTest`、`LostFoundSearchIntegrationTest`

阶段 2 改动文件：
- 后端：新增 `LostFoundImageStagingService`（暂存上传/读取/列出，MinIO `lost-found-staging/` 前缀）、`LostFoundImageStagingCleanupJob`（TTL 清理）、`LostFoundImageRules`（从 `LostFoundReportService` 抽出的共享图片校验）；`LostFoundAgentWebController.uploadImage`（`POST /agent/upload-image`）；`LostFoundImageController.downloadStaged`（暂存预览 `GET /images/staging/{objectName}`）；`AgentWebInvokeRequest.images`；`AgentCreateLostReportRequest` / `AgentCreateFoundReportRequest.imageKeys`；`LostFoundAgentInternalController` 走 `createFromStaged`；`AgentCandidateResponse.visualFingerprints` + `searchCandidates()` 组装；`LostFoundImageRepository.existsByObjectKey`；`CampusAgentApplication` 加 `@EnableScheduling`
- Agent：`models.py` 新增 `AgentImage` + `InvokeRequest.images`；`rules.py` 把 `payload.images` 并入 context（`images`/`visual_fingerprints` 白名单 + 多轮共享）并放行纯图搜索；`matching.py` 查询端支持多图指纹取 best；`tools.py` `ReportLostInput`/`ReportFoundInput` 增加 `images`/`visual_fingerprints`，`report_lost/found` 把 `imageKeys` 写入内部 API body；`schemas/lost-found-agent.json` 升 1.6.0 并同步 `images` 输入
- 前端：`lostFoundAgent.ts` 新增 `StagedAgentImage` + `uploadAgentImage()`；`LostFoundAgentPanel.tsx` 图片选择/暂存上传/预览/移除/消息气泡展示/发送与确认带 `images`

更新规则：每个阶段 PR 必须更新"开发进度跟踪"、契约变更与风险清单；API 变化同步 JSON Schema 与自动化契约测试。

## 9. 验证与测试计划

- 后端：`LostFoundReportService` / 图片代理端点集成测试；内部 API `imageKeys` 关联测试（含失败回滚）；指纹回填测试沿用。
- Agent：契约测试 + `matching_eval.py` 增加视觉样例；`_probe_lf.py` 直连 live 链路回归。
- 前端：`LostFoundAgentPanel.test.tsx`、`ReportsPage.test.tsx` 补充带图用例。
- 手工验收路径：
  1. Browse 上传图 → 列表/详情/编辑回显正常（阶段 1）。
  2. Agent 面板发图报失 → 确认 → 记录带图 + 自动匹配含视觉理由（阶段 2）。
  3. Agent 面板单独发图搜物 → 返回 Top 5（阶段 2）。
  4. Browse 上传图搜物 → 卡片结果与 Agent 一致（阶段 3）。
- 环境：`docker compose --profile agent up -d --build`（注意记忆中的过期镜像/CRLF/端口占用坑）。

### 阶段 1 真实运行验证结果（2026-08-11）

对 live 栈（chat-backend 8080 / MySQL / MinIO）执行：

| 验证项 | 结果 |
|---|---|
| 登录 → 创建带图 FOUND 报告 | 报告 id=22，图片 URL = `/api/lost-found/images/2`（**不再是 minio:9000 预签名**）✓ |
| `GET /api/lost-found/images/2`（无鉴权） | 200，`Cache-Control: max-age=86400, public`，字节与原图 `cmp` 完全一致 ✓ |
| Browse 列表 `GET /api/lost-found/reports` | 图片 URL 为 `/api/lost-found/images/2` ✓ |
| 不存在的图片 `GET /images/99999` | 404 ✓ |
| 删除报告后原图片 | 404（MinIO 对象与 DB 行随 orphanRemoval/清理删除）✓ |
| 后端完整测试套件 | `Tests run: 237, Failures: 0, Errors: 0` ✓（含新增代理端点测试） |

待办：前端无代码改动（URL 透传），未启动 web 容器做浏览器目视确认；部署形态下 nginx 同源代理 `/api/`，相对路径 `/api/lost-found/images/{id}` 可用。若配置了跨源 `VITE_API_BASE`，图片 URL 需由前端统一加前缀（当前默认同源不受影响）。

### 阶段 2 真实运行验证结果（2026-08-11）

重建 `chat-backend`（Java）与 `lost-found-agent`（Python）镜像后，对 live 栈执行：

| 验证项 | 结果 |
|---|---|
| 登录 → `POST /api/lost-found/agent/upload-image`（multipart，JWT） | 200，返回 `{objectKey: lost-found-staging/<uuid>.png, visualFingerprint: VF1:…, url: /api/lost-found/images/staging/<uuid>.png, …}` ✓ |
| `GET /api/lost-found/images/staging/<uuid>.png`（无鉴权 `<img>`） | 200，字节与原图一致 ✓ |
| `POST /api/lost-found/agent/invoke`（消息 + images 含 objectKey/fingerprint/url） | `needs_confirmation`；`shared_context.images` 与 `visual_fingerprints` 正确下发 ✓ |
| 确认创建报失 | `report_id=24` 创建成功；详情图片 URL = `/api/lost-found/images/4`（代理，非 minio）✓；`GET /images/4` → 200 ✓ |
| 双向匹配（FOUND 带图自动匹配 LOST） | FOUND 报失记录创建后自动搜索命中 LOST 报告 #27，`match_reason` 含 **图片特征相似** ✓（同一指纹的 #24/#25 亦被召回） |
| 同一 objectKey 二次关联 | 唯一约束冲突 → 整体回滚（预期防双挂，见 §7） |
| 后端完整测试套件 | `Tests run: 245, Failures: 0, Errors: 0` ✓（含暂存/关联/清理用例） |
| Agent 完整测试套件 | `98 passed` ✓（含新增图片流/多图匹配/契约用例） |
| 前端 | `tsc + vite build` ✓，`vitest 169 passed` ✓ |

待办：未启动 web 容器做浏览器目视确认（Agent 面板图片选择/预览/移除）；Browser 目视与真实浏览器交互留给阶段 3 收尾时一并验证。

### 阶段 2 修复：纯图搜索占位语把视觉匹配分数拉低（2026-08-11）

**症状**：面板仅发图搜物（占位语 `帮我找这个`）时，即使上传图片与候选报告图片完全一致，也返回"暂时没有达到最低匹配分数的候选物品"（`no_match`）。

**根因**（纯 Agent 侧，Python）：`rules.extract_fields` 把占位语 `帮我找这个` 抽取为 `keyword="这个"`（指示代词，无检索信息）。`score_candidate` 将其计入 text 分量（权重 0.28），与候选文本近零相似度；而打分是"活跃分量加权平均归一化"（`sum(w·v)/Σw`），于是纯视觉匹配时
`score=(0.28·0 + 0.10·1.0)/0.38 ≈ 0.263 < minimum_score=0.35` → 完全一致的图片也被过滤。报告创建流程（query 含真实 item_name/category 等）不受影响，故阶段 2 真实运行验证的"双向匹配含图片特征相似"通过。

**修复**：`rules.py` 新增 `KEYWORD_STOPWORDS` + `is_stopword_keyword()`；`extract_fields` 规则抽取与 LLM `interpreted_fields` 合并两条路径都跳过停用词 keyword。纯视觉搜索回归到只有 visual 分量 → `score=1.0`。

**验证**：新增回归测试 `test_placeholder_image_search_matches_identical_image`（占位语 + 完全一致指纹 → `match_found`、score=1.0、含"图片特征相似"）；Agent 完整套件 `99 passed` ✓。

**真实运行验证（2026-08-11，重建 lost-found-agent/lost-found-mcp 镜像后）**：

| 验证项 | 结果 |
|---|---|
| `docker compose --profile agent up -d --build lost-found-agent lost-found-mcp` | 两镜像 Created 更新（未复用缓存），容器 healthy；`docker exec` 确认运行代码含 `KEYWORD_STOPWORDS` ✓ |
| 登录 → 创建带图 FOUND 报告 #30（纯红 64×64 PNG） | 201，图片 URL `/api/lost-found/images/10` ✓ |
| 暂存同一张图 | `objectKey=lost-found-staging/<uuid>.png`，指纹 `VF1:…`（Java 计算）✓ |
| 纯图搜索（`帮我找这个` + 同一张图） | **`match_found`**，命中 #30，score=1.0，理由含**图片特征相似**；`shared_context` 无 `keyword` ✓ |
| 对照组：纯蓝图搜索 | **`no_match`**（正确不命中红色报告 #30，视觉分量确实判别）✓ |
| 清理 | 删除验证报告 #30 → 204 → 复查 404 ✓ |
