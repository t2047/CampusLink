# BeforeLanding 在 main 分支的全部提交记录与逐行解释

> 生成时间：2026-08-20
>
> 范围：`git log main --author="BeforeLanding"`，共 **19 个提交**（2026-08-10 ~ 2026-08-17）。
>
> 工作主线：**失物招领（Lost & Found）** 与 **用户中心（个人中心 / 修改密码）**。
>
> 格式：每个提交先说明「主要作用」，再按文件分组做「逐行解释」（覆盖全部改动文件）。正文按时间从旧到新排列；merge 提交放在其承载的功能提交之后。

---

## 提交总览

| # | 时间 | 提交 | 主题 |
|---|------|------|------|
| 1 | 08-10 | `f810823` | feat: expand lost found backend admin workflows |
| 2 | 08-10 | `fa3cc44` | feat: improve lost found admin frontend |
| 3 | 08-10 | `94511f8` | test: cover lost found admin claim security |
| 4 | 08-10 | `4d324ec` | feat: add lost found agent evaluation and matching improvements |
| 5 | 08-11 | `b20ac26` | 实现主动report时产品类别信息智能填写 |
| 6 | 08-11 | `25437b2` | 修复浏览页图片无法显示 |
| 7 | 08-11 | `ccd1c38` | 完成Agent 图片上传 + 图片匹配，修复纯图搜索无法正常匹配的bug |
| 8 | 08-11 | `c01bf95` | 完成Browse以图搜物 |
| 9 | 08-11 | `328e76c` | 修复CI检查未通过：ruff E501行过长与eslint未使用变量 |
| 10 | 08-12 | `57f3e7e` | 安全测试 |
| 11 | 08-16 | `8d8b51f` | 实现个人中心功能 |
| 12 | 08-16 | `3cff44d` | Merge branch 'feature/lost-found-user-center'（承载 #11） |
| 13 | 08-16 | `1505050` | 修复颜色跨语言/同义词不一致 |
| 14 | 08-16 | `e6e2d24` | Merge branch 'feature/lost-found-user-center'（承载 #13） |
| 15 | 08-17 | `6d040eb` | 实现修改密码功能 |
| 16 | 08-17 | `a01be05` | feat(user-center): 修改密码安全优化——旧 JWT 失效、BCrypt 72 字节校验、统一密码规则 |
| 17 | 08-17 | `0e6ad56` | Merge branch 'feature/lost-found-user-center'（承载 #15/#16） |
| 18 | 08-17 | `619fd6f` | fix(lost-found): 发送成功后清空暂存图片，避免残留在输入框 |
| 19 | 08-17 | `e5b7547` | Merge branch 'feature/lost-found-user-center'（承载 #18） |

---

## 1. `f810823` feat: expand lost found backend admin workflows

- 时间：2026-08-10
- **主要作用**：把失物招领后台从"只读概览"扩展为完整管理闭环。管理员现在可以下架/恢复/删除任意报告并记录审计原因，认领审核从"物主自己审"收归为"管理员统一审"；同时引入站内通知系统、报告 owner 的编辑/关闭/删除能力，并为图片加入与 Agent 端字节级对齐的视觉指纹（含历史数据回填与防解压炸弹的尺寸校验）。背景是配合前端管理面板上线，补齐后端支撑。
- 改动规模：56 个文件，+2936/-100 行

### 逐行解释

