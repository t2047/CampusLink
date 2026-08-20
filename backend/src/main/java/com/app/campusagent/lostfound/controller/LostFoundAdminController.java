/**
 * 失物招领后台管理控制器（REST 控制器）。
 *
 * 主要作用与职责：
 *  1. 面向 ADMIN / SUPER_ADMIN 管理角色，是后台管理面板所调用的一族 REST 端点，
 *     用于对失物招领模块进行运营管理，不暴露给普通用户。
 *  2. 端点覆盖范围：
 *      - GET  /overview                          模块整体运营概况（各状态统计）
 *      - GET  /reports                           分页检索招领/寻物报告（含管理端隐藏过滤）
 *      - POST /reports/{reportId}/delist         将报告下架（对用户隐藏）
 *      - POST /reports/{reportId}/restore        恢复被下架的报告
 *      - POST /reports/{reportId}/delete         物理删除报告（需登记操作原因）
 *      - GET  /audit-logs                        查询管理操作审计日志
 *      - GET  /claims                            分页检索认领记录
 *      - GET  /claims/{claimId}                  认领记录详情
 *      - POST /claims/{claimId}/approve          管理端通过认领
 *      - POST /claims/{claimId}/reject           管理端驳回认领
 *  3. 本类只做 HTTP 层适配（参数绑定、分页/排序参数校验、透传当前登录用户），
 *     所有业务逻辑均委托给 LostFoundAdminService。
 *  4. 全类通过 @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')") 做角色级权限控制。
 */
package com.app.campusagent.lostfound.controller;

// —— 领域模型与枚举（当前登录用户、认领状态、类别、审计动作、报告状态/类型）——
import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundAuditAction;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
// —— DTO：通用分页响应 + 管理端各场景的请求/响应对象 ——
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.dto.admin.AdminAuditLogResponse;
import com.app.campusagent.lostfound.dto.admin.AdminClaimDecisionRequest;
import com.app.campusagent.lostfound.dto.admin.AdminClaimDetailResponse;
import com.app.campusagent.lostfound.dto.admin.AdminClaimSummaryResponse;
import com.app.campusagent.lostfound.dto.admin.AdminLostFoundOverviewResponse;
import com.app.campusagent.lostfound.dto.admin.AdminLostFoundReportResponse;
import com.app.campusagent.lostfound.dto.admin.AdminReportActionRequest;
// —— 模块自定义异常与管理端业务服务 ——
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.service.LostFoundAdminService;
// —— 校验与 Spring Data 分页/排序 ——
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
// —— Spring Security：方法级角色控制、当前登录用户注入 ——
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
// —— Web MVC 绑定注解 ——
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
// —— JDK 工具类 ——
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