- `.gitignore`：追加 `.claude/`，忽略 Claude Code 本地目录，避免误提交。
- `backend/Dockerfile`：在非 root 用户段预建 `/app/keys` 目录并 `chown campus:campus`。原因是 RSA 委托密钥持久化到命名卷时，首次挂载会继承镜像目录属主；若属主是 root，非 root 的 campus 用户写入会 403。预建并改属主后，卷挂载时保持 campus 属主。
- `backend/pom.xml`：新增 `spring-boot-starter-actuator`，为 Docker HEALTHCHECK 提供 `/actuator/health` 端点（注释说明 management.* 与 SecurityConfig 放行已配套配置）。
- `backend/src/main/resources/application.properties`：新增两个回填开关——`app.lost-found.backfill-fingerprints`（默认 false）与 `backfill-page-size`（默认 100），对应启动时对旧图片视觉指纹的幂等回填。
- `backend/src/main/java/com/app/campusagent/lostfound/domain/LostFoundAuditAction.java`（新增）：审计动作枚举，覆盖报告创建/更新/关闭/删除/下架/恢复/管理员删除/被认领/认领批准/认领拒绝共 11 种。
- `backend/src/main/java/com/app/campusagent/lostfound/domain/LostFoundAuditLog.java`（新增）：审计日志实体。关键设计：`reportId` 是普通 Long 列（无外键）、`itemName`/`actorEmail` 是写入时快照，因此报告被硬删除后审计行仍保留可追溯；构造器在 actor 非空时快照其 email；`@PrePersist` 写 `createdAt`。表建 4 个索引（report_id/actor_id/action/created_at）。
- `backend/src/main/java/com/app/campusagent/lostfound/domain/LostFoundClaim.java`：新增 `reviewedAt` 列（历史已审核数据可空）；`approve()`/`reject()` 现在写入 `Instant.now()` 作为审核时间，供后台展示审核时间。
- `backend/src/main/java/com/app/campusagent/lostfound/domain/LostFoundImage.java`：新增 `visualFingerprint` 列（长 512）；原构造器扩为可传指纹，另保留无指纹重载；新增 `assignVisualFingerprint()` 供回填使用（注释标明幂等、仅覆盖当前为空情况）。
- `backend/src/main/java/com/app/campusagent/lostfound/domain/LostFoundNotification.java`（新增）：站内通知实体，关联 recipient（必填）/report/claim，含 title（200）、message（1000）、`readAt`，`markRead()` 仅首次标记；`@PrePersist` 写 createdAt。表建 recipient+read_at、recipient+created_at、created_at 三组索引。
- `backend/src/main/java/com/app/campusagent/lostfound/domain/LostFoundReport.java`：新增 `adminHidden` 布尔列（下架标记，不进公开搜索/候选匹配/非 owner 非 admin 详情）；新增 `markClosed()`、`hide()`、`show()`、`updateDetails()`（编辑字段批量更新）、`imageObjectKeys()`（删除前收集 MinIO 键供清理）、`replaceImages()`（整体替换图片集合，靠 orphanRemoval 删旧图、按序 addImage 挂新图）；`images` 集合加 `@BatchSize(size=50)` 避免 N+1。
- `backend/src/main/java/com/app/campusagent/lostfound/domain/NotificationType.java`（新增）：四种通知类型 CLAIM_SUBMITTED/CLAIM_APPROVED/CLAIM_REJECTED/REPORT_CLAIMED。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/CreateLostFoundReportRequest.java`：`itemName` 的 `@Size(min=3)` 改为 `min=2`，注释说明中文物品名常为 2 字符（钥匙/钱包），与 Agent 端提取口径一致。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/agent/AgentCreateLostReportRequest.java`：同上，Agent 创建报告的 itemName 也放宽到 min=2。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/UpdateLostFoundReportRequest.java`（新增）：owner 编辑报告的请求体 record，字段与创建一致但无 reportType，`eventDate` 带 `@PastOrPresent`。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/LostFoundNotificationResponse.java`（新增）：通知响应 record，含 type/reportId/claimId/title/message/read/createdAt/readAt。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/UnreadNotificationCountResponse.java`（新增）：`record(long unread)` 未读数响应。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminAuditLogResponse.java`（新增）：审计日志列表项 record。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminClaimDecisionRequest.java`（新增）：审核认领的备注，仅 `@Size(max=500)`；注释说明批准时可选、拒绝时由 Service 层校验非空。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminClaimDetailResponse.java`（新增）：认领详情，嵌套 claimant 用户详情、report 详情、review 信息。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminClaimReportDetail.java`（新增）：认领详情里的报告完整信息 + owner 摘要 + 图片列表。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminClaimReportSummary.java`（新增）：认领列表的报告摘要（不含 description/图片）。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminClaimReviewInfo.java`（新增）：`record(boolean reviewed, String decisionNote, Instant reviewedAt)`。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminClaimSummaryResponse.java`（新增）：认领列表项，含证明摘要、claimant/report 摘要。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminClaimUserDetail.java`（新增）：详情用用户信息，含 Role。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminClaimUserSummary.java`（新增）：列表用 `record(Long id, String email)`。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminLostFoundOverviewResponse.java`：新增 `hiddenReports` 字段（下架数）。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminLostFoundReportResponse.java`：新增 `adminHidden` 布尔字段。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/admin/AdminReportActionRequest.java`（新增）：管理员写操作（下架/恢复/删除）必填 `reason`（`@NotBlank` + `@Size(max=500)`），保证所有管理员写操作有据可查。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/agent/AgentCandidateResponse.java`：新增 `List<String> visualFingerprints`，供 Agent 做以图搜物时比对指纹。
- `backend/src/main/java/com/app/campusagent/lostfound/repository/LostFoundAuditLogRepository.java`（新增）：继承 `JpaSpecificationExecutor` 支持动态查询，并覆写 `findAll` 加 `@EntityGraph(attributePaths="actor")` 预加载 actor，避免审计分页 N+1。
- `backend/src/main/java/com/app/campusagent/lostfound/repository/LostFoundClaimRepository.java`：加 `JpaSpecificationExecutor`（供认领筛选），新增 `deleteByReportId`（报告删除时级联清理认领）。
- `backend/src/main/java/com/app/campusagent/lostfound/repository/LostFoundImageRepository.java`（新增）：`findByVisualFingerprintIsNull(Pageable)`，供回填分页抓取空指纹图片。
- `backend/src/main/java/com/app/campusagent/lostfound/repository/LostFoundNotificationRepository.java`（新增）：按 recipient 分页查询（全量/未读）、未读数统计、`findByIdAndRecipientId`（保证只能动自己的通知）、`deleteByReportId`（报告删除级联清理）。
- `backend/src/main/java/com/app/campusagent/lostfound/repository/LostFoundReportRepository.java`：新增 `countByAdminHiddenTrue()`，供概览统计下架数。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundAuditService.java`（新增）：`record(...)` 审计写入方法，`@Transactional` 保证与业务写操作同事务、失败回滚时审计也不落库。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundClaimService.java`：核心变化——`approve`/`reject` 从"物主可审"改为一律抛 403 `CLAIM_REVIEW_ADMIN_ONLY`（注释注明实际审核逻辑已移到 `LostFoundAdminService`），并删掉原 owner 审核逻辑（requireClaim/assertCanReview/assertSubmitted/trimToNull）。`submit` 新增两点：报告 `isAdminHidden()` 时抛 `REPORT_HIDDEN` 拒绝认领；认领成功后调 `notificationService.claimSubmitted(claim)` 通知报告发布者。构造器注入通知服务。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundNotificationService.java`（新增）：三个写方法——`claimSubmitted`（通知报告发布者）、`claimApproved`（通知认领者"已批准" + 通知发布者"报告被标记已认领"两条）、`claimRejected`（通知认领者）；读方法 `mine`（按 unreadOnly 分支查询）、`unreadCount`、`markRead`（`findByIdAndRecipientId` 保证只能标记自己的通知，找不到返回 NOT_FOUND）。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundReportService.java`（重点，+252 行）：
  - `create`：上传每张图前先 `visualFingerprint(image)` 计算指纹写入 `LostFoundImage`；`saveAndFlush` 后审计 `REPORT_CREATED`（detail 记录 images 数量）；异常仍走回滚清理已上传 MinIO 对象。
  - `searchCandidates`：Agent 候选响应新增 `visualFingerprints(report)`（按 sortOrder 排序、过滤 null）。
  - `getById`：对 `adminHidden` 报告，仅 owner 或 ADMIN/SUPER_ADMIN 可见，其他人一律返回 404（避免暴露下架内容、防枚举）。
  - 新增 `update`：仅 owner 可改，仅 OPEN 报告可改；若带了新图片则 `replaceImages`（校验类型/数量，上传新图、挂载后删旧 MinIO 对象，失败回滚）；更新文本字段后审计 `REPORT_UPDATED`。
  - 新增 `close`：仅 owner + OPEN，置 CLOSED 并审计 `REPORT_CLOSED`。
  - 新增 `delete`：仅 owner + OPEN，走 `deleteReportAndCleanup` 硬删除（先收集 objectKey，级联删通知 `deleteByReportId`、删认领 `deleteByReportId`，再删报告并 flush，最后逐删 MinIO 对象），审计 `REPORT_DELETED`。
  - 新增 `deleteAsAdmin`：供管理员删除，不校验 owner/状态（由管理接口 ADMIN 权限兜底），复用同一级联清理；审计由调用方在清理后写入（reportId 无外键，报告删除后审计仍存）。
  - 公开 `search` 的 Specification 首条加入 `builder.isFalse(root.get("adminHidden"))`，下架报告彻底退出公开搜索。
  - `validateImageDimensions`：用 `ImageIO.createImageInputStream` 只读头部宽高不整图解码，任一边 >8192 抛 `IMAGE_DIMENSION_TOO_LARGE`（防解压炸弹）；无法识别的格式直接 return 跳过（WebP 在指纹提取走 SHA-256 回退不触发解码）。
  - `visualFingerprint`：读字节交给 `VisualFingerprintExtractor.extract`，IO 异常映射为 `IMAGE_READ_FAILED`。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundAdminService.java`（重点，+394 行）：
  - `overview`：新增 `reportRepository.countByAdminHiddenTrue()` 填 hiddenReports。
  - `search`：Specification 新增 `adminHidden` 等值过滤。
  - 新增 `delist`：`adminHidden` 已为 true 抛 `REPORT_ALREADY_HIDDEN`，否则 `report.hide()` + 审计 `REPORT_DELISTED`（detail 记 `adminHidden=false→true`）。
  - 新增 `restore`：未隐藏抛 `REPORT_NOT_HIDDEN`，否则 `show()` + 审计 `REPORT_RESTORED`（detail 记 `adminHidden=true→false`）。
  - 新增 `deleteReport`：快照 itemName/status/imageCount 后调 `reportService.deleteAsAdmin`，随后审计 `REPORT_DELETED_BY_ADMIN`（detail 记 status 与 images 数量）。
  - 新增 `auditLogs`：按 reportId/action/actorEmail(小写精确)/keyword(itemName 或 actorEmail LIKE) 动态查询，`EntityGraph` 预载 actor 后映射响应。
  - 新增 `searchClaims`：复杂 Specification——按需 join `report`/`claimant`/`report.createdBy` 并复用避免重复 join；支持 status、reportId、adminHidden、claimantEmail、reportOwnerEmail、跨 proofDescription/decisionNote/itemName/description/location/双邮箱的 keyword LIKE。
  - 新增 `getClaimDetail`/`toDetail`：图片按 sortOrder 排序并生成 presigned URL；`reviewed = status != SUBMITTED`；`reviewedAtOrFallback` 优先 reviewed_at，历史已审核数据回退 updatedAt。
  - 新增 `approveClaim`：依次校验 SUBMITTED、FOUND 类型、report OPEN；批准当前 claim 后把同 report 其余 SUBMITTED 自动 reject（固定文案 "Another claim was approved by admin"）；`report.markClaimed()`；审计 `CLAIM_APPROVED_BY_ADMIN`（detail 记两端状态变化）；通知已批准者 + 逐个通知被自动拒绝者。
  - 新增 `rejectClaim`：拒绝时备注必填，空则 422 `DECISION_NOTE_REQUIRED`；report 状态不变；审计 `CLAIM_REJECTED_BY_ADMIN` + 通知认领者。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundImageBackfillRunner.java`（新增）：`CommandLineRunner`，默认 `enabled=false` 跳过；置 `backfill-fingerprints=true` 时启动执行 `backfill(pageSize)` 并打 INFO 汇总日志。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundImageBackfillService.java`（新增）：`backfill(int)` 分页拉 `findByVisualFingerprintIsNull`，逐张 `download` → `extract` → `assignVisualFingerprint` → save；单张失败仅计数 failed 并继续（`@Transactional` 内 try/catch 吞 Runtime 异常），`BackfillResult(processed, updated, failed)` 汇总；只处理空指纹图片故重跑安全（幂等）。
- `backend/src/main/java/com/app/campusagent/lostfound/storage/ObjectStorageService.java`：接口新增 `byte[] download(String objectKey)`。
- `backend/src/main/java/com/app/campusagent/lostfound/storage/MinioObjectStorageService.java`：实现 `download`，用 `minioClient.getObject` 流式读全部字节，失败映射 `OBJECT_STORAGE_UNAVAILABLE`。
- `backend/src/main/java/com/app/campusagent/lostfound/visual/VisualFingerprintExtractor.java`（新增）：与 Agent `embeddings.py` 字节级共享的确定性颜色直方图指纹。算法：8x8 网格整数缩放采样（`sx=(dx*width)/8`），每像素 RGB 量化进 64 桶（`bin=(r>>6&3)<<4|(g>>6&3)<<2|(b>>6&3)`），double 精度 L1 归一化，64 个 float32 按小端序列化 + `VF1:` 前缀 + Base64。WebP（JDK ImageIO 不可解码）或不可解码字节回退为前 1KB 的 SHA-256 直方图，保证两端对齐。
- `backend/src/main/java/com/app/campusagent/lostfound/controller/LostFoundAdminController.java`：新增 7 个端点——`POST /reports/{id}/delist|restore|delete`（携带 `@Valid AdminReportActionRequest` 的 reason，delete 返回 204）、`GET /audit-logs`（reportId/action/actorEmail/keyword 筛选）、`GET /claims`（status/keyword/reportId/claimantEmail/reportOwnerEmail/adminHidden 筛选）、`GET /claims/{id}`、`POST /claims/{id}/approve|reject`。新增 `CLAIM_SORT_FIELDS` 白名单映射（跨 join 字段映射到 `report.itemName`、`claimant.email` 等嵌套属性，防注入）；`claimsPageable`/`auditPageable` 校验 page/size/sort 字段白名单，并用 `id DESC` 作为稳定次要排序防止同排序值分页漂移。
- `backend/src/main/java/com/app/campusagent/lostfound/controller/LostFoundNotificationController.java`（新增）：`GET /api/lost-found/notifications`（分页 + unreadOnly）、`GET /unread-count`、`POST /{id}/read`；分页校验并固定 `createdAt DESC` 排序。
- `backend/src/main/java/com/app/campusagent/lostfound/controller/LostFoundReportController.java`：新增 `PUT /reports/{id}`（multipart，`@RequestPart("report")` + 可选 images）、`POST /reports/{id}/close`、`DELETE /reports/{id}`（返回 204）。
- 测试（9 个文件）：
  - `LostFoundAdminSecurityIntegrationTest.java`：新增匿名拒绝 delist/delete（401）、STUDENT 拒绝 restore（403）、管理员可 delist（jsonPath adminHidden=true）、超级管理员可 delete（204）、空 reason 422、管理员可查审计日志；`rejectsAdministrator` 测试同步断言 hiddenReports 字段。
  - `LostFoundNotificationSecurityIntegrationTest.java`（新增）：用 JWT bearer 验证匿名 401、用户看不到他人通知、不能标记他人通知已读（404）、能看到自己的通知且 unread-count 正确。
  - `LostFoundAdminServiceIntegrationTest.java`：新增 delist 隐藏+审计断言、重复 delist 冲突、restore 恢复+审计断言、重复 restore 冲突、管理员删除后报告消失但审计行保留（reportId/itemName/actorEmail 快照）、审计关键词筛选。
  - `LostFoundClaimServiceTest.java`：approve/reject 用例改为断言抛 `CLAIM_REVIEW_ADMIN_ONLY`；submit 后 verify 通知服务 `claimSubmitted`。
  - `LostFoundImageBackfillServiceTest.java`（新增）：mock 仓库/存储，验证单张回填成功 + 失败计数继续且不保存坏图。
  - `LostFoundNotificationServiceTest.java`（新增）：claimSubmitted 发通知、mine 返回读状态/reportId/claimId、unreadOnly 过滤、unreadCount、markRead 仅自己。
  - `LostFoundReportServiceTest.java`：新增超大尺寸拒绝（构造改 IHDR 宽高并重算 CRC 的假 PNG）、owner 编辑/非 owner 拒绝/非 OPEN 拒绝/换图并删旧对象、close 及其拒绝分支、delete 级联清理断言/非 owner/非 OPEN、getById 对下架报告的 owner/admin 可见、非 owner 404。
  - `LostFoundSearchIntegrationTest.java`：新增候选列表暴露 visualFingerprints、公开搜索与候选排除下架报告；构造器同步注入新增依赖。
  - `VisualFingerprintExtractorTest.java`（新增）：用与 Agent `test_embeddings.py` 共享的 golden PNG 断言指纹字节级等于 Python golden vector；纯色图指纹确定且互异；乱码字节回退确定性哈希；WebP 路由到同一回退。

---

## 2. `fa3cc44` feat: improve lost found admin frontend

- 时间：2026-08-10
- **主要作用**：前端把失物招领管理面板从"只读报告浏览"升级为可执行下架/恢复/删除操作并查看审计日志的管理台；同时给报告详情页增加 owner 的编辑/关闭/删除能力。底层补齐了对应 API 封装、类型与单元测试。
- 改动规模：15 个文件，+1144/-128 行

### 逐行解释

- `frontend_web/src/api/adminLostFound.ts`：新增 `delistAdminReport`/`restoreAdminReport`/`deleteAdminReport`（POST 携带 `{ reason }`，delete 无返回）、`searchAdminAuditLogs`（GET `/admin/lost-found/audit-logs`，支持 reportId/action/actorEmail/keyword/page/size/sort），并导出 `AdminAuditLogSearchParams` 接口。
- `frontend_web/src/api/lostFound.ts`：新增 `updateReport`——构造 FormData，`report` 为 JSON Blob、图片 append 到 `images`，`PUT` 时带 `onUploadProgress` 上传进度回调；新增 `closeReport`（POST `/close`）与 `deleteReport`（DELETE）。
- `frontend_web/src/types.ts`：`AdminLostFoundOverview` 加 `hiddenReports`；`AdminLostFoundReport` 加 `adminHidden`；新增 `AuditAction` 联合类型（8 种）、`AdminAuditLog` 接口、`UpdateReportInput` 接口。
- `frontend_web/src/labels.ts`：新增 `auditActionLabels`（AuditAction → 中文显示文案）。
- `frontend_web/src/admin/lostFound/AdminLostFoundPage.tsx`（+395 行，重构）：
  - 顶部加 `Tabs`（Reports/Audit Logs），页签状态存进 URL searchParam `tab`，`changeTab` 写/删该参数；`reportsQueryKey` 用 `useMemo` 剔除 `tab` 参数，使切换页签不触发报告列表重查。
  - 报告加载 useEffect 依赖 `reportsQueryKey`/`refreshCounter`/`tab`，且 `tab !== 'reports'` 时直接 return；新增 `refreshCounter` 状态用于操作成功后触发重查。
  - 新增 `adminHidden` 筛选下拉（All/Hidden/Visible），随表单提交；过滤表单与 URL 参数双向同步。
  - 报告表格：item 下方新增 Hidden warning chip（`report.adminHidden`）；操作列新增 Restore（隐藏时）/Delist（可见时）/Delete 按钮，调用 `openAction(report, action)` 打开确认 Dialog。
  - `confirmAction`：按 action 调用 `delistAdminReport`/`restoreAdminReport`/`deleteAdminReport`，reason 必填（trim 后为空禁用确认按钮），成功后 `closeAction()` + `setRefreshCounter(+1)`；失败展示 `actionError`。Dialog 删除场景用红色 confirm 按钮。
  - 概览卡从 5 格扩到 6 格，新增 "Hidden reports" 指标。
  - `tab === 'audit'` 时只加载 overview（供统计卡），审计表格交给 `AdminAuditLogsSection` 自管理。
- `frontend_web/src/admin/lostFound/AdminAuditLogsSection.tsx`（新增）：独立审计日志组件。筛选表单（action 下拉来自 auditActionLabels、actorEmail/keyword/reportId 输入框）+ 分页表格（Time/Report/Action chip/Actor/Reason/Detail 六列，`formatDateTime` 用 en-SG locale）；`load()` 只把非空筛选参数拼进 query，固定 `size:25&sort=createdAt,desc`；挂载只加载一次、筛选由用户显式 Search/Reset 触发；空态/错误态/loading 齐全。
- `frontend_web/src/components/EditReportDialog.tsx`（新增）：编辑报告对话框。打开时用 `initialForm(report)` 回填文本字段；可选换图——`selectImages` 校验数量 ≤5、类型（jpeg/png/webp）与大小 ≤10MB，用 `URL.createObjectURL` 做本地预览，`imagesRef` 同步 + 卸载/关闭时 `revokeObjectURL` 释放；`submit` 本地先校验 itemName≥3、description≥10，再调 `updateReport(report.id, form, files, setProgress)` 并展示 `LinearProgress`（有进度值则 determinate，否则 indeterminate）；成功后回调 `onUpdated(updated)` 再关闭。
- `frontend_web/src/pages/ReportDetailPage.tsx`：新增 owner 管理入口——仅当 `report.createdByMe && status === 'OPEN'` 时显示 Edit/Close/Delete 按钮行；`handleUpdated` 用编辑结果覆盖本地 report 并提示 "Report updated."；新增 close/delete 确认 Dialog（文案说明不可撤销），`runClose` 调 `closeReport` 更新本地状态，`runDelete` 成功后 `navigate('/lost-found', { replace: true })` 返回列表；Edit 按钮渲染 `<EditReportDialog>`。
- `frontend_web/src/api/adminLostFound.test.ts`：新增 delist/restore/delete 的 POST 调用断言（参数为 `{reason}`）、audit-logs 带筛选与分页的 GET 断言。
- `frontend_web/src/api/lostFound.test.ts`：新增 updateReport（断言 FormData 里 report 是 Blob、images 是文件数组、走 PUT）、closeReport（POST `/close`）、deleteReport（DELETE）测试。
- `frontend_web/src/admin/dashboard/AdminDashboardPage.test.tsx`、`frontend_web/src/admin/dashboard/sections/LostFoundOverviewSection.test.tsx`：fixture 补 `hiddenReports` 字段，适配新接口。
- `frontend_web/src/admin/lostFound/AdminAuditLogsSection.test.tsx`（新增）：mock `searchAdminAuditLogs`，验证行渲染（item/#id/action 文案/actor/reason/detail）、空态、错误态、筛选提交后 trim keyword 并重置 page=0。
- `frontend_web/src/admin/lostFound/AdminLostFoundPage.test.tsx`：新增 hidden chip + Restore 按钮、adminHidden=true 筛选传给后端、delist/restore/delete 完整流程（Dialog 确认、reason trim、成功刷新二次查列表）、切到 Audit 页签不重查报告列表。
- `frontend_web/src/pages/ReportDetailPage.test.tsx`（新增）：owner+OPEN 显示管理按钮、非 owner/已关闭隐藏、close 确认流、delete 确认流、edit dialog 提交后更新名称与提示、非 owner 仍能正常提认领。

---

## 3. `94511f8` test: cover lost found admin claim security

- 时间：2026-08-10
- **主要作用**：为 f810823 新增的管理员认领接口补齐安全集成测试，锁定"认领浏览/审核仅管理员"的权限契约，防止普通用户越权。
- 改动规模：1 个文件，+65 行

### 逐行解释

- `backend/src/test/java/com/app/campusagent/lostfound/controller/LostFoundAdminSecurityIntegrationTest.java`：新增注入 `LostFoundClaimRepository`，并加 5 个测试：
  - `rejectsAnonymousUsersFromClaimsEndpoints`：匿名 GET `/claims` → 401。
  - `rejectsStudentsFromClaimsEndpoints`：`@WithMockUser(roles="STUDENT")` GET `/claims` → 403。
  - `allowsAdministratorToBrowseClaims`：管理员（构造 `UsernamePasswordAuthenticationToken` 带 `ROLE_ADMIN` 权限，经 `authentication(...)` 注入）GET `/claims` → 200 且 `$.content` 为数组。
  - `allowsAdministratorToApproveClaim`：造 owner/admin/claimant 三人、保存 FOUND 报告与 SUBMITTED 认领，管理员 POST `/claims/{id}/approve`（body 带 decisionNote）→ 200，断言 `$.status == APPROVED` 且 `$.report.status == CLAIMED`（验证批准会联动报告状态）。
  - `rejectsStudentsFromApprovingClaim`：认领者本人（非管理员）POST approve → 403，确认 `@PreAuthorize` 权限兜底，普通认领者无法批准自己的认领。

---

## 4. `4d324ec` feat: add lost found agent evaluation and matching improvements

- 时间：2026-08-10
- **主要作用**：为失物招领 Agent 引入「可复现的评估体系」和「多模态匹配」。新增确定性文本/图片 Embedding 模块（本地离线，Java/Python 字节级一致），把候选重排从纯规则升级为「规则 + 文本向量 + 视觉指纹」三路融合；新增匹配排序评估（matching_eval）、NLU 回归评估（nlu_eval）、真实模型批量评估（model_eval）三个 CLI 工具和配套语料；顺带修复了中文 2 字物品名被误拒、LLM 偶发输出失败导致 fail-closed 的问题。
- 改动规模：29 个文件，+1719/-31

### 逐行解释

- `agent/lost_found_agent/lost_found_agent/embeddings.py`（新增，165 行）：本提交的核心。实现确定性本地 Embedding 基线，使匹配管线在引入外部向量库前就有向量召回面。`embed_text()` 用 blake2b 对每个 token 哈希取 4 字节决定桶、第 5 字节符号位做 signed 累加，再 L2 归一化（同一文本在 CI 与 Java 端可重现）；`embedding_tokens()` 把 normalize+tokens 结果与「去掉空格的连续 1/2/3 字 n-gram」并集作为特征，让中文部分重叠（如「黑色无线耳机」vs「黑色耳机盒」）也能得到非零相似度。图片侧 `embed_image()` 输出 64 维颜色直方图：对 RGB 像素按每个通道取高 2 位拼成 64 桶、8×8 网格整数缩放采样、L1 归一化；WebP（JDK ImageIO 解不了）或解码失败走 SHA-256 的确定性 fallback。`visual_fingerprint()` 把 64 个 float 用 `struct.pack("<64f")` + base64 编码成 `VF1:` 前缀字符串，`visual_fingerprint_to_vector()` 反向解析（畸形输入返回 None）；`visual_similarity()` 用 L1 距离转相似度 `1 - dist/2` 并 clamp 到 [0,1]。
- `agent/lost_found_agent/lost_found_agent/matching.py`：把权重表改为 `text/category .28、colour/location .14、date .06、visual .10`（原 text/category .30、colour/location .15、date .10），为 visual 分量腾出权重。`text_similarity` 新增可选 `text_embedding=True` 参数，`max(sequence, jaccard, containment, vector)` 取最大（向量只加分不减分，避免干扰既有规则）；`score_candidate`/`rank_candidates` 透传该开关（供评估工具关掉向量做对照）。新增 `_visual_similarity()`：取 query 的 `visual_fingerprint` 字符串与候选 `visualFingerprints` 列表逐对算相似度取 best，任一缺失返回 None（不参与归一化）。`reason()` 增加 `visual` 分量中英文文案「图片特征相似 / Similar image features」。
- `agent/lost_found_agent/lost_found_agent/llm.py`：`ExtractedFields.item_name` 的 `min_length` 从 3 降到 2（中文钥匙/钱包等 2 字物品名），SYSTEM_PROMPT 同步说明；`max_tokens` 改为读取 settings 的 `lost_found_llm_max_tokens`（推理模型 reasoning_content 会占满 1200）。新增 `LlmTelemetry` dataclass（model/input/output tokens/duration/http_status）和可选 `on_complete` 回调，在 `interpret()` 成功校验后回调，供批量评估采集。新增 `usage_tokens()`（容错提取 `usage.prompt_tokens/completion_tokens`，缺失记 0）和 `interpret_with_retry()`：非推理模型对相同输入也可能输出不同结果，schema/长度偶发不达标直接 fail-closed 会让用户反复看到不可用，所以最多重试 attempts（默认 3）次，把失败率从 ~20% 降到 ~1%。
- `agent/lost_found_agent/lost_found_agent/main.py` 与 `agent/mcp_servers/lost_found_server.py`：两处 invoke 入口都改用 `interpret_with_retry()` 包装 LLM 调用。
- `agent/lost_found_agent/lost_found_agent/config.py`：版本 0.4.0→0.6.0；新增 `lost_found_llm_max_tokens`（默认 4000）、`lost_found_llm_input_cost_per_1m`/`output_cost_per_1m`（单价，默认 0=未配置），供 model_eval 估算费用。
- `agent/lost_found_agent/lost_found_agent/rules.py`：`ALLOWED_CONTEXT_FIELDS` 白名单新增 `visual_fingerprint`，为后续视觉匹配放行。
- `agent/lost_found_agent/lost_found_agent/tools.py`：`ReportLostInput.item_name` min_length 3→2，与 llm.py 口径一致。
- `agent/lost_found_agent/lost_found_agent/matching_eval.py`（新增）：匹配排序评估工具。`load_cases()` 读 JSONL 语料（跳过 # 注释行），每条含 query/candidates/relevant 列表；`evaluate()` 用 `minimum_score=0.0`（避免默认 0.35 阈值过滤掉相关项污染排序指标），跑三个变体：rule（strip 掉视觉字段、关向量）、embedding（strip 视觉、开向量）、multimodal（全开），计算 recall@5/precision@5/MRR/NDCG@5 和 mean_first_relevant_rank；`compare()` 要求 rule 基线存在，输出每指标 best 与相对 rule 的 delta；`main()` 支持 `--variant all|rule|embedding|multimodal`，`all` 时打印逐指标对比表。
- `agent/lost_found_agent/lost_found_agent/nlu_eval.py`（新增）：规则 NLU 回归评估，复用 `detect_explicit_intent` + `extract_fields`，算 intent_accuracy / field_completeness / mistaken_write_rate（`must_not_write` 用例被误判成写意图）并列出失败用例。
- `agent/lost_found_agent/lost_found_agent/model_eval.py`（新增）：真实模型批量评估。`run_evaluation()` 逐条顺序调用 `interpret_with_retry`（不并发，避免扭曲延迟分位），通过 `on_complete` 收集 telemetry，叠加质量指标、延迟分位（p50/p95/p99）、token 用量与 `_estimate_cost()`（用 config 单价算美元）。`main()` 里未配置 `LOST_FOUND_LLM_API_KEY` 时输出 skipped 报告并 exit 0（不影响 CI）。
- `agent/lost_found_agent/pyproject.toml`：版本 0.4.0→0.6.0，新增依赖 `pillow>=11,<13`（embeddings 图片直方图用）。
- `agent/mcp_servers/Dockerfile`：fallback 安装把无版本限制的 `mcp` 改成 `"mcp>=1.28,<2"`——之前无版本限制会装成 2.x 并移除 fastmcp，破坏依赖。
- `agent/schemas/lost-found-agent.json`：版本 1.3.0→1.4.0，weights 同步为六分量并新增 `visualFingerprint` 配置段（queryField `visual_fingerprint` / candidateField `visualFingerprints`）。
- `agent/lost_found_agent/README.md`：补匹配权重与评估工具使用说明。
- `agent/lost_found_agent/tests/helpers.py`：新增 `make_solid_png(rgb)` 用 Pillow 生成纯色 PNG，供图片指纹测试用。
- `agent/lost_found_agent/tests/test_embeddings.py`（新增）：核心是跨语言一致性契约——硬编码一份 16×16 半蓝半红 PNG（GOLDEN_PNG）及其应算出的 GOLDEN_FINGERPRINT（348 字符 `VF1:`），断言 Python `embed_image→visual_fingerprint` 产出与 Java `VisualFingerprintExtractorTest` 一致；另测中英文本部分重叠、纯色可判别、malformed 返回 None、fallback 确定性。
- `agent/lost_found_agent/tests/test_matching.py`：新增视觉分量测试——文本完全相同、仅图片颜色不同的候选靠 visual 分量区分排序；多候选图取 best；`text_embedding=False` 关掉向量后分数不高于开启。
- `agent/lost_found_agent/tests/test_llm.py`：新增 fail-closed 用例——模型在 fields 里带嵌套额外键（visual_fingerprint）必须失败且不调用后端。
- `agent/lost_found_agent/tests/test_matching_eval.py` / `test_model_eval.py` / `test_nlu_eval.py`（新增）：评估工具的单元测试，验证语料加载、multimodal 在视觉判别上 MRR 优于 rule、compare 需 rule 基线、CLI 输出形状、model_eval 用 MockTransport 模拟 LLM 的质效/费用/重试统计、无 Key 时 skipped。
- `agent/lost_found_agent/tests/fixtures/*.jsonl`（新增）：matching_regression（9 条含纯文本/类别颜色地点/日期/视觉指纹样例，最后一条 relevant 为空验证 0 相关不污染指标）、model_regression（8 条，含 must_not_write 的搜索用例）、nlu_regression（4 条规则用例）。
- `agent/lost_found_agent/tests/test_api.py` / `test_contract.py`：版本断言 0.4.0→0.6.0、schema 1.3.0→1.4.0。
- `agent/lost_found_agent/uv.lock`：锁定新增的 pillow 及依赖树（含模型评估不新增外部包）。

---

## 5. `b20ac26` 实现主动report时产品类别信息智能填写

- 时间：2026-08-11
- **主要作用**：让用户在手动上报（主动 report）页面填写物品名时，系统自动给出"产品类别（category）"建议并回填下拉框。背景是此前类别完全靠用户手选，容易选错；本提交打通"前端失焦触发 → 网关代理 → Agent 规则/LLM 分类 → 回填前端"的完整链路，且全程 fail-open，分类失败绝不影响创建报告。
- 改动规模：16 个文件，+711/-22 行

### 逐行解释

- `agent/lost_found_agent/lost_found_agent/llm.py`：新增轻量分类能力。核心是新增 `CategorySuggestion` 模型与 `CLASSIFY_PROMPT` 常量，以及 `LlmInterpreter.classify_item()` 方法。
  - `CategorySuggestion(BaseModel)`（新增）：仅含 `category: Category | None` 一个字段，`model_config = ConfigDict(extra="forbid")`。注释说明了刻意不带 description 等字段的动机——避免 `ExtractedFields.description` 的 `min_length=10` 校验把"白色耳机"这种短物品名误判为不可信；`extra="forbid"` 保证模型多输出任何键都会校验失败，从而走 fail-open。
  - `CLASSIFY_PROMPT`（新增）：把 9 个枚举（ELECTRONICS/ID_CARD/WALLET_PURSE/KEYS/BAG/CLOTHING/BOOKS_STATIONERY/UMBRELLA/OTHER）交给模型；明确"真实物理物品但无专属类别归 OTHER"，"非具体物品（颜色/动作/含糊短语）或真歧义才返回 category=null"，并要求只输出单个 `category` 键。
  - `classify_item(self, item_name)`（新增）：构造 `/chat/completions` 请求，`temperature=0`、`response_format={"type":"json_object"}`、`max_tokens` 复用配置；随后 `_extract_content(payload)` 取文本、`len>20_000` 视为过大、`strip_code_fence` 去围栏后 `CategorySuggestion.model_validate_json`。异常统一 `raise LlmUnavailable`（网络/超时/非 JSON/无效枚举/多余键全部覆盖），时间用 `perf_counter()` 记录并通过 `_on_complete` 上报 `LlmTelemetry`（与 `interpret()` 保持一致）。整体原则是"分类建议是低风险读操作，绝不应阻塞表单"。
- `agent/lost_found_agent/lost_found_agent/main.py`：注册新端点并组装分类器。
  - `create_app()` 顶部：`classify_interpreter` 独立于主 mode 创建——只要 `lost_found_llm_api_key` 非空就可用；llm 模式复用 `active_llm_interpreter` 同一实例，否则新建 `LlmInterpreter(active_settings)`。这样 rules 模式下配了 key 也能 LLM 兜底。
  - 生命周期：shutdown 时若 `classify_interpreter` 不是 `active_llm_interpreter` 才单独 `close()`，避免重复关闭。
  - 新增 `POST /agent/classify`：`@app.post("/agent/classify", response_model=ClassifyResponse)`；先 `security.verify(request, "classify")` 鉴权，然后 `map_category(payload.item_name)` 规则优先；命中 `None` 且有 `classify_interpreter` 时调 `classify_item` 兜底；`LlmUnavailable` 捕获后 `category=None`。返回 `ClassifyResponse(category=...)`，fail-open 保证绝不 5xx。
- `agent/lost_found_agent/lost_found_agent/models.py`：新增请求/响应 DTO。
  - `ClassifyRequest`：`item_name: str = Field(min_length=1, max_length=200)`。
  - `ClassifyResponse`：`category: str | None = None`，None 表示规则与 LLM 均无法判断。
- `agent/lost_found_agent/lost_found_agent/rules.py`：扩充分类规则表 `CATEGORIES`。
  - 新增 `"遥控": "ELECTRONICS"`；新增 `"汽车": "OTHER"`、`"车辆": "OTHER"`（车辆无专属类别落 OTHER）。注释强调"遥控需在汽车之前命中"，使复合词"遥控汽车"归 ELECTRONICS 而非 OTHER；测试同时保证"车钥匙"仍命 KEYS（子串顺序回归保护）。
- `agent/lost_found_agent/tests/test_classify.py`（新增）：`/agent/classify` 端点全场景测试。覆盖：未带安全头 401；中英文规则命中（黑色耳机→ELECTRONICS、wallet→WALLET_PURSE）；车辆落 OTHER；复合词遥控汽车→ELECTRONICS、车钥匙→KEYS；规则未命中且无 key 时返回 category=None；`action="invoke"` 的签名调用 classify 端点被拒 403（intended_action 作用域隔离）；空 item_name 422；nonce 复用被拒 401。LLM 兜底侧用 `httpx.MockTransport`：规则命中不调 LLM；规则未命中调 LLM 并校验请求 URL/Authorization；模型返回 None 时透传 null；"not-json / 超时 / 429 / 无效枚举 TELEPORT" 四种坏输出全部 fail-open 为 200+null；最后验证 rules 模式配 key 也能走 LLM 兜底。
- `backend/src/main/java/com/app/campusagent/lostfound/controller/LostFoundAgentWebController.java`：新增 `@PostMapping("/classify")`，入参 `@Valid AgentClassifyWebRequest`、`@AuthenticationPrincipal User`，委托 `agentGateway.classify(request, currentUser)`。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/agent/AgentClassifyResponse.java`（新增）：`record AgentClassifyResponse(String category)`。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/agent/AgentClassifyWebRequest.java`（新增）：record 含 `@NotBlank` + `@Size(max=200)` 校验的 `itemName`；`toAgentPayload()` 返回 `Map.of("item_name", itemName.trim())`（去除首尾空格）。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundAgentGateway.java`：重构为共用 HTTP 调用骨架，新增 classify 方法。
  - 构造器改为链式重载：`classifyUri` 由 `invokeUri` 字符串替换 `"/agent/invoke"→"/agent/classify"` 得到。
  - 抽出私有 `callAgent(uri, body, intendedAction, currentUser, traceId)`：原 `invoke()` 里创建 nonce/timestamp、签名、发送、异常处理的逻辑整体移入；`delegationToken()` 新增 `intendedAction` 参数并写入 JWT 的 `intended_action` claim（原来是硬编码 `"invoke"`）。
  - 新 `classify()`：序列化 `toAgentPayload()` 后以 `intendedAction="classify"` 调 `callAgent`；响应取 `payload.get("category")`，`instanceof String` 才作为 category，否则 null——保证 Agent 端 fail-open 的 null 在网关侧被兜住。
  - `invoke()` 相应变薄：复用 `callAgent` 后仍校验 `response`/`status` 两个字段，行为不变。
- 后端测试：`LostFoundAgentClassifyControllerTest.java`（新增，+81 行）MockMvc 单测——正常返回 category、Agent 不确定时 category=null、空白 itemName 返回 422 且不调用 gateway；`LostFoundSecurityIntegrationTest.java` 新增 `rejectsUnauthenticatedClassifyRequests`（未登录访问 `/api/lost-found/agent/classify` 返回 401）；`LostFoundAgentGatewayTest.java` 新增两个测试——第一个用内嵌 `HttpServer` 起 `/agent/classify` 上下文，断言请求体含 `item_name`、`X-signature` 校验一致、JWT 中 `intended_action == "classify"` 且 `jti==nonce`；第二个验证 Agent 返回 `{"category":null}` 时网关回退 null。
- `frontend_web/src/api/lostFound.ts`：新增 `suggestCategory(itemName)`：POST `/lost-found/agent/classify`，body `{ itemName }`，返回 `response.data.category ?? null`。
- `frontend_web/src/pages/CreateReportPage.tsx`：接入自动填分类。
  - 新增 `categoryTouched` state 与 `categoryTouchedRef` ref，以及 `autoSuggestCategory()`：取 `form.itemName.trim()`，为空或已被手动选过分类（`categoryTouchedRef.current`）则直接返回；调 `suggestCategory(name)`，await 后若仍未被手动改过才 `setForm` 覆盖 category；catch 静默。ref 的用途注释说明"防止异步返回时覆盖用户刚手动选过的分类"。
  - Item name 输入框加 `onBlur={autoSuggestCategory}`（失焦触发）；Category Select 的 `onChange` 里置 `categoryTouchedRef.current = true`（用户手选后禁止自动覆盖）。
- `frontend_web/src/api/lostFound.test.ts`：新增 `suggestCategory` 三例——正常返回 category、Agent 不确定返回 null、响应缺 `category` 键返回 null。
- `frontend_web/src/pages/CreateReportPage.test.tsx`（新增）：自动分类交互测试。用 jsdom 中 `getByRole('combobox')` 定位 Category（注释说明 aria-labelledby 的 name 在 jsdom 不解析）。覆盖：失焦后回填类别；用户已手选 Other 后失焦不再覆盖也不调用 API；Agent 不确定时保留默认 Electronics；请求失败静默（无 alert 且分类不变）。

---

## 6. `25437b2` 修复浏览页图片无法显示

- 时间：2026-08-11
- **主要作用**：修复 Browse 列表/详情/编辑/Agent 候选卡片图片全部加载不出来的 bug。根因是后端把 MinIO 用 Docker 内网地址 `http://minio:9000` 生成的预签名 URL 直接塞进响应，浏览器解析不了该主机名（HTTPS 下还有 mixed-content 拦截），且预签名 URL 仅 15 分钟有效。修复方案是新增后端图片代理端点，所有图片回显统一改走同源代理 URL。
- 改动规模：9 个文件，+388/-29

### 逐行解释

- `backend/.../config/SecurityConfig.java`：`/api/lost-found/images/**` 加入 permitAll。原因 `<img>` 标签不携带 JWT；已知权衡 imageId 为自增主键可枚举、无登录可看图（记录在开发文档 §6）。
- `backend/.../controller/LostFoundImageController.java`（新增）：图片回显代理端点 `GET /api/lost-found/images/{imageId}`。按 imageId 查 `LostFoundImage`，不存在抛 `IMAGE_NOT_FOUND`(404)；`storageService.download(objectKey)` 从 MinIO 拉字节，回原始 contentType（空则 octet-stream），`Cache-Control: max-age=1天, public`——objectKey 是随机 UUID 且图片上传后不变可安全缓存，删记录后 404 兜底。
- `backend/.../dto/LostFoundImageResponse.java`：新增静态工厂 `of(LostFoundImage)`，统一构造 url=`/api/lost-found/images/{id}` 的响应，避免各处重复拼装。
- `backend/.../service/LostFoundAdminService.java`：`toDetail()` 改为 `LostFoundImageResponse.of()` 构造图片 URL，并移除不再使用的 `ObjectStorageService` 注入。
- `backend/.../service/LostFoundReportService.java`：`toResponse()`（详情/列表/编辑）与 `searchCandidates()`（Agent 候选）里所有 `storageService.createPresignedGetUrl(...)` 替换成 `LostFoundImageResponse.of(...).url()`。
- `backend/.../controller/LostFoundImageControllerTest.java`（新增）：验证无鉴权请求 `GET /images/1` 返回字节与 `image/png` Content-Type；id 不存在返回 404。
- `backend/.../service/LostFoundReportServiceTest.java`：图片 url 断言从 `http://minio/item.png` 改为 `/api/lost-found/images/7`（mock save 时给图片 id=7），update 场景同理；删除不再需要的 presigned mock。
- `backend/.../service/LostFoundSearchIntegrationTest.java`：候选断言改为 `/api/lost-found/images/{imageId}`（从 saveAndFlush 后的实体取真实 id）。
- `docs/lost-found/IMAGE_MATCHING_DEVELOPMENT_cn.md`（新增）：图片匹配的完整开发记录/计划，含根因分析（预签名 URL、mixed-content、无 URL 重写）、目标架构数据流（Spring Boot 唯一写入口/唯一碰 MinIO）、分三阶段计划、契约变更清单、安全边界（方案 A permitAll vs 方案 B blob-fetch 的权衡）、风险债与真实运行验证结果表。

---

## 7. `ccd1c38` 完成Agent 图片上传 + 图片匹配，修复纯图搜索无法正常匹配的bug

- 时间：2026-08-11
- **主要作用**：实现阶段 2「Agent 图片上传 + 图片匹配」，把上一提交搭好的视觉匹配架子两端接通：Agent 面板可发图，图经 Spring Boot 暂存（MinIO `lost-found-staging/` 前缀 + 上传即算指纹），确认创建时关联为报告图片；候选端返回指纹、查询端注入指纹参与打分。并修复纯图搜索 bug：仅发图占位语「帮我找这个」被抽成无信息 keyword，把纯视觉匹配分数拖到阈值以下导致「完全一致的图片也匹配不到」。
- 改动规模：36 个文件，+1473/-184

### 逐行解释

- `agent/lost_found_agent/lost_found_agent/matching.py`：`_visual_similarity` 从「单指纹 query」扩展为支持多图——新增 `_query_fingerprints()` 同时识别 `visual_fingerprint`（单）与 `visual_fingerprints`（多图），对「每个 query 指纹 × 每个候选指纹」全组合算相似度取 best。
- `agent/lost_found_agent/lost_found_agent/models.py`：新增 `AgentImage`（object_key / visual_fingerprint / url），`InvokeRequest` 增加 `images: list[AgentImage]`（max_length=5）。
- `agent/lost_found_agent/lost_found_agent/rules.py`：核心修复。`ALLOWED_CONTEXT_FIELDS` 加 `visual_fingerprints`、`images`；新增 `KEYWORD_STOPWORDS`（中英文指示代词/量词：「这个/那个/一个/this/that/it」等）与 `is_stopword_keyword()`——因为仅发图占位语会抽出「这个」，它无检索信息，作为 text 分量近零相似度会把加权平均分拉到最低阈值 0.35 以下，即注释里算的 `(0.28·0+0.10·1.0)/0.38≈0.263`。过滤发生在两处：LLM `interpreted_fields` 合并进 context 时跳过停用词 keyword；`extract_fields` 规则抽取时对搜到的 keyword 先 `is_stopword_keyword` 检查。`handle()` 里 `payload.images` 并入 context：`images` 只存 objectKey 列表，指纹存 `visual_fingerprints` 并同时把第一张写进 `visual_fingerprint`（兼容单图）；多轮共享——本轮携带覆盖、否则沿用 shared_data。`_search_candidates` 的候选拉取条件白名单加 `visual_fingerprint`/`visual_fingerprints`，使「仅发图」也能走检索。`safe_context` 对 `images`/`visual_fingerprints` 只放行字符串列表。
- `agent/lost_found_agent/lost_found_agent/tools.py`：`ReportLostInput`/`ReportFoundInput` 增加 `images`（objectKey，确认创建时发给内部 API）与 `visual_fingerprints`（查询端指纹）；`CampusApiClient.report_lost/found` 的 body 在有 images 时附加 `imageKeys`。
- `agent/schemas/lost-found-agent.json`：1.5.0→1.6.0，`invokeInput` 增加 `images` 数组（object_key 必填、指纹/url 可空，maxItems 5）。
- `agent/lost_found_agent/tests/test_rules.py`：新增 4 个关键回归——带图报失确认前后 flows（确认前零写入，确认后 report_lost 载荷含 images/visual_fingerprints）；纯图搜索走 search_found_items；占位语 + 完全一致指纹 → match_found、score=1.0、含「图片特征相似」且 shared_context 无 keyword（锁定停用词修复）；图搜结果跨轮共享指纹。
- `agent/lost_found_agent/tests/test_matching.py`：新增多指纹 query 取 best 对测试。`test_matching_eval.py`：语料 9→10 条、scored_cases 8→9。`fixtures/matching_regression.jsonl` 增加多图 query 样例。`test_tools.py` 验证 `imageKeys` 进 body。`test_contract.py` 版本断言 1.6.0 + images 载荷校验。
- `backend/.../CampusAgentApplication.java`：加 `@EnableScheduling` 启用 TTL 定时任务。
- `backend/.../service/LostFoundImageStagingService.java`（新增）：Agent 图片暂存服务。`upload()` 校验单图后传 MinIO `lost-found-staging/<uuid>.<ext>`，原始文件名写进 user metadata（供重建元数据），上传即用 `VisualFingerprintExtractor` 算指纹，返回 `StagedImageResponse`；`retrieve()` 读暂存对象（校验前缀，不存在抛 NOT_FOUND——使创建整体回滚，避免半态）；`list()` 列暂存区供 TTL 用；`delete()` best-effort。`safeOriginalName` 只取文件名防路径穿越。
- `backend/.../service/LostFoundImageRules.java`（新增）：从 `LostFoundReportService` 抽出的共享图片校验（≤5 张、≤10MB、JPEG/PNG/WebP 白名单 + magic bytes 校验、单边 ≤8192 防解压炸弹——WebP 无法被 ImageIO 识别时跳过尺寸检查，指纹走 SHA-256 fallback 不触发解码）。
- `backend/.../service/LostFoundImageStagingCleanupJob.java`（新增）：`@Scheduled(fixedDelay=staging-cleanup-interval-ms 默认 1h)` 清理超过 `staging-ttl-hours`（默认 24h）的暂存对象，且 `!imageRepository.existsByObjectKey()`（已关联的键跳过，避免误删报告图）。
- `backend/.../service/LostFoundReportService.java`：新增 `createFromStaged()`——Agent 确认创建路径：校验数量后建报告，逐个 `stagingService.retrieve()` 下载暂存字节 → 重算指纹 → 建 `LostFoundImage` 行（objectKey 复用暂存键），`saveAndFlush` + 审计「staged=true」；任一暂存对象缺失即抛异常让事务回滚。`searchCandidates()` 的 `AgentCandidateResponse` 新增 `visualFingerprints`（与 imageUrls 同序，无指纹的位置 null）。`validateImages` 简化为委托 `LostFoundImageRules.validateAll`（删掉内联的 magic bytes/尺寸校验重复代码）。
- `backend/.../controller/LostFoundAgentWebController.java`：新增 `POST /upload-image`（multipart，登录用户）→ `stagingService.upload(file)`。
- `backend/.../controller/LostFoundAgentInternalController.java`：`reports/lost` 与 `reports/found` 从 `create(..., List.of())` 改为 `createFromStaged(..., imageKeys(request.imageKeys()), ...)`，透传暂存键。
- `backend/.../controller/LostFoundImageController.java`：新增 `GET /images/staging/{objectName}` 暂存图预览（随机 UUID 文件名不可枚举，与自增 id 不同；拒绝含 `/`、`\` 的名字防路径穿越），从暂存服务读字节返回。
- `backend/.../dto/agent/`：`AgentCreateLostReportRequest`/`AgentCreateFoundReportRequest` 加 `imageKeys`（@Size max 5）；`AgentCandidateResponse` 加 `visualFingerprints`；`AgentWebInvokeRequest` 加 `images: List<AgentImage>`（objectKey 必填），`toAgentPayload()` 转 snake_case 透传。`StagedImageResponse.java`（新增）为暂存响应 DTO。
- `backend/.../repository/LostFoundImageRepository.java`：新增 `existsByObjectKey` 供 TTL 判断。
- 后端测试：新增 `LostFoundAgentUploadImageControllerTest`（登录可暂存、未登录 401）、`LostFoundImageStagingCleanupJobTest`（只删过期且未引用）、`LostFoundReportServiceTest` 新增 3 个——从暂存创建复用 objectKey/重算指纹、暂存对象缺失整体回滚不调 saveAndFlush、超 5 个暂存键拒绝；`LostFoundImageControllerTest` 加暂存预览与分隔符拒绝用例；`LostFoundAgentGatewayTest`/`LostFoundSearchIntegrationTest` 适配新构造参数并断言候选指纹与 imageUrls 同序。
- `frontend_web/src/api/lostFoundAgent.ts`：新增 `StagedAgentImage` 类型与 `uploadAgentImage(file)`（multipart 到 `/lost-found/agent/upload-image`）；`AgentInvokeRequest` 加 `images`。
- `frontend_web/src/components/LostFoundAgentPanel.tsx`：图片选择/暂存/预览/移除。`selectImages` 校验类型（jpeg/png/webp）与 ≤10MB、总图 ≤5，逐张 `uploadAgentImage`；`send` 在无文字但有图时用占位语「帮我找这个」触发按图检索（Agent 契约要求 message 非空）；发送与确认都带 `images`；确认创建成功后清空暂存（已关联落库）；消息气泡渲染已发图片，输入区渲染已选图 + 删除按钮 + 上传中 spinner。
- `frontend_web/src/api/lostFoundAgent.test.ts` / `components/LostFoundAgentPanel.test.tsx`：新增转发 images、multipart 上传、占位语消息、移除、超大文件不触上传即报错等用例。

---

## 8. `c01bf95` 完成Browse以图搜物

- 时间：2026-08-11
- **主要作用**：实现阶段 3「Browse 以图搜物」。架构决策是给 Agent 新增轻量搜索路由 `POST /agent/search` 直接复用 `matching.rank_candidates` 打分（不做 Java 侧打分重实现），保证与 Agent 面板同图结果逐字节一致；后端网关加 `/search` 代理（`intended_action=search`），前端 ReportsPage 加「Search by image」上传/搜索交互。顺带记录并排除了部署镜像过期导致「同图 Not Found」的真实运行问题。
- 改动规模：18 个文件，+1036/-63

### 逐行解释

- `agent/lost_found_agent/lost_found_agent/main.py`：新增 `POST /agent/search` 路由，响应 `SearchResponse`。`security.verify(request, "search")`（intended_action 校验）；query 构造只放非空字段（keyword/category/colour/location/date_from/date_to）——关键坑：`score_candidate` 对缺失字段按 `str()` 拼进 text 分量，若 key 以 None 存在会注入 `"None"` 幽灵文本；images 的指纹收集进 `query["visual_fingerprints"]`。调用模块级 `search_candidates(..., target_report_type=payload.report_type)`；`BackendApiError` 时返回 `status="failed"`。注释明确不注入 `event_date`，避免 ±30 天窗口兜底（Browse 用显式 date 硬过滤）；language 固定 zh 使理由文案与面板一致。
- `agent/lost_found_agent/lost_found_agent/models.py`：新增 `SearchRequest`（report_type 必填 FOUND/LOST，keyword/category/colour/location/date 可选，`images` min 1 max 5；`model_validator` 校验 date_from ≤ date_to）与 `SearchResponse`（status: match_found/no_match/failed，match_results 复用 `MatchResult`）。
- `agent/lost_found_agent/lost_found_agent/rules.py`：把 `RuleEngine._search_candidates` 抽取为模块级 `search_candidates(api_client, query, verified, minimum_score, language, emit, target_report_type)`——逻辑原样搬移（含 ±30 天窗口、size=100、in-memory rank_candidates），聊天流程与 Browse 搜索共用同一函数，保证逐字节一致；`RuleEngine._search_candidates` 变成一行委托。
- `agent/schemas/lost-found-agent.json`：1.6.0→1.7.0，新增 `$defs.searchInput`（images 必填 min 1）+ `$defs.searchOutput` + 顶层 `search` 段。
- `agent/lost_found_agent/tests/test_search_route.py`（新增，7 用例）：同指纹纯图搜 score=1.0 含「图片特征相似」；keyword 不进后端硬过滤而进 in-memory 打分（视觉同样 1.0 但文字不匹配的候选被剔除）；无关色指纹 no_match；LOST 方向调 search_lost_items；错 intended_action（invoke）403；缺 images 422；日期倒置 422。`test_contract.py` 加 search 段校验、版本断言 1.7.0。
- `backend/.../dto/agent/AgentWebSearchRequest.java`（新增）：Browse 图搜请求 DTO——reportType（@Pattern FOUND|LOST）+ 可选筛选 + images（@Size 1..5，复用 `AgentWebInvokeRequest.AgentImage`）；`toAgentPayload()` 转 snake_case 且空字段省略（`hasText` 判断，防注入幽灵文本）。
- `backend/.../dto/agent/AgentWebInvokeRequest.java`：`agentImagePayload` 从 private 改为包级 static（被 AgentWebSearchRequest 复用）。
- `backend/.../service/LostFoundAgentGateway.java`：新增 `searchUri`（由 invokeUri 的 `/agent/invoke` 替换成 `/agent/search`）与 `search()`——序列化 payload → `callAgent(searchUri, body, "search", ...)`（HS256 Delegation Token 带 `intended_action="search"`），校验响应有 `status` 字段。
- `backend/.../controller/LostFoundAgentWebController.java`：新增 `POST /search`（authenticated，任意登录角色）→ `agentGateway.search()`。
- 后端测试：`AgentWebSearchRequestTest`（camelCase→snake_case、空字段省略）、`LostFoundAgentSearchControllerTest`（200 返回透传、非法请求 422 且不调 gateway）、`LostFoundAgentGatewayTest` 新增 `searchesAndSignsWithSearchAction`（校验 body 内容与 X-nonce/X-timestamp/X-signature、Authorization 的 subject/intended_action=search）。
- `frontend_web/src/api/lostFound.ts`：新增 `searchByImage()`（POST `/lost-found/agent/search`）与 `AgentImageSearchInput/Request/Response/Status` 类型。
- `frontend_web/src/pages/ReportsPage.tsx`：筛选区加「Search by image」按钮（类型/≤10MB 校验 → `uploadAgentImage` 暂存 → 预览 + 移除）；`runImageSearch()` 组装 `searchByImage` 请求（reportType 取当前切换、文本筛选叠加、图优先）；`submit()` 在 `stagedImage` 存在时走图搜而非普通列表 effect；`toReportCard()` 把 `AgentMatchResult` 映射成 `ReportCard` 兼容对象（item_id→id，image_urls→images）；图搜结果无分页（≤5），failed/no_match/匹配各有独立 UI；切换视图/重置/Agent 创建报告后清空已选图。
- `frontend_web/src/api/lostFound.test.ts` / `pages/ReportsPage.test.tsx`：新增图搜请求 payload 断言、图搜渲染卡片无分页、文字筛选叠加、视图切换方向、移除图回退普通列表等用例。
- `docs/lost-found/IMAGE_MATCHING_DEVELOPMENT_cn.md`：更新阶段 3 状态/契约/进度表，新增数据流说明（ReportsPage→gateway search→/agent/search→search_candidates→rank_candidates）、设计要点（复用非复制、None 幽灵文本坑、方向语义）、真实运行冒烟结果与「同图 Not Found」根因排查（部署镜像过期，重建后复验通过，Python 指纹与 DB 逐字节 348 字符一致）。

---

## 9. `328e76c` 修复CI检查未通过：ruff E501行过长与eslint未使用变量

- 时间：2026-08-11
- **主要作用**：修复上一提交引入的 CI 静态检查失败（ruff E501 行过长 + eslint 未使用变量），属纯代码清理，无逻辑变更。背景是 b20ac26 合入后 CI 的 ruff/eslint 报错。
- 改动规模：3 个文件，+7/-6 行

### 逐行解释

- `agent/lost_found_agent/lost_found_agent/rules.py`：`search_candidates` 的 docstring 原来为了规避某历史问题把中文写成字面 `\uXXXX` 转义（每行被拉得很长，如 `"""候选...` 那一行），本提交还原为真实中文（"候选检索 + 打分。Browse 以图搜物与 chat 双向匹配共用同一套链路…"），既消除 E501 行过长，也让文档可读。纯字符串内容，无行为变化。
- `agent/lost_found_agent/lost_found_agent/main.py`：把 `classify_interpreter = active_llm_interpreter or llm_interpreter or LlmInterpreter(active_settings)` 这一超长单行改成括号换行三行写法，满足 ruff E501。逻辑等价。
- `frontend_web/src/pages/CreateReportPage.tsx`：删除上一提交引入但从未被读取的 `categoryTouched` state（`const [categoryTouched, setCategoryTouched] = useState(false)`）及 Category Select `onChange` 里的冗余 `setCategoryTouched(true)` 调用，只保留 `categoryTouchedRef`（ref 才是真正被读取的防覆盖标志）。消除 eslint 的未使用变量告警。

---

## 10. `57f3e7e` 安全测试

- 时间：2026-08-12
- **主要作用**：围绕分类/搜索相关测试做类型安全加固与格式化清理（提交名标注"安全测试"，内容实质是 CI 通过的收尾工作）。需要如实说明：本提交在 `rules.py` 并未新增任何安全校验逻辑，`KEYWORD_STOPWORDS` 的改动纯属把每个停用词单独一行（满足 ruff 格式化/行宽要求），词条集合与既有逻辑完全不变。
- 改动规模：4 个文件，+33/-19 行

### 逐行解释

- `agent/lost_found_agent/lost_found_agent/rules.py`：`KEYWORD_STOPWORDS` 常量从"一行多词"改为"一词一行"（中文指示代词/量词与英文指示代词逐一拆行），是对静态检查（ruff format / E501）的排版修复。停用词集合与注释语义均未变（仍用于防止抽取"这个/那个/this/that"等作为搜索 keyword）。
- `agent/lost_found_agent/tests/test_classify.py`：`classify()` 辅助函数补类型标注。新增 `from typing import cast`；返回类型从无标注改为 `-> httpx.Response`，并把 `client.post(...)` 包一层 `cast(httpx.Response, ...)`——因为 `TestClient` 泛型无法推断，需显式 cast 满足 mypy 类型检查。
- `agent/lost_found_agent/tests/test_rules.py`：类型加固 + 格式化。新增 `from lost_found_agent.tools import ReportLostInput`；`test_report_with_staged_image_flows_images_to_confirmed_create` 里把 `lost_payload = fake_api.calls[0][2]` 包成 `cast(ReportLostInput, ...)`，让 mypy 认为它是强类型输入模型；两处 `fake_api.candidates = [...]` 从三行压成一行，以及一处 `assert any("图片特征相似" ...)` 从多行压成一行，均为行宽整理。
- `agent/lost_found_agent/tests/test_search_route.py`：`test_image_search_no_match_for_unrelated_image` 里的 `fake_api.candidates = [...]` 从三行合并为一行，纯排版。

---

## 11. `8d8b51f` 实现个人中心功能

- 时间：2026-08-16
- **主要作用**：为失物招领模块新增「个人中心」页面及配套后端 API。用户可以查看/编辑昵称、上传/更换头像、按 `owner=me` 查看自己发布的失物/拾物报告（含被管理员下架的）、查看认领数量，并新增中英双语 FAQ 静态页。同时把「展示名」打通到全站顶部导航（头像 + 昵称），并让报告搜索接口支持 `owner=me` 过滤与 `adminHidden` 透出。
- 改动规模：42 个文件，+1633/-37 行

### 逐行解释

#### 后端——个人中心核心

- `backend/src/main/java/com/app/campusagent/controller/UserProfileController.java`（新增，88 行）：新增 `/api/users/me/**` 资料端点与 `/api/users/avatar/{objectKey}` 公开回显代理。
  - `getProfile`：`GET /api/users/me/profile`，从 `@AuthenticationPrincipal User` 取当前登录用户，委托 `profileService.getProfile`。
  - `updateNickname`：`PUT /api/users/me/profile`，接收 `UpdateProfileRequest`，校验并落库昵称。
  - `uploadAvatar`：`POST /api/users/me/avatar`（`consumes = MULTIPART_FORM_DATA_VALUE`），用 `@RequestPart("file")` 收 MultipartFile。
  - `downloadAvatar`：`GET /api/users/avatar/{objectKey}`。关键安全守卫：先校验 objectKey 必须以 `avatar-` 前缀开头且不含 `/` 或 `\`，否则直接抛 `LostFoundApiException(404, AVATAR_NOT_FOUND)`——避免该公开端点变成读取 MinIO 任意对象（如失物图片）的通道。通过校验后 `storageService.download(objectKey)` 读取字节，`Content-Type` 由 `UserProfileService.avatarContentType` 按扩展名推导，响应头加 `Cache-Control: max-age=1 day, public`（头像对象键是随机 UUID、上传后不可变，可安全公开缓存）。
- `backend/src/main/java/com/app/campusagent/service/UserProfileService.java`（新增，106 行）：资料读写与头像上传的服务层。
  - `AVATAR_URL_TEMPLATE = "/api/users/avatar/%s"`：对外返回代理 URL 而非 MinIO 内部对象键。
  - `getProfile`：`@Transactional(readOnly=true)`，直接把当前用户映射为 `UserProfileResponse`。
  - `updateNickname`：先判空（request 或 nickname 为 null 抛 `NICKNAME_REQUIRED`），然后 `request.nickname().trim()` 去首尾空白；`isEmpty()` 抛 `NICKNAME_REQUIRED`，长度 >30 抛 `NICKNAME_INVALID_LENGTH`；通过后 `setNickname` 并 `save`。
  - `uploadAvatar`：先 `LostFoundImageRules.validateAvatar(file)` 校验类型/大小/尺寸；生成对象键 `"avatar-" + UUID.randomUUID() + extension(contentType)`（无目录、随机、不可枚举）；`storageService.upload(file, objectKey)` 上传；把新对象键写入 `avatarUrl` 并 `save`；若之前有旧头像则 `storageService.delete(previous)` 清理；返回新 profile。
  - `avatarContentType`（static）：按对象键扩展名（小写）返回 `image/png` / `image/webp` / 默认 `image/jpeg`，与上传时写入 MinIO 的 Content-Type 保持一致。
  - `toProfile`：email/role/nickname 原样透出，`avatarUrl` 为 null 时返回 null，否则格式化成代理路径 `/api/users/avatar/{objectKey}`。
  - `extension`：按 Content-Type 反推 `.png`/`.webp`/默认 `.jpg`。
- `backend/src/main/java/com/app/campusagent/domain/User.java`：为 `users` 表新增两个可空列。
  - `nickname`（`@Setter`，`@Column(length = 30)`）：昵称，展示时前端回退 email 前缀。
  - `avatarUrl`（`@Setter`，`@Column(name = "avatar_url", length = 512)`）：存 MinIO 对象键，经代理端点回显。
- `backend/src/main/java/com/app/campusagent/dto/AuthResponse.java`：record 从 `(token, email, role)` 扩为 `(token, email, role, nickname, avatarUrl)`，登录/注册响应直接带上资料，前端登录即可渲染头像与昵称。
- `backend/src/main/java/com/app/campusagent/dto/UpdateProfileRequest.java`（新增）：昵称更新请求 record `(String nickname)`，校验在 service 层完成。
- `backend/src/main/java/com/app/campusagent/dto/UserProfileResponse.java`（新增）：资料响应 record `(email, role, nickname, avatarUrl)`；avatarUrl 为代理路径、null 表示未上传。
- `backend/src/main/java/com/app/campusagent/service/AuthService.java`：register/adminRegister/login 三处构造 `AuthResponse` 改为调用新增的 `toAuthResponse(token, user)`，统一带上 `user.getNickname()` 和 `user.getAvatarUrl()`。
- `backend/src/main/java/com/app/campusagent/exception/ErrorCode.java`：新增 `NICKNAME_REQUIRED`（昵称不能为空）与 `NICKNAME_INVALID_LENGTH`（去除首尾空白后 1-30 字符）。
- `backend/src/main/java/com/app/campusagent/exception/GlobalExceptionHandler.java`：新增两个异常处理器。
  - `handleLostFoundDomain`：兜底处理 lostfound 包外控制器抛的 `LostFoundApiException`（头像回显代理就用它），输出 `{timestamp, status, code, error}`。
  - `handleBusiness`：处理 `BusinessException`（昵称/改密校验失败），统一 400 + `errorCode.code`。说明：lostfound 包内异常由高优先级的 `LostFoundExceptionHandler` 处理，这里只兜底。
- `backend/src/main/java/com/app/campusagent/config/SecurityConfig.java`：新增 `.requestMatchers("/api/users/avatar/**").permitAll()`。理由注释说明：`<img>` 标签不携带 JWT，头像必须公开回显；对象键是随机 UUID 不可枚举，故放行。`/api/users/me/**` 仍要求登录（默认规则）。

#### 后端——失物招领集成（我的报告）

- `backend/src/main/java/com/app/campusagent/lostfound/controller/LostFoundReportController.java`：`search` 端点新增 `@RequestParam(required = false) String owner`，透传给 service。非法值由 service 拒绝。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundReportService.java`：
  - `search` 签名新增 `String owner`，先 `resolveOwnerFilter(owner)` 得到 `mine` 布尔，再传入新重载的 `specification(..., mine, currentUser)`。
  - 新增 `resolveOwnerFilter`：owner 为 null/空白 → `false`（公开搜索）；为 `"me"` → `true`；其他任意值抛 `LostFoundApiException(422, INVALID_OWNER_FILTER)`。
  - 新增带 `mine`/`currentUser` 参数的 `specification` 重载：`mine=true` 时 predicate 改为 `createdBy.id == currentUser.id`（只查自己），并且**不再过滤 `adminHidden`**——用户要能看到自己被下架的报告；`mine=false` 时保持原有 `adminHidden = false` 公开过滤。
  - `toResponse` 新增 `report.isAdminHidden()` 字段，让前端据此显示下架标识。
- `backend/src/main/java/com/app/campusagent/lostfound/dto/LostFoundReportResponse.java`：record 新增 `boolean adminHidden`。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundImageRules.java`：为头像新增校验规则并复用尺寸检查。
  - 新增常量 `MAX_AVATAR_SIZE = 2MB`、`MAX_AVATAR_DIMENSION = 512`。
  - 新增 `validateAvatar`：空文件 → `EMPTY_IMAGE(422)`；>2MB → `AVATAR_TOO_LARGE(413)`；类型不在 JPEG/PNG/WebP 白名单或 magic bytes 不符 → `UNSUPPORTED_IMAGE_TYPE(415)`；尺寸校验走新参数化的 `validateImageDimensions(image, MAX_AVATAR_DIMENSION, "AVATAR_DIMENSION_TOO_LARGE")`。
  - 原 `validateImageDimensions(MultipartFile)` 重构为带 `maxDimension` 与 `errorCode` 参数，报告图片仍传 `MAX_IMAGE_DIMENSION`(8192) 与 `IMAGE_DIMENSION_TOO_LARGE`。
- `backend/src/main/java/com/app/campusagent/lostfound/storage/ObjectStorageService.java`：接口新增重载 `StoredObject upload(MultipartFile file, String objectKey)`，支持调用方指定对象键。
- `backend/src/main/java/com/app/campusagent/lostfound/storage/MinioObjectStorageService.java`：原 `upload(file)` 拆为默认路径实现——调 `upload(file, "lost-found/" + UUID.randomUUID() + extension)`；新重载 `upload(file, objectKey)` 接收显式对象键（头像用），内容逻辑不变。这样头像不再落入 `lost-found/` 前缀，且对象键可由服务层控制。
- `backend/src/main/java/com/app/campusagent/facilities/service/FacilitiesService.java`：`MAINTENANCE_TRANSITIONS` 中把 `SUBMITTED` 的可转移状态从 `{IN_PROGRESS, RESOLVED, CANCELLED}` 改为 `{IN_PROGRESS, CANCELLED}`——即维修申请不再允许从「已提交」直接跳到「已解决」，必须先进入 IN_PROGRESS。与本提交主题无直接关系，属顺带的状态机收紧。

#### 后端测试

- `backend/src/test/java/com/app/campusagent/controller/UserProfileControllerTest.java`（新增，116 行）：standalone MockMvc 测试，用 `AuthenticationPrincipalArgumentResolver` 解析 `@AuthenticationPrincipal`，并挂 `GlobalExceptionHandler` + `LostFoundExceptionHandler`。覆盖 `getProfile`、`updateNickname`、`uploadAvatar`（multipart）、`downloadAvatar` 返回字节与 Content-Type、以及 `downloadAvatar` 对非 `avatar-` 前缀键返回 404。
- `backend/src/test/java/com/app/campusagent/controller/UserProfileSecurityIntegrationTest.java`（新增，39 行）：`@SpringBootTest` 集成测试，验证未登录访问 `/api/users/me/profile`（GET/PUT）返回 401；公开回显端点对非 avatar 键返回 404 而非 401。
- `backend/src/test/java/com/app/campusagent/service/UserProfileServiceTest.java`（新增，121 行）：单元测试。覆盖昵称 trim 落库、空/空白/超长昵称拒绝且不 `save`、上传头像会删除旧对象、`toProfile` 派生代理 URL、无头像时 avatarUrl 为 null。
- `backend/src/test/java/com/app/campusagent/lostfound/service/LostFoundSearchIntegrationTest.java`：适配 `search` 新增的 `owner` 参数（补充 null/owner 传参），并新增 3 个用例：`owner=me` 只返回自己的报告且**包含被下架的**（`adminHidden=true`）、`owner=me` 可与 status 过滤组合（CLOSED 只留 closed）、非法 owner 抛 `INVALID_OWNER_FILTER`。
- `backend/src/test/java/com/app/campusagent/controller/AuthControllerTest.java`：因 `AuthResponse` 增加字段，把 mock 返回的 4 处构造调用补上 `null, null`。

#### 前端——API 与状态

- `frontend_web/src/api/users.ts`（新增，19 行）：封装 `getMyProfile()`（GET `/users/me/profile`）、`updateNickname(nickname)`（PUT `/users/me/profile`）、`uploadAvatar(file)`（FormData POST `/users/me/avatar`）。
- `frontend_web/src/types.ts`：`AuthResponse` 增加 `nickname`/`avatarUrl`；新增 `UserProfile` 接口 `(email, role, nickname, avatarUrl)`；`LostFoundReport` 增加 `adminHidden: boolean`。
- `frontend_web/src/auth/AuthContext.tsx`：
  - `SessionUser` 增加 `nickname`/`avatarUrl` 可空字段。
  - `storedUser()` 从 sessionStorage 读取时补默认值 `nickname ?? null`、`avatarUrl ?? null`，兼容旧存量的 session。
  - `authenticate` 登录/注册后把 `response.nickname ?? null`、`response.avatarUrl ?? null` 写入 session 并 setUser。
  - 新增 `updateProfile`（`useCallback`）：编辑昵称/头像后把新资料合并进 `SessionUser`、同步写回 sessionStorage，实现全站（顶部导航 + 个人中心）即时刷新。
- `frontend_web/src/auth/displayName.ts`（新增）：`displayName(nickname, email)`——有非空昵称用昵称，否则回退 `email.split('@')[0]`（再兜底 email 本身）。
- `frontend_web/src/components/UserAvatar.tsx`（新增，35 行）：头像组件。`name` 用于无头像时的首字母（`name.trim().charAt(0).toUpperCase() || '?'`）；`avatarUrl` 存在且 `broken` 为 false 时显示图片，`onError` 置 `broken` 回退首字母头像；`size` 控制宽高与字号。
- `frontend_web/src/components/AppShell.tsx`：顶部导航原来显示 `user?.email` 的纯文本，改为一个 `aria-label="Personal center"` 的按钮链接（`to="/lost-found/profile"`），内含 `<UserAvatar>`（28px）+ `displayName` 文本。即展示名/头像全站统一。
- `frontend_web/src/App.tsx`：`lazy` 引入 `ProfilePage`/`MyReportsPage`/`LostFoundFaqPage`，新增路由：
  - `/lost-found/profile` → `ProfilePage`
  - `/lost-found/profile/lost` → `MyReportsPage reportType="LOST"`
  - `/lost-found/profile/found` → `MyReportsPage reportType="FOUND"`
  - `/lost-found/faq` → `LostFoundFaqPage`
  - 均放在 `/lost-found/:reportId` 之前注册（React Router 按静态段优先匹配，顺序也合理）。
- `frontend_web/src/components/ReportCard.tsx`：新增 `showAdminHidden?: boolean` 属性；为 true 且 `report.adminHidden` 时，在卡片图片区左上角叠加红色 `<Chip label="Removed by admin">`。图片外层套 `<Box position="relative">` 作为定位容器。默认 false，公开搜索页行为不变。

#### 前端——页面

- `frontend_web/src/pages/ProfilePage.tsx`（新增，246 行）：个人中心主页。
  - 顶部资料卡：大号 `<Avatar>`（加载失败时隐藏图片保留首字母）+ 展示名 + 角色 Chip + email + 「Edit profile」按钮。
  - `My Services` 区：三个 `ServiceEntry` 卡片——My Claims（`/claims/mine`，副标题显示 total 与 pending review 数）、My Lost Items（`/lost-found/profile/lost`，显示 `owner=me` 搜索的 `totalElements`）、My Found Items（`/lost-found/profile/found`）。
  - `Other` 区：FAQ 入口（`/lost-found/faq`）。
  - 数据加载 `useEffect`（`active` 标志防卸载后 setState）：并行请求 `getMyProfile`（成功后 `setProfile` + `updateProfile` 同步全站）、`getMyClaims`、两次 `searchReports({owner:'me', ...})` 统计 lost/found 数量；各请求失败时兜底为 0。
  - `EditProfileDialog`：昵称输入（前后 trim、1-30 字符校验，`maxLength=30`）+ 头像上传（`selectFile` 前端先校验类型 JPEG/PNG/WebP 与 ≤2MB，并 `URL.createObjectURL` 做本地预览）。`save` 先 `updateNickname`，若有选文件再 `uploadAvatar`，成功后 `onSaved` 把新 profile 同步给 `updateProfile` 并关闭对话框。input 用 `accept` 限制类型，且 `e.target.value=''` 允许连续选同一文件。
- `frontend_web/src/pages/MyReportsPage.tsx`（新增，108 行）：我的失物/拾物列表页（`reportType` 由路由决定）。
  - 状态：`status`（ALL/OPEN/CLAIMED/CLOSED 芯片）、`page`、`result`、`loading`、`error`。
  - `load`（useCallback 依赖 reportType/status/page）：调 `searchReports({ owner:'me', reportType, status(undefined if ALL), page, size:20, sort:'createdAt,desc' })`。
  - 渲染：顶部「Back to personal center」+ 标题 + 新建报告按钮；状态芯片切换重置 `page`；有数据时 Grid 渲染 `ReportCard report={...} showAdminHidden`（展示下架徽标）+ 分页；空态显示 `No reports here yet` 与发布 CTA。副标题注明「含被管理员下架的」。
- `frontend_web/src/pages/LostFoundFaqPage.tsx`（新增，80 行）：中英双语 FAQ 静态页。`faqZh`/`faqEn` 各 11 条（发布、认领、审核、状态含义、图片限制、关闭/删除、下架说明等），`ToggleButtonGroup` 切换语言，`Accordion` 手风琴展示（`defaultExpanded` 首条）。「Back to personal center」链接。
- `frontend_web/src/pages/ReportsPage.tsx` / `ReportDetailPage.test.tsx` / `ReportsPage.test.tsx`：因 `LostFoundReport` 新增 `adminHidden` 字段，给 mock 数据补 `adminHidden: false`。
- `frontend_web/src/App.test.tsx`：管理端导航测试从「dashboard 卡片导航」改写为「Administration 侧边栏导航」（`screen.getByRole('link')` 替代 `within(main)`），因侧边栏布局变化。
- `frontend_web/src/App.profileRoutes.test.tsx`（新增，88 行）：mock 三个新页面后验证：已登录访问 `/lost-found/profile` 渲染个人中心、未登录重定向到登录页、`/lost-found/profile/lost` 渲染我的失物、`/lost-found/faq` 渲染 FAQ、顶部导航「Personal center」链接指向 `/lost-found/profile`。
- `frontend_web/src/pages/ProfilePage.test.tsx`、`MyReportsPage.test.tsx`、`LostFoundFaqPage.test.tsx`（均新增）：分别覆盖 ProfilePage 渲染/昵称回退/服务入口路由/计数/编辑昵称同步、MyReportsPage 的 owner=me 请求参数/下架徽标/状态过滤/空态 CTA、FAQ 默认中文与切英文。
- `frontend_web/src/admin/facilities/FacilitiesPage.tsx`：删除未使用的 `toggleSort` 函数（顺带清理，与本功能无逻辑关系）。

---

## 12. `3cff44d` Merge branch 'feature/lost-found-user-center'

- 时间：2026-08-16
- **主要作用**：把 feature 分支上的「个人中心」功能（即提交 11 `8d8b51f`）合入 main 的合并提交。它本身没有独立的新改动，承载的就是 `8d8b51f` 的那 42 个文件的改动（UserProfile 后端体系 + Profile/MyReports/FAQ 前端页面 + 全站展示名打通）。
- 改动规模：42 个文件，+1611/-36 行（等价于 `8d8b51f`）

### 逐行解释

- 合并内容与提交 `8d8b51f`（第 11 节）完全一致，逐行解释见第 11 节。此处不再重复。
- 合并引入的具体文件与 `8d8b51f` 相同：新增 `UserProfileController`/`UserProfileService`/`UserProfileResponse`/`UpdateProfileRequest`/`UserAvatar`/`displayName`/`ProfilePage`/`MyReportsPage`/`LostFoundFaqPage`/`api/users.ts`，以及 SecurityConfig 放行 `/api/users/avatar/**`、`AuthResponse` 增加 nickname/avatarUrl、报告搜索支持 `owner=me` 等。

---

## 13. `1505050` 修复颜色跨语言/同义词不一致

- 时间：2026-08-16
- **主要作用**：修复颜色在"跨语言/同义词"上不一致的 P0 问题——之前数据库 colour 字段存的是用户输入原文（如 `白色`、`White` 混存），搜索用原始 `lower(colour) like %input%`，中文值永远匹配不到英文查询、同义词（ivory/cream）也搜不到。本提交在 Python agent 与 Java 后端各建一份"canonical 颜色组"表并保持同步：匹配时归一为同一 canonical code，SQL 颜色过滤时扩展成同义表面形式的 OR。
- 改动规模：8 个文件，+465/-35 行

### 逐行解释

- `agent/lost_found_agent/lost_found_agent/matching.py`：新增颜色规范化模块。
  - 新增 `COLOUR_FORM_ASCII_PATTERN = re.compile(r"[a-z0-9]")`：用于区分"纯 ASCII 表面形式"（要词边界匹配）与"含 CJK 的形式"（子串匹配）。
  - 新增 `@dataclass(frozen=True) ColourGroup`：字段 `code`（canonical 标识）、`en`/`zh`（两种展示形式）、`forms`（全部同义表面形式）。注释点名与后端 `ColourNormalizer` 保持同步。
  - 新增 `COLOUR_GROUPS` 表（14 组）：WHITE（white/ivory/cream/白色/米白/乳白/纯白/象牙白/奶白）、BLACK（含 charcoal/纯黑/墨黑/乌黑）、GREY（grey/gray/银灰/浅灰/深灰）、BLUE（含 navy/dark blue/cyan/teal/azure/天蓝/藏蓝/宝蓝…）、RED（含 maroon/crimson/scarlet/酒红/枣红/朱红…）、GREEN（含 olive/emerald/jade/翠绿/墨绿…）、YELLOW（含 amber）、GOLD（gold/golden/金黄）、SILVER、PURPLE（含 violet/lavender）、PINK（含桃红/浅粉）、ORANGE（含橘色/桔色）、BROWN（含 tan/beige/bronze/咖啡色/卡其色/驼色）、TRANSPARENT（clear/透明/无色）。合并策略是"保守"：只合并跨语言+明确同义词；silver 与 grey、gold 与 yellow 保持分开避免近义误召回；单字中文（白/黑/蓝…）故意不收，防止子串命中"明白/黑板"等无关词。
  - 新增 `contains_colour_form(text, form)`：纯 ASCII 形式用正则 `(?<![a-z0-9])form(?![a-z0-9])` 词边界匹配（避免 "black" 命中 "backpack"、"red" 命中 "redemption"）；含 CJK 的形式直接 `form in text` 子串匹配。入参 text 需已 lowercase。
  - 新增 `colour_codes(value)`：把 value 命中命中的所有 canonical code 返回 `frozenset`；空值返回空集；复合色（"blue lid black bottle"）返回 `{"BLUE","BLACK"}` 多个。
  - 新增 `colour_similarity(left, right)`：两侧都命中 canonical 颜色时，按 code 集合判同色（交集非空→1.0，否则→0.0），实现 white↔白色=1.0、white vs black=0.0；任一侧未命中则回退旧 `short_text_similarity`，保留未知颜色/拼写变体的旧行为（如 white vs whitish 返回 0~1 之间）。
  - `_score_candidate_detailed` 中 colour 评分项由 `short_text_similarity(...)` 替换为 `colour_similarity(...)`，接上打分链路。
- `agent/lost_found_agent/lost_found_agent/rules.py`：把颜色逻辑收敛到 matching.py。
  - 顶部 import 从 `from .matching import rank_candidates` 扩展为导入 `COLOUR_FORM_ASCII_PATTERN / COLOUR_GROUPS / contains_colour_form / rank_candidates`。
  - 删除旧的 `COLOURS` 字典（原来只有 11 种基础色的中英文对、无同义词、无词边界保护），由 `COLOUR_GROUPS` 取代。
  - 新增 `extract_colour(message)`：遍历 `COLOUR_GROUPS` 各组表面形式，首个命中即返回该组展示形式——命中英文形式返回 `group.en`（如 ivory→"White"）、命中中文形式返回 `group.zh`（如 乳白→"白色"），尽量保持用户输入语言；"black" 不会误命中 "backpack"。
  - `extract_fields` 中提取 colour 的分支由"遍历旧 COLOURS 做子串匹配"改为调用 `extract_colour(message)`，非空才写入 `fields["colour"]`。
- `agent/lost_found_agent/tests/test_matching.py`：新增 `colour_codes` 跨语言/同义词用例（white/White/白色/ivory/cream→WHITE、黑色→BLACK、gray→GREY、navy→BLUE、golden→GOLD；复合色多 code；backpack/redemption/空串不命中）；`colour_similarity` 同色 1.0 / 异色 0.0 / 空值；以及未知值回退 `short_text_similarity` 的行为。
- `agent/lost_found_agent/tests/test_rules.py`：新增 `extract_colour` 两组测试——保留输入语言（我丢了白色水杯→白色、米白水杯→白色、colour: black→Black、a white backpack→White、ivory phone case→White、navy blue jacket→Blue）；词边界防误命中（backpack/redemption/空串返回 None、black backpack 先命中 Black）。
- `backend/src/main/java/com/app/campusagent/lostfound/colour/ColourNormalizer.java`（新增，+99 行）：Python `COLOUR_GROUPS` 的 Java 镜像，注释明确要求两端同步。
  - `GROUPS`：`LinkedHashMap<String, List<String>>`，key 为 canonical code，value 为该组全部小写表面形式，与 Python 表逐组一致（含同样 14 组、同样的保守合并注释）。
  - `canonicalCodes(value)`：空/null 返回空集，否则 `trim().toLowerCase(Locale.ROOT)` 后筛出命中的 code 集合（对应 Python `colour_codes`）。
  - `expand(value)`：输入命中 canonical 表时返回该组全部表面形式列表（供 SQL OR 扩展，使 white 能命中库里的 白色/ivory/cream）；未命中返回空 list，调用方回退原始 LIKE。
  - `containsForm(text, form)`：纯 ASCII（`form.chars().allMatch(ch -> ch < 128)`）用 `(?<![a-z0-9])...(?![a-z0-9])` 词边界正则；否则 `text.contains(form)` 子串。与 Python `contains_colour_form` 一一对应。
- `backend/src/main/java/com/app/campusagent/lostfound/service/LostFoundReportService.java`：`search` 的 colour 过滤分支改写。原为 `predicates.add(builder.like(lower(colour), likePattern(colour)))`；新逻辑先 `ColourNormalizer.expand(colour)`——为空则回退原始 LIKE（未知颜色保旧行为），非空则 `builder.or(...)` 对该组所有表面形式逐一 LIKE 并 OR 合并，使 `white` 可命中 `白色`/`ivory`/`cream` 的候选，修复 P0 跨语言/同义词不一致。
- `backend/src/test/java/com/app/campusagent/lostfound/colour/ColourNormalizerTest.java`（新增，+48 行）：断言 `canonicalCodes` 跨语言/同义词归一、复合色多 code、词边界防误命中（backpack/redemption/空/null）、`expand` 返回同组全部同义词以及未知/空值返回空。
- `backend/src/test/java/com/app/campusagent/lostfound/service/LostFoundSearchIntegrationTest.java`：新增两个集成测试。`colourFilterExpandsToCrossLanguageAndSynonymValues` 落库 colour='白色' 与 colour='Black' 两条记录，验证搜索 "white" 命中白色候选、"白色" 反向命中 White 候选、"black" 只命中 Black 候选；`unknownColourFallsBackToSubstringLike` 用 colour='multicolour' 验证未知颜色 "colour" 回退原始 LIKE 仍能命中。

---

## 14. `e6e2d24` Merge branch 'feature/lost-found-user-center'

- 时间：2026-08-16
- **主要作用**：把 feature 分支上的「颜色跨语言/同义词修复」（即提交 13 `1505050`）合入 main 的合并提交。承载的就是 `1505050` 的那 8 个文件的改动（Python 颜色组 + Java `ColourNormalizer` + 搜索扩展）。
- 改动规模：8 个文件，+465/-35 行（等价于 `1505050`）

### 逐行解释

- 合并内容与提交 `1505050`（第 13 节）完全一致，逐行解释见第 13 节。此处不再重复。

---

## 15. `6d040eb` 实现修改密码功能

- 时间：2026-08-17
- **主要作用**：在个人中心新增「修改密码」能力。后端 `PUT /api/users/me/password` 校验当前密码（BCrypt matches）、新密码长度（6-64 字符）并重哈希落库；前端 ProfilePage 增加 `ChangePasswordDialog` 表单（当前密码/新密码/确认新密码），错误码映射为友好英文提示。
- 改动规模：10 个文件，+338/-7 行

### 逐行解释

- `backend/src/main/java/com/app/campusagent/controller/UserProfileController.java`：新增 `changePassword` 端点——`@PutMapping("/me/password")`，收 `ChangePasswordRequest` 与 `@AuthenticationPrincipal User`，返回 void（200 即成功）。
- `backend/src/main/java/com/app/campusagent/dto/ChangePasswordRequest.java`（新增）：record `(String currentPassword, String newPassword)`，校验在 service 层。
- `backend/src/main/java/com/app/campusagent/exception/ErrorCode.java`：新增 4 个错误码——`PASSWORD_REQUIRED`、`PASSWORD_INVALID_LENGTH`（6-64 字符，注释注明与注册最小长度一致）、`PASSWORD_CURRENT_INCORRECT`、`PASSWORD_SAME_AS_CURRENT`。
- `backend/src/main/java/com/app/campusagent/service/UserProfileService.java`：
  - 注入 `PasswordEncoder`（构造器加入第三个参数）。
  - 常量 `PASSWORD_MIN_LENGTH = 6`、`PASSWORD_MAX_LENGTH = 64`（64 为 BCrypt 72 字节上限内的安全取值）。
  - 新增 `changePassword`（`@Transactional`），顺序：
    1. request 为 null 或 current/new 任一侧空白（trim 判空）→ `PASSWORD_REQUIRED`。
    2. 新密码长度 <6 或 >64（按 `String.length()` 字符数）→ `PASSWORD_INVALID_LENGTH`。
    3. `!passwordEncoder.matches(currentPassword, currentUser.getPassword())` → `PASSWORD_CURRENT_INCORRECT`。
    4. `currentPassword.equals(newPassword)` → `PASSWORD_SAME_AS_CURRENT`。
    5. `setPassword(passwordEncoder.encode(newPassword))` + `userRepository.save`。
  - 新增 `isBlank` 私有静态方法（null 或 trim 后空）。注释明确：密码本身不做 trim（空格可能是有效字符），只有判空用 trim。
- `backend/src/test/java/com/app/campusagent/controller/UserProfileControllerTest.java`：新增 `changePasswordParsesBodyAndReturnsOk`——PUT JSON 到 `/api/users/me/password` 期望 200，并用 `argThat` 断言 service 收到解析后的 `ChangePasswordRequest` 字段。
- `backend/src/test/java/com/app/campusagent/service/UserProfileServiceTest.java`：新增 5 个用例：正确改密并落库（验证新 hash 与 save）、空/null 字段拒绝、长度非法拒绝（5 字符、65 字符）、当前密码错误拒绝、新旧相同拒绝；均断言不触发 `save`。
- `backend/test-api.http`：新增修改密码的 HTTP 请求示例（占位 `<token>`，演示 currentPassword/newPassword 体）。
- `frontend_web/src/api/users.ts`：新增 `changePassword(currentPassword, newPassword)` → `PUT /users/me/password`。
- `frontend_web/src/pages/ProfilePage.tsx`：
  - 引入 `LockOutlinedIcon`、`axios`、`changePassword`、`ApiErrorBody`。
  - 常量 `passwordMinLength=6`/`passwordMaxLength=64`；`passwordErrorMessages` 把后端 4 个 `PASSWORD_*` 错误码映射成英文提示；`passwordErrorMessage` 先用 `axios.isAxiosError` + `error.response.data.code` 查表，查不到回退 `apiErrorMessage`。
  - 资料卡按钮区从单个 Edit 按钮改为 `Stack`，新增「Change password」按钮（`setPasswordOpen(true)`）。
  - 新增 `ChangePasswordDialog` 组件：三个密码框（current/new/confirm，`autoComplete` 分别 current-password/new-password）、`save()` 流程——前端先查空、长度（6-64）、两次新密码一致，再调 `changePassword`，成功显示 success Alert 并清空三个输入；失败显示 `passwordErrorMessage`。打开对话框时 `useEffect` 重置全部状态。关闭按钮在 submitting 时禁用。
- `frontend_web/src/pages/ProfilePage.test.tsx`：mock 列表加入 `changePassword`，新增 3 个用例：成功改密显示 success 提示并断言调用参数；当前密码错误显示对应英文提示；确认密码不一致时前端拦截且不调 API。

---

## 16. `a01be05` feat(user-center): 修改密码安全优化——旧 JWT 失效、BCrypt 72 字节校验、统一密码规则

- 时间：2026-08-17
- **主要作用**：对上一提交的改密功能做三点安全加固：① 改密后立即使改密前签发的旧 JWT 失效（否则 token 泄露后最长还能用满 24 小时）；② 密码长度上限从「按字符数 64」改为「按 UTF-8 字节 ≤72」，规避 BCrypt 对超 72 字节密码的静默截断导致不同密码等价的问题；③ 把注册/管理员建号/改密/前端提示统一到同一套 `PasswordRules` 规则。前端改密成功后主动登出并引导重新登录。
- 改动规模：20 个文件，+368/-29 行

### 逐行解释

#### 后端——旧 JWT 失效

- `backend/src/main/java/com/app/campusagent/domain/User.java`：新增可空列 `passwordChangedAt`（`@Setter`，`@Column(name="password_changed_at")`），记录最近改密时间，供过滤器做旧 token 判定。
- `backend/src/main/java/com/app/campusagent/config/JwtTokenProvider.java`：新增 `getIssuedAtFromToken(String token)`，返回 `parseClaims(token).getIssuedAt()`（JWT 的 iat），供过滤器比较。
- `backend/src/main/java/com/app/campusagent/config/JwtAuthFilter.java`：放行条件从 `user != null && role != null` 增加 `&& tokenIssuedAfterPasswordChange(user, token)`。
  - 新增 `tokenIssuedAfterPasswordChange` 逻辑：`passwordChangedAt == null`（没改过密码）→ 放行；`issuedAt == null`（无 iat 的旧 token）→ 放行保持原行为；否则把 `passwordChangedAt.truncatedTo(ChronoUnit.SECONDS)` 对齐到秒（因为 JWT iat 是秒级 NumericDate 而 passwordChangedAt 是微秒级），转成系统时区 Instant 后比较——`changed > issuedAt` 说明 token 签发早于改密，返回 false 拒绝；否则放行。注释说明取舍：对齐到秒避免同一秒内新 token 被毫秒差误拒，代价是改密前同一秒内签发的 token 最多残留 1 秒有效，相比原 24 小时窗口可忽略。
- `backend/src/main/java/com/app/campusagent/service/UserProfileService.java`：`changePassword` 在 `setPassword(...)` 后追加 `setPasswordChangedAt(LocalDateTime.now())`，再 `save`；长度校验从手写的 6-64 字符常量改为复用 `PasswordRules.isValidLength(newPassword)`（删除原来的 `PASSWORD_MIN_LENGTH`/`PASSWORD_MAX_LENGTH` 常量）。

#### 后端——统一密码规则 + BCrypt 72 字节

- `backend/src/main/java/com/app/campusagent/util/PasswordRules.java`（新增，36 行）：统一校验工具。
  - `MIN_LENGTH = 6`（字符数，与既有注册约束一致）；`MAX_UTF8_BYTES = 72`（BCrypt 硬截断上限）。
  - `isValidLength(password)`：非 null 且 `length() >= 6` 且 `utf8Bytes <= 72`。Javadoc 说明关键动机：若只按字符数限制，中文/emoji 等多字节字符可绕过 72 字节上限，BCrypt 截断后不同密码可能等价。
  - `utf8Bytes(value)`：`value.getBytes(StandardCharsets.UTF_8).length`。
- `backend/src/main/java/com/app/campusagent/validation/ValidPassword.java`（新增）：Bean Validation 注解，`@Constraint(validatedBy = PasswordValidator.class)`，`@Target` 含 `RECORD_COMPONENT`（覆盖 register/admin register record DTO），`message` 默认「密码须为 6 个字符以上，且不超过 72 字节」。
- `backend/src/main/java/com/app/campusagent/validation/PasswordValidator.java`（新增）：实现 `ConstraintValidator<ValidPassword, String>`，`isValid` 直接委托 `PasswordRules.isValidLength`。
- `backend/src/main/java/com/app/campusagent/dto/RegisterRequest.java`：密码注解从 `@NotBlank @Size(min = 6)` 改为 `@NotBlank @ValidPassword`。
- `backend/src/main/java/com/app/campusagent/dto/AdminRegisterRequest.java`：同样把 `@NotBlank @Size(min = 6)` 换成 `@NotBlank @ValidPassword`。
- `backend/src/main/java/com/app/campusagent/exception/ErrorCode.java`：`PASSWORD_INVALID_LENGTH` 文案从「6-64 个字符」改为「6 个字符以上，且不超过 72 字节」。

#### 后端测试

- `backend/src/test/java/com/app/campusagent/controller/AuthControllerTest.java`：新增 `shouldReturn400WhenPasswordExceedsUtf8ByteLimit`——25 个中文字符（75 字节）注册返回 400；注释说明旧实现按字符数 6-64 会放行这类密码导致 BCrypt 截断。
- `backend/src/test/java/com/app/campusagent/controller/UserProfileSecurityIntegrationTest.java`：
  - `@AfterEach` 删除测试创建的 `createdUser` 清理数据库；注入 `UserRepository`/`JwtTokenProvider`。
  - `rejectsUnauthenticatedProfileRequests` 追加未登录 PUT `/me/password` 期望 401。
  - 新增 `rejectsTokenIssuedBeforePasswordChange`：创建用户 → 改密前签发 oldToken → `Thread.sleep(1100)` 确保旧 token 落在改密时间所在秒之前（规避 ≤1s 残留窗口）→ 设 `passwordChangedAt` → 用 oldToken 请求 profile 期望 401；再签发 newToken 请求期望 200。
- `backend/src/test/java/com/app/campusagent/service/UserProfileServiceTest.java`：
  - 成功改密断言追加 `user.getPasswordChangedAt()` 非 null。
  - 长度拒绝用例改为「73 个 ASCII 字节」与「25 个中文字符（75 字节）」。
  - 新增 `changePasswordAcceptsUpTo72Utf8Bytes`：65 个 ASCII 字符（旧 64 字符上限会拒）与 24 个中文字符（恰 72 字节）都放行，断言 `save` 调用 2 次、`passwordChangedAt` 非 null。

#### 前端

- `frontend_web/src/lib/passwordRules.ts`（新增，17 行）：与后端 PasswordRules 对齐的前端副本——`PASSWORD_MIN_LENGTH = 6`、`PASSWORD_MAX_BYTES = 72`、`utf8ByteLength`（用 `TextEncoder().encode(value).length` 数 UTF-8 字节）、`isPasswordLengthValid`。
- `frontend_web/src/lib/passwordRules.test.ts`（新增）：断言常量与后端一致（6/72）、多字节计数（65/72/75 字节）、长度校验边界。
- `frontend_web/src/pages/LoginPage.tsx`：`handleSubmit` 在注册模式（`mode === 'register'`）且 `!isPasswordLengthValid(password)` 时，前端直接报「at least 6 characters and at most 72 bytes」并 return，不再发请求。
- `frontend_web/src/pages/LoginPage.test.tsx`：新增「25 个中文字符注册被前端拦截」用例。
- `frontend_web/src/admin/users/AdminUserManagementPage.tsx`：`handleCreate` 中创建用户密码校验从 `length < 6` 改为 `!isPasswordLengthValid(createPassword)`，错误文案用常量拼「至少 6 位，且不超过 72 字节」。
- `frontend_web/src/pages/ProfilePage.tsx`：
  - 删除本地 `passwordMinLength/passwordMaxLength`，改用 `lib/passwordRules` 常量；`passwordErrorMessages` 的 `PASSWORD_INVALID_LENGTH` 文案同步为「at least 6 characters and at most 72 bytes」。
  - `ChangePasswordDialog` 引入 `useAuth().logout` 与 `useNavigate`。
  - `save()` 增加「新密码与当前密码相同」前端拦截；长度校验改用 `isPasswordLengthValid`；helperText 改为「At least 6 characters, max 72 bytes」。
  - 新增 `handleClose()`：submitting 时忽略；若已 success——因旧 JWT 已失效，调用 `logout()` 清 session 并 `navigate('/login', {replace:true})`；否则普通 `onClose`。Dialog 的 `onClose` 从内联改为 `handleClose`；DialogActions 在 success 时只显示「Log in」按钮（点击走 `handleClose` 登出跳转），否则显示 Cancel/Update；success Alert 文案改为「Password updated. Please log in again with your new password.」。
- `frontend_web/src/pages/ProfilePage.test.tsx`：新增 3 个用例——新密码=当前密码被前端拦截且不调 API、>72 字节（25 个中文）被拦截、改密成功后点「Log in」清空 `sessionStorage` 的 token 与 user（验证登出）。

---

## 17. `0e6ad56` Merge branch 'feature/lost-found-user-center'

- 时间：2026-08-17
- **主要作用**：把 feature 分支上的「修改密码 + 安全优化」（即提交 15 `6d040eb` 与提交 16 `a01be05`）合入 main 的合并提交。承载的就是这两个提交的改动（改密端点/表单 + 旧 JWT 失效 + BCrypt 72 字节 + 统一密码规则）。
- 改动规模：25 个文件，+686/-16 行（等价于 `6d040eb` + `a01be05`）

### 逐行解释

- 合并内容与提交 `6d040eb`（第 15 节）和 `a01be05`（第 16 节）完全一致，逐行解释见对应章节。此处不再重复。
- 合并引入的关键点：`User.passwordChangedAt` 列、`JwtAuthFilter.tokenIssuedAfterPasswordChange`、`JwtTokenProvider.getIssuedAtFromToken`、`PasswordRules`/`ValidPassword`/`PasswordValidator`、前端 `lib/passwordRules.ts`、ProfilePage 改密对话框 + 登出引导。

---

## 18. `619fd6f` fix(lost-found): 发送成功后清空暂存图片，避免残留在输入框

- 时间：2026-08-17
- **主要作用**：修复 LostFoundAgentPanel（L&F 对话面板）的一个交互 bug——发送消息后，上传到暂存区的图片没有清空，会一直残留在输入框下方的暂存区。改动在发送成功后调用 `setStagedImages([])`。
- 改动规模：2 个文件，+29/-0 行

### 逐行解释

- `frontend_web/src/components/LostFoundAgentPanel.tsx`（+3 行）：在发送逻辑的 `try` 分支中，`invoke({ ..., conversationContext: { sessionId, sharedData }, images: stagedImages })` 调用成功之后、`catch` 之前，插入 `setStagedImages([])`。代码注释解释意图：图片已随本轮发送（消息气泡 + `shared_context` 都带图），清空面板暂存，避免发送成功后图片仍残留在输入框下方的暂存区；后续轮次可通过 `shared_data` 沿用这些图。因为放在成功路径（`catch` 之外），发送失败时暂存图片保留，用户可重试。
- `frontend_web/src/components/LostFoundAgentPanel.test.tsx`（+26 行）：新增回归测试 `clears staged images after a successful send so they do not linger in the input box`：
  1. mock `uploadAgentImage` 返回一个 staging 图片；
  2. 通过文件输入 `fireEvent.change` 选择 `a.png`，断言暂存区出现 `<img alt="a.png">` 与「Remove a.png」删除按钮；
  3. 输入文本并点「Send」；
  4. `waitFor` 断言 `invoke` 被调用一次；
  5. 关键断言：消息气泡仍渲染 `<img alt="a.png">`（因为消息里确实带图），但「Remove a.png」暂存删除按钮已消失（注释明确说明用删除按钮的消失来判定暂存区被清空，而非依赖图片本身），证明 `setStagedImages([])` 生效。

---

## 19. `e5b7547` Merge branch 'feature/lost-found-user-center'

- 时间：2026-08-17
- **主要作用**：把 feature 分支上的「发送成功后清空暂存图片」修复（即提交 18 `619fd6f`）合入 main 的合并提交。承载的就是 `619fd6f` 那 2 个文件的改动。
- 改动规模：2 个文件，+29/-0 行（等价于 `619fd6f`）

### 逐行解释

- 合并内容与提交 `619fd6f`（第 18 节）完全一致，逐行解释见第 18 节。此处不再重复。

---

## 附：工作线小结

1. **失物招领管理后台**（08-10）：`f810823`（后端管理闭环：下架/恢复/删除、审计、认领审核收归管理员、站内通知、视觉指纹回填）→ `fa3cc44`（前端管理台 + 报告 owner 编辑）→ `94511f8`（认领安全集成测试）。
2. **Agent 匹配体系**（08-10 ~ 08-11）：`4d324ec`（确定性 Embedding + 视觉指纹 + 评估工具）→ `b20ac26`（类别智能填写）→ `25437b2`（图片代理回显修复）→ `ccd1c38`（Agent 图片上传 + 纯图搜索修复）→ `c01bf95`（Browse 以图搜物）。
3. **CI/收尾**（08-11 ~ 08-12）：`328e76c`、`57f3e7e`（静态检查与类型加固清理）。
4. **颜色归一**（08-16）：`1505050`（Python/Java 双份 canonical 颜色表 + SQL 同义扩展）。
5. **用户中心**（08-16 ~ 08-17）：`8d8b51f`（个人中心 + 头像/昵称 + 我的报告 + FAQ）→ `6d040eb`（修改密码）→ `a01be05`（旧 JWT 失效 + BCrypt 72 字节 + 统一密码规则）→ `619fd6f`（对话面板暂存图清空修复）。
6. 4 个 merge 提交（`3cff44d`/`e6e2d24`/`0e6ad56`/`e5b7547`）为把 feature 分支各阶段合入 main 的节点，各自承载对应功能提交。