// 标注为 REST 控制器，所有端点统一以 /api/admin/lost-found 作为前缀
@RestController
@RequestMapping("/api/admin/lost-found")
// 类级权限控制：只有 ADMIN 或 SUPER_ADMIN 角色可访问本控制器的所有端点
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class LostFoundAdminController {

    // 报告列表允许的排序字段白名单：防止外部传入任意 JPA 属性路径造成注入或报错
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "updatedAt", "eventDate", "itemName");

    // 审计日志允许的排序字段白名单：审计日志只允许按创建时间排序
    private static final Set<String> ALLOWED_AUDIT_SORT_FIELDS = Set.of("createdAt");

    /** 认领列表排序字段 → JPA 属性路径（跨 join 的字段映射到嵌套属性）。 */
    // 前端传的排序字段名到真实 JPA 路径的映射：
    // 诸如 itemName / eventDate 位于关联的 report 实体上，claimantEmail / reportOwnerEmail 在关联的用户实体上，
    // 因此需显式映射到嵌套属性路径，Sort 才能正确跨 join 排序
    private static final Map<String, String> CLAIM_SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "updatedAt", "updatedAt",
            "status", "status",
            "itemName", "report.itemName",
            "eventDate", "report.eventDate",
            "claimantEmail", "claimant.email",
            "reportOwnerEmail", "report.createdBy.email");

    // 管理端业务服务：报告/认领的检索、下架、审批、审计等具体实现
    private final LostFoundAdminService adminService;

    // 构造器注入管理服务（Spring 构造器依赖注入）
    public LostFoundAdminController(LostFoundAdminService adminService) {
        this.adminService = adminService;
    }

    /**
     * GET /api/admin/lost-found/overview
     * 获取失物招领模块的整体运营概况（各状态报告数、认领数等统计指标）。
     * 无入参，由后台管理面板首页调用，返回管理端专用概况响应体。
     */
    @GetMapping("/overview")
    public AdminLostFoundOverviewResponse overview() {
        // 直接委托 Service 聚合统计并返回
        return adminService.overview();
    }

    /**
     * GET /api/admin/lost-found/reports
     * 管理端分页检索招领/寻物报告，支持多条件组合筛选（比用户端多了 adminHidden 过滤）。
     * 由后台管理面板的"报告管理"页调用，返回分页报告列表。
     */
    @GetMapping("/reports")
    public PageResponse<AdminLostFoundReportResponse> search(
            @RequestParam(required = false) ReportType reportType,    // 报告类型（LOST/FOUND），不传则不限
            @RequestParam(required = false) String keyword,           // 关键词，对名称/描述等做模糊匹配
            @RequestParam(required = false) ItemCategory category,    // 按物品种类筛选
            @RequestParam(required = false) String colour,            // 按物品颜色筛选
            @RequestParam(required = false) String location,          // 按丢失/捡到地点筛选
            @RequestParam(required = false) LocalDate dateFrom,       // 事件日期范围下界（含当天）
            @RequestParam(required = false) LocalDate dateTo,         // 事件日期范围上界（含当天）
            @RequestParam(required = false) ReportStatus status,      // 按报告状态筛选（OPEN/CLOSED 等）
            @RequestParam(required = false) Boolean adminHidden,      // 是否按"管理端隐藏"标记过滤
            @RequestParam(defaultValue = "0") int page,               // 页码，从 0 开始
            @RequestParam(defaultValue = "25") int size,              // 每页条数
            @RequestParam(defaultValue = "createdAt,desc") String sort) { // 排序："字段,asc|desc"
        // 把查询参数连同校验后的分页/排序对象一并交给 Service 执行组合查询
        return adminService.search(
                reportType,
                keyword,
                category,
                colour,
                location,
                dateFrom,
                dateTo,
                status,
                adminHidden,
                pageable(page, size, sort));
    }

    /**
     * POST /api/admin/lost-found/reports/{reportId}/delist
     * 将指定报告下架（对用户隐藏，非物理删除），需携带操作原因以便审计追溯。
     * 入参：reportId 路径参数指定报告；request 内含 reason 操作原因；
     *       currentUser 为当前登录的管理员，用于记录审计人。
     * 返回：下架后的报告信息。
     * 调用方：后台管理面板"报告管理"页的"下架"按钮。
     */
    @PostMapping("/reports/{reportId}/delist")
    public AdminLostFoundReportResponse delist(
            @PathVariable Long reportId,                    // 要下架的报告 id（路径参数）
            @Valid @RequestBody AdminReportActionRequest request, // 请求体，校验后必含 reason 原因
            @AuthenticationPrincipal User currentUser) {    // 当前登录用户（审计操作人）
        return adminService.delist(reportId, request.reason(), currentUser);
    }

    /**
     * POST /api/admin/lost-found/reports/{reportId}/restore
     * 恢复一条此前被下架的报告，使其重新对用户可见。
     * 入参与 delist 一致（reportId + reason + currentUser），返回恢复后的报告信息。
     */
    @PostMapping("/reports/{reportId}/restore")
    public AdminLostFoundReportResponse restore(
            @PathVariable Long reportId,                    // 要恢复的报告 id
            @Valid @RequestBody AdminReportActionRequest request, // 操作原因（审计用）
            @AuthenticationPrincipal User currentUser) {    // 当前登录用户（审计操作人）
        return adminService.restore(reportId, request.reason(), currentUser);
    }

    /**
     * POST /api/admin/lost-found/reports/{reportId}/delete
     * 物理删除一条报告（管理端不可逆操作），删除前必须登记操作原因。
     * 入参：reportId 报告 id；request.reason() 删除原因；currentUser 操作人。
     * 返回：204 No Content（无响应体），表示删除成功。
     */
    @PostMapping("/reports/{reportId}/delete")
    public ResponseEntity<Void> delete(
            @PathVariable Long reportId,                    // 要删除的报告 id
            @Valid @RequestBody AdminReportActionRequest request, // 删除原因（必填）
            @AuthenticationPrincipal User currentUser) {    // 当前登录用户（审计操作人）
        // 执行物理删除，删除原因会写入审计日志
        adminService.deleteReport(reportId, request.reason(), currentUser);
        // 删除成功返回 204 No Content
        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/admin/lost-found/audit-logs
     * 分页查询管理操作审计日志，可按下架/恢复/删除/审批等动作及操作人筛选。
     * 返回：PageResponse<AdminAuditLogResponse> 分页审计日志列表。
     * 调用方：后台管理面板"审计日志"页。
     */
    @GetMapping("/audit-logs")
    public PageResponse<AdminAuditLogResponse> auditLogs(
            @RequestParam(required = false) Long reportId,            // 按关联报告 id 筛选
            @RequestParam(required = false) LostFoundAuditAction action, // 按审计动作类型筛选
            @RequestParam(required = false) String actorEmail,        // 按操作人邮箱筛选
            @RequestParam(required = false) String keyword,           // 关键词模糊搜索（原因等）
            @RequestParam(defaultValue = "0") int page,               // 页码，从 0 开始
            @RequestParam(defaultValue = "25") int size,              // 每页条数
            @RequestParam(defaultValue = "createdAt,desc") String sort) { // 排序（仅允许 createdAt）
        return adminService.auditLogs(
                reportId,
                action,
                actorEmail,
                keyword,
                auditPageable(page, size, sort));
    }

    /**
     * GET /api/admin/lost-found/claims
     * 管理端分页检索认领记录，支持按状态、关键词、报告、双方邮箱等条件组合筛选。
     * 返回：PageResponse<AdminClaimSummaryResponse> 分页认领摘要列表。
     * 调用方：后台管理面板"认领管理"页。
     */
    @GetMapping("/claims")
    public PageResponse<AdminClaimSummaryResponse> claims(
            @RequestParam(required = false) ClaimStatus status,       // 按认领状态筛选（PENDING/APPROVED 等）
            @RequestParam(required = false) String keyword,           // 关键词模糊搜索
            @RequestParam(required = false) Long reportId,            // 按关联报告 id 筛选
            @RequestParam(required = false) String claimantEmail,     // 按认领人邮箱筛选
            @RequestParam(required = false) String reportOwnerEmail,  // 按报告发布人邮箱筛选
            @RequestParam(required = false) Boolean adminHidden,      // 是否按管理端隐藏过滤
            @RequestParam(defaultValue = "0") int page,               // 页码，从 0 开始
            @RequestParam(defaultValue = "25") int size,              // 每页条数
            @RequestParam(defaultValue = "createdAt,desc") String sort) { // 排序（字段经 CLAIM_SORT_FIELDS 映射）
        return adminService.searchClaims(
                status,
                keyword,
                reportId,
                claimantEmail,
                reportOwnerEmail,
                adminHidden,
                claimsPageable(page, size, sort));
    }

    /**
     * GET /api/admin/lost-found/claims/{claimId}
     * 获取单条认领记录的完整详情（含报告信息、双方用户信息、图片等）。
     * 入参：claimId 认领记录 id；返回认领详情响应体。
     * 调用方：后台管理面板认领列表点击某条记录后加载详情。
     */
    @GetMapping("/claims/{claimId}")
    public AdminClaimDetailResponse claim(@PathVariable Long claimId) {
        return adminService.getClaimDetail(claimId);
    }

    /**
     * POST /api/admin/lost-found/claims/{claimId}/approve
     * 管理端通过一条认领申请。可附带决定备注，操作会写入审计日志并触发通知。
     * 入参：claimId 认领 id；request.decisionNote() 审批备注；currentUser 审批人。
     * 返回：审批后的认领详情。
     */
    @PostMapping("/claims/{claimId}/approve")
    public AdminClaimDetailResponse approve(
            @PathVariable Long claimId,                 // 要审批的认领 id
            @Valid @RequestBody AdminClaimDecisionRequest request, // 审批决定（含备注）
            @AuthenticationPrincipal User currentUser) { // 当前审批的管理员
        return adminService.approveClaim(claimId, request.decisionNote(), currentUser);
    }

    /**
     * POST /api/admin/lost-found/claims/{claimId}/reject
     * 管理端驳回一条认领申请，逻辑与 approve 对称。
     * 入参：claimId 认领 id；request.decisionNote() 驳回理由；currentUser 操作人。
     * 返回：驳回后的认领详情。
     */
    @PostMapping("/claims/{claimId}/reject")
    public AdminClaimDetailResponse reject(
            @PathVariable Long claimId,                 // 要驳回的认领 id
            @Valid @RequestBody AdminClaimDecisionRequest request, // 驳回决定（含备注）
            @AuthenticationPrincipal User currentUser) { // 当前操作的管理员
        return adminService.rejectClaim(claimId, request.decisionNote(), currentUser);
    }

    /**
     * 构造"报告列表"查询的分页排序对象，并进行参数合法性校验。
     * 入参：page/size 分页参数，sortValue 形如 "createdAt,desc" 的排序串。
     * 返回：Spring Data 的 Pageable。
     * 校验失败（分页越界 / 排序字段或方向非法）时抛出 LostFoundApiException（422）。
     */
    private Pageable pageable(int page, int size, String sortValue) {
        // 分页约束：页码非负，每页条数在 1~100 之间，否则 422
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        // 按第一个逗号拆分排序串为 [字段, 方向]，方向可省略
        String[] parts = sortValue.split(",", 2);
        String field = parts[0];
        // 排序字段必须命中白名单，防止任意属性路径注入
        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_SORT_FIELD",
                    "sort field must be createdAt, updatedAt, eventDate or itemName");
        }
        // 解析排序方向；省略方向时默认升序 ASC，非法方向抛 422
        Sort.Direction direction = parts.length == 2
                ? Sort.Direction.fromOptionalString(parts[1]).orElseThrow(() ->
                        new LostFoundApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "INVALID_SORT_DIRECTION",
                                "sort direction must be asc or desc"))
                : Sort.Direction.ASC;
        // 组装带分页与排序的 Pageable 对象
        return PageRequest.of(page, size, Sort.by(direction, field));
    }

    /**
     * 构造"认领列表"查询的分页排序对象：排序字段先经 CLAIM_SORT_FIELDS 映射为
     * 真实 JPA 属性路径，并追加 id 作为稳定次要排序以避免分页漂移。
     */
    private Pageable claimsPageable(int page, int size, String sortValue) {
        // 分页约束同报告列表：页码非负，每页 1~100，否则 422
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        // 拆分排序串为 [字段, 方向]
        String[] parts = sortValue.split(",", 2);
        String field = parts[0];
        // 通过映射表把前端字段名转成可跨 join 的 JPA 属性路径
        String property = CLAIM_SORT_FIELDS.get(field);
        // 映射不到说明传了非法排序字段，抛 422
        if (property == null) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_SORT_FIELD",
                    "sort field must be createdAt, updatedAt, status, itemName, eventDate, claimantEmail or reportOwnerEmail");
        }
        // 解析排序方向；省略方向时默认升序 ASC，非法方向抛 422
        Sort.Direction direction = parts.length == 2
                ? Sort.Direction.fromOptionalString(parts[1]).orElseThrow(() ->
                        new LostFoundApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "INVALID_SORT_DIRECTION",
                                "sort direction must be asc or desc"))
                : Sort.Direction.ASC;
        // 同排序值下的分页漂移用 id 作为稳定次要排序
        // 在用户指定排序之上再叠加 id 降序，保证相同排序值在翻页时顺序稳定
        return PageRequest.of(page, size, Sort.by(direction, property).and(Sort.by(Sort.Direction.DESC, "id")));
    }

    /**
     * 构造"审计日志"查询的分页排序对象：仅允许按 createdAt 排序（白名单校验），
     * 同样以 id 作为稳定次要排序防止分页漂移。
     */
    private Pageable auditPageable(int page, int size, String sortValue) {
        // 分页约束：页码非负，每页 1~100，否则 422
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        // 拆分排序串为 [字段, 方向]
        String[] parts = sortValue.split(",", 2);
        String field = parts[0];
        // 审计日志只允许 createdAt 排序字段，否则 422
        if (!ALLOWED_AUDIT_SORT_FIELDS.contains(field)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_SORT_FIELD",
                    "audit log sort field must be createdAt");
        }
        // 解析排序方向；审计日志省略方向时默认降序 DESC（最新在前），非法方向抛 422
        Sort.Direction direction = parts.length == 2
                ? Sort.Direction.fromOptionalString(parts[1]).orElseThrow(() ->
                        new LostFoundApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "INVALID_SORT_DIRECTION",
                                "sort direction must be asc or desc"))
                : Sort.Direction.DESC;
        // 同 createdAt 的分页漂移用 id 作为稳定次要排序
        // 主排序按 createdAt（默认降序），再叠加 id 降序保证翻页顺序稳定
        return PageRequest.of(page, size, Sort.by(direction, field).and(Sort.by(Sort.Direction.DESC, "id")));
    }
}
