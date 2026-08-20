/**
 * 失物招领（Lost &amp; Found）模块的管理员侧业务服务。
 *
 * <p>本类承载后台管理所需的全部核心能力，仅在 {@code ADMIN} / {@code SUPER_ADMIN} 角色下可访问：</p>
 * <ul>
 *   <li>运营总览 {@link #overview}——报告/认领的各状态统计数字；</li>
 *   <li>报告管理 {@link #search}（多条件+下架标记筛选的分页搜索）、下架 {@link #delist}、
 *       恢复 {@link #restore}、删除 {@link #deleteReport}（任意状态）；</li>
 *   <li>审计日志查询 {@link #auditLogs}——按报告/动作/操作者/关键词过滤的分页追溯；</li>
 *   <li>认领管理 {@link #searchClaims} / {@link #getClaimDetail}、批准 {@link #approveClaim}
 *       （连同把同报告其余待审认领自动拒绝）、拒绝 {@link #rejectClaim}。</li>
 * </ul>
 *
 * <p>被 {@code LostFoundAdminController}（/api/admin/lost-found）调用；类上同时声明了
 * {@code @PreAuthorize} 角色鉴权（方法与类双层校验）。</p>
 *
 * <p>依赖的 Repository / Service：</p>
 * <ul>
 *   <li>{@code LostFoundReportRepository} — 报告查询、计数与规格分页；</li>
 *   <li>{@code LostFoundClaimRepository} — 认领查询、计数与规格分页；</li>
 *   <li>{@code LostFoundAuditLogRepository} — 审计日志规格分页查询；</li>
 *   <li>{@code LostFoundAuditService} — 管理员操作的审计留痕；</li>
 *   <li>{@code LostFoundReportService} — 复用其级联删除（{@code deleteAsAdmin}）；</li>
 *   <li>{@code LostFoundNotificationService} — 认领审批后向申请人/发布者发站内通知。</li>
 * </ul>
 */
package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundAuditAction;
import com.app.campusagent.lostfound.domain.LostFoundAuditLog;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import com.app.campusagent.lostfound.domain.LostFoundImage;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.LostFoundImageResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.dto.admin.AdminAuditLogResponse;
import com.app.campusagent.lostfound.dto.admin.AdminClaimDetailResponse;
import com.app.campusagent.lostfound.dto.admin.AdminClaimReportDetail;
import com.app.campusagent.lostfound.dto.admin.AdminClaimReportSummary;
import com.app.campusagent.lostfound.dto.admin.AdminClaimReviewInfo;
import com.app.campusagent.lostfound.dto.admin.AdminClaimSummaryResponse;
import com.app.campusagent.lostfound.dto.admin.AdminClaimUserDetail;
import com.app.campusagent.lostfound.dto.admin.AdminClaimUserSummary;
import com.app.campusagent.lostfound.dto.admin.AdminLostFoundOverviewResponse;
import com.app.campusagent.lostfound.dto.admin.AdminLostFoundReportResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundAuditLogRepository;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 管理员侧服务，职责详见文件头注释。
 *
 * <p>类级别 {@code @PreAuthorize} 声明了全类角色门槛（{@code ADMIN}/{@code SUPER_ADMIN}），
 * 即使个别方法漏加方法级注解，也会被类级注解兜底拒绝非管理员访问。</p>
 */
@Service
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class LostFoundAdminService {

    /** 报告 Repository：管理端报告的计数、规格分页查询与实体加载。 */
    private final LostFoundReportRepository reportRepository;
    /** 认领 Repository：认领的计数、规格分页查询、按报告+状态查找等。 */
    private final LostFoundClaimRepository claimRepository;
    /** 审计日志 Repository：审计记录的规格分页查询。 */
    private final LostFoundAuditLogRepository auditLogRepository;
    /** 审计服务：管理员的下架/恢复/删除/审批等操作写入审计日志。 */
    private final LostFoundAuditService auditService;
    /** 用户侧报告服务：删除报告时复用它封装的级联清理（{@code deleteAsAdmin}）。 */
    private final LostFoundReportService reportService;
    /** 通知服务：认领批准/拒绝后给申请人、发布者发站内通知。 */
    private final LostFoundNotificationService notificationService;

    public LostFoundAdminService(
            LostFoundReportRepository reportRepository,
            LostFoundClaimRepository claimRepository,
            LostFoundAuditLogRepository auditLogRepository,
            LostFoundAuditService auditService,
            LostFoundReportService reportService,
            LostFoundNotificationService notificationService) {
        this.reportRepository = reportRepository;
        this.claimRepository = claimRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.reportService = reportService;
        this.notificationService = notificationService;
    }

    /**
     * 运营总览统计（管理后台首页）。
     *
     * @return 各类关键计数：报告总数、各状态（OPEN/CLAIMED/CLOSED）、各类型（LOST/FOUND）、
     *         待处理认领数、已处理认领数（APPROVED+REJECTED）、被下架报告数。
     */
    @Transactional(readOnly = true)
    public AdminLostFoundOverviewResponse overview() {
        return new AdminLostFoundOverviewResponse(
                // 报告总数
                reportRepository.count(),
                // 待寻（OPEN）报告数
                reportRepository.countByStatus(ReportStatus.OPEN),
                // 已被认领（CLAIMED）报告数
                reportRepository.countByStatus(ReportStatus.CLAIMED),
                // 已关闭（CLOSED）报告数
                reportRepository.countByStatus(ReportStatus.CLOSED),
                // 丢失类（LOST）报告数
                reportRepository.countByReportType(ReportType.LOST),
                // 拾到类（FOUND）报告数
                reportRepository.countByReportType(ReportType.FOUND),
                // 待管理员审批的认领数（SUBMITTED）
                claimRepository.countByStatus(ClaimStatus.SUBMITTED),
                // 已处理认领数 = 已批准 + 已拒绝
                claimRepository.countByStatus(ClaimStatus.APPROVED) + claimRepository.countByStatus(ClaimStatus.REJECTED),
                // 被管理员下架（隐藏）的报告数
                reportRepository.countByAdminHiddenTrue());
    }

    /**
     * 管理员报告列表搜索。
     *
     * <p>与用户侧搜索的区别：不强制过滤 adminHidden，而是把它作为一个可选筛选条件
     * （传 true 只看下架的、传 false 只看未下架的、不传则全部），便于后台管理。
     * 颜色过滤此处不做同义词扩展（管理端按字面模糊匹配即可）。</p>
     *
     * @return 分页的 {@link AdminLostFoundReportResponse} 列表，含发布者邮箱与下架标记。
     * @throws LostFoundApiException 日期范围非法时抛 422 INVALID_DATE_RANGE。
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminLostFoundReportResponse> search(
            ReportType reportType,
            String keyword,
            ItemCategory category,
            String colour,
            String location,
            LocalDate dateFrom,
            LocalDate dateTo,
            ReportStatus status,
            Boolean adminHidden,
            Pageable pageable) {
        // 前置校验日期范围合法性
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_DATE_RANGE",
                    "dateFrom must be on or before dateTo");
        }

        // 动态拼装查询条件：全部可选，缺省不参与过滤
        Specification<LostFoundReport> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            // 报告类型精确匹配
            if (reportType != null) {
                predicates.add(builder.equal(root.get("reportType"), reportType));
            }
            // 物品分类精确匹配
            if (category != null) {
                predicates.add(builder.equal(root.get("category"), category));
            }
            // 报告状态精确匹配
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            // 下架标记过滤：Boolean 三态，null 表示不过滤
            if (adminHidden != null) {
                predicates.add(builder.equal(root.get("adminHidden"), adminHidden));
            }
            // 关键词：物品名或描述模糊匹配（转小写，OR 组合）
            if (StringUtils.hasText(keyword)) {
                String pattern = likePattern(keyword);
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("itemName")), pattern),
                        builder.like(builder.lower(root.get("description")), pattern)));
            }
            // 颜色字面模糊匹配（管理端不做同义词扩展）
            if (StringUtils.hasText(colour)) {
                predicates.add(builder.like(builder.lower(root.get("colour")), likePattern(colour)));
            }
            // 地点模糊匹配
            if (StringUtils.hasText(location)) {
                predicates.add(builder.like(builder.lower(root.get("location")), likePattern(location)));
            }
            // 事件日期范围下界（含）
            if (dateFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("eventDate"), dateFrom));
            }
            // 事件日期范围上界（含）
            if (dateTo != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("eventDate"), dateTo));
            }
            // 全部条件 AND 拼接
            return builder.and(predicates.toArray(Predicate[]::new));
        };

        // 规格查询 + 分页排序，实体映射为管理端 DTO
        Page<AdminLostFoundReportResponse> reports = reportRepository
                .findAll(specification, pageable)
                .map(this::toResponse);
        return PageResponse.from(reports);
    }

    /**
     * 管理员下架报告：置 adminHidden=true，使其从公开搜索与 Agent 候选中隐藏。
     *
     * @param reportId 报告 id；
     * @param reason   下架原因（必填，由 DTO 校验），写入审计日志；
     * @param admin    执行操作的管理员。
     * @return 下架后的报告 DTO（adminHidden=true）。
     * @throws LostFoundApiException 报告不存在抛 404；已隐藏抛 409 REPORT_ALREADY_HIDDEN。
     */
    @Transactional
    public AdminLostFoundReportResponse delist(Long reportId, String reason, User admin) {
        LostFoundReport report = requireReport(reportId);
        // 幂等约束：已隐藏的报告不允许重复下架
        if (report.isAdminHidden()) {
            throw conflict("REPORT_ALREADY_HIDDEN", "This report is already hidden");
        }
        // 实体标记隐藏并保存
        report.hide();
        reportRepository.save(report);
        // 审计记录下架动作与原因，detail 标注状态变化
        auditService.record(
                LostFoundAuditAction.REPORT_DELISTED,
                reportId,
                report.getItemName(),
                admin,
                reason,
                "adminHidden=false→true");
        return toResponse(report);
    }

    /**
     * 管理员恢复下架报告：置 adminHidden=false，重新对公开可见。
     *
     * @param reportId 报告 id；
     * @param reason   恢复原因（必填，由 DTO 校验），写入审计日志；
     * @param admin    执行操作的管理员。
     * @return 恢复后的报告 DTO（adminHidden=false）。
     * @throws LostFoundApiException 报告不存在抛 404；未隐藏抛 409 REPORT_NOT_HIDDEN。
     */
    @Transactional
    public AdminLostFoundReportResponse restore(Long reportId, String reason, User admin) {
        LostFoundReport report = requireReport(reportId);
        // 幂等约束：未隐藏的报告不允许恢复
        if (!report.isAdminHidden()) {
            throw conflict("REPORT_NOT_HIDDEN", "This report is not hidden");
        }
        // 实体标记显示并保存
        report.show();
        reportRepository.save(report);
        // 审计记录恢复动作与原因
        auditService.record(
                LostFoundAuditAction.REPORT_RESTORED,
                reportId,
                report.getItemName(),
                admin,
                reason,
                "adminHidden=true→false");
        return toResponse(report);
    }

    /**
     * 管理员删除报告（任意状态，包括已认领/已关闭）。
     *
     * <p>不校验 owner 与状态（管理端权限由类级 {@code @PreAuthorize} 兜底）；级联清理
     * （通知/认领/MinIO 对象）复用 {@code LostFoundReportService.deleteAsAdmin}。
     * 审计行在删除后写入且保留，reportId 为无外键普通列。</p>
     *
     * @param reportId 报告 id；
     * @param reason   删除原因（必填，由 DTO 校验），写入审计日志；
     * @param admin    执行操作的管理员。
     */
    @Transactional
    public void deleteReport(Long reportId, String reason, User admin) {
        LostFoundReport report = requireReport(reportId);
        // 删除前快照物品名/状态/图片数，供审计 detail 使用（实体随后被删除）
        String itemName = report.getItemName();
        ReportStatus status = report.getStatus();
        int imageCount = report.imageObjectKeys().size();
        // 复用用户侧服务的级联删除
        reportService.deleteAsAdmin(reportId);
        // 审计行在报告删除后写入，历史仍可追溯
        auditService.record(
                LostFoundAuditAction.REPORT_DELETED_BY_ADMIN,
                reportId,
                itemName,
                admin,
                reason,
                "status=" + status + "→DELETED, images=" + imageCount);
    }

    /**
     * 审计日志查询（管理后台追溯管理员/用户的报告级写操作）。
     *
     * <p>支持按报告 id、动作类型、操作者邮箱（精确匹配，忽略大小写）与关键词（物品名/操作者
     * 邮箱模糊匹配）过滤。审计日志是历史快照，不因报告删除而丢失。</p>
     *
     * @param reportId   报告 id 过滤，可空；
     * @param action     审计动作类型过滤，可空；
     * @param actorEmail 操作者邮箱过滤（精确、忽略大小写），可空；
     * @param keyword    物品名/操作者邮箱模糊关键词，可空；
     * @param pageable   分页排序参数（Controller 只允许按 createdAt 排序）。
     * @return 分页的 {@link AdminAuditLogResponse} 列表。
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminAuditLogResponse> auditLogs(
            Long reportId,
            LostFoundAuditAction action,
            String actorEmail,
            String keyword,
            Pageable pageable) {
        // 动态拼装审计日志查询条件
        Specification<LostFoundAuditLog> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            // 按报告 id 精确过滤（reportId 为普通列，报告删除后仍可命中）
            if (reportId != null) {
                predicates.add(builder.equal(root.get("reportId"), reportId));
            }
            // 按审计动作类型精确过滤
            if (action != null) {
                predicates.add(builder.equal(root.get("action"), action));
            }
            // 操作者邮箱精确匹配：两侧转小写后比较，忽略大小写差异
            if (StringUtils.hasText(actorEmail)) {
                predicates.add(builder.equal(
                        builder.lower(root.get("actorEmail")),
                        actorEmail.trim().toLowerCase(Locale.ROOT)));
            }
            // 关键词：物品名或操作者邮箱模糊匹配（转小写，OR 组合）
            if (StringUtils.hasText(keyword)) {
                String pattern = likePattern(keyword);
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("itemName")), pattern),
                        builder.like(builder.lower(root.get("actorEmail")), pattern)));
            }
            // 全部条件 AND 拼接
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        // 规格查询 + 分页排序，映射为审计 DTO
        Page<AdminAuditLogResponse> logs = auditLogRepository.findAll(specification, pageable)
                .map(this::toAuditResponse);
        return PageResponse.from(logs);
    }

    /**
     * 管理员认领申请列表：支持状态/报告/下架筛选、申请人/发布者邮箱过滤、
     * 跨物品与用户字段的关键词搜索，以及分页排序。
     *
     * <p>查询根实体是 {@link LostFoundClaim}；由于过滤条件涉及关联表（报告、申请人、发布者），
     * 采用 JPA {@code Join} 把关联表连进来。关联只在确实需要时才创建并复用，避免多余的
     * join 与笛卡尔积。</p>
     *
     * @param status          认领状态过滤，可空；
     * @param keyword         跨字段关键词：认领证明、审核意见、报告物品名/描述/地点、
     *                       申请人邮箱、发布者邮箱的模糊匹配，可空；
     * @param reportId        所属报告 id 过滤，可空；
     * @param claimantEmail   申请人邮箱模糊过滤，可空；
     * @param reportOwnerEmail 报告发布者邮箱模糊过滤，可空；
     * @param adminHidden     报告下架标记过滤（true/false/null），可空；
     * @param pageable        分页排序参数（Controller 端把排序字段映射为嵌套属性路径）。
     * @return 分页的 {@link AdminClaimSummaryResponse} 列表（含报告摘要与申请人摘要）。
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminClaimSummaryResponse> searchClaims(
            ClaimStatus status,
            String keyword,
            Long reportId,
            String claimantEmail,
            String reportOwnerEmail,
            Boolean adminHidden,
            Pageable pageable) {
        Specification<LostFoundClaim> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            // 预判哪些过滤条件实际生效，决定是否需要 join 关联表
            boolean keywordSearch = StringUtils.hasText(keyword);
            boolean claimantFilter = StringUtils.hasText(claimantEmail);
            boolean ownerFilter = StringUtils.hasText(reportOwnerEmail);

            // 按需创建 join 并复用，避免同一关联重复 join：
            // report 关联——凡是需要读报告字段（reportId/adminHidden/关键词/发布者）时创建
            Join<LostFoundClaim, LostFoundReport> reportJoin =
                    reportId != null || adminHidden != null || keywordSearch || ownerFilter
                            ? root.join("report") : null;
            // claimant 关联——关键词或申请人邮箱过滤时需要
            Join<LostFoundClaim, User> claimantJoin =
                    keywordSearch || claimantFilter ? root.join("claimant") : null;
            // owner 关联——关键词或发布者邮箱过滤时需要（依赖 reportJoin 已建立）
            Join<LostFoundReport, User> ownerJoin =
                    reportJoin != null && (keywordSearch || ownerFilter)
                            ? reportJoin.join("createdBy") : null;

            // 认领状态精确匹配
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            // 所属报告精确匹配
            if (reportId != null) {
                predicates.add(builder.equal(reportJoin.get("id"), reportId));
            }
            // 报告下架标记过滤（三态）
            if (adminHidden != null) {
                predicates.add(builder.equal(reportJoin.get("adminHidden"), adminHidden));
            }
            // 跨字段关键词：任一字段模糊命中即算匹配（OR 组合）
            if (keywordSearch) {
                String pattern = likePattern(keyword);
                predicates.add(builder.or(
                        // 认领方字段：证明描述、审核意见
                        builder.like(builder.lower(root.get("proofDescription")), pattern),
                        builder.like(builder.lower(root.get("decisionNote")), pattern),
                        // 报告字段：物品名、描述、地点
                        builder.like(builder.lower(reportJoin.get("itemName")), pattern),
                        builder.like(builder.lower(reportJoin.get("description")), pattern),
                        builder.like(builder.lower(reportJoin.get("location")), pattern),
                        // 用户字段：申请人邮箱、发布者邮箱
                        builder.like(builder.lower(claimantJoin.get("email")), pattern),
                        builder.like(builder.lower(ownerJoin.get("email")), pattern)));
            }
            // 申请人邮箱过滤（模糊匹配）
            if (claimantFilter) {
                predicates.add(builder.like(
                        builder.lower(claimantJoin.get("email")),
                        likePattern(claimantEmail)));
            }
            // 发布者邮箱过滤（模糊匹配）
            if (ownerFilter) {
                predicates.add(builder.like(
                        builder.lower(ownerJoin.get("email")),
                        likePattern(reportOwnerEmail)));
            }
            // 全部条件 AND 拼接
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        // 规格查询 + 分页排序，认领实体映射为摘要 DTO
        Page<AdminClaimSummaryResponse> claims = claimRepository
                .findAll(specification, pageable)
                .map(this::toSummary);
        return PageResponse.from(claims);
    }

    /**
     * 管理员认领申请详情，含申请人、物品完整信息与图片预览。
     *
     * @param claimId 认领申请 id。
     * @return 认领详情 DTO（含申请人信息、报告完整信息与排序后的图片列表、审核信息）。
     * @throws LostFoundApiException 认领不存在抛 404 CLAIM_NOT_FOUND。
     */
    @Transactional(readOnly = true)
    public AdminClaimDetailResponse getClaimDetail(Long claimId) {
        return toDetail(requireClaim(claimId));
    }

    /**
     * 管理员批准认领：claim → APPROVED，report → CLAIMED，
     * 同 report 其余 SUBMITTED claim 自动 REJECTED（固定文案）。
     *
     * <p>业务约束：认领必须处于 SUBMITTED 待审态；只有"拾到类"报告（FOUND）允许被认领；
     * 报告必须仍处于 OPEN 状态。批准成功后给被批准的申请人发 CLAIM_APPROVED 通知、
     * 给报告发布者发 REPORT_CLAIMED 通知，并给被自动拒绝的其余申请人发 CLAIM_REJECTED 通知。</p>
     *
     * @param claimId      待批准的认领 id；
     * @param decisionNote 审核意见（可空）；
     * @param admin        执行操作的管理员。
     * @return 批准后的认领详情 DTO（status=APPROVED）。
     * @throws LostFoundApiException 认领不存在抛 404；已处理过抛 409 CLAIM_ALREADY_DECIDED；
     *         非 FOUND 报告抛 409 ONLY_FOUND_REPORTS_CAN_BE_CLAIMED；报告非 OPEN 抛 409 REPORT_NOT_OPEN。
     */
    @Transactional
    public AdminClaimDetailResponse approveClaim(Long claimId, String decisionNote, User admin) {
        LostFoundClaim claim = requireClaim(claimId);
        // 幂等约束：只有待审（SUBMITTED）的认领才能被审批
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            throw conflict("CLAIM_ALREADY_DECIDED", "This claim has already been decided");
        }
        LostFoundReport report = claim.getReport();
        // 业务规则：只有"拾到物"报告才能被认领（丢失类报告没有可领回的物品）
        if (report.getReportType() != ReportType.FOUND) {
            throw conflict("ONLY_FOUND_REPORTS_CAN_BE_CLAIMED", "Only found-item reports can be claimed");
        }
        // 报告必须仍在寻主状态，否则不可认领
        if (report.getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_OPEN", "This report is no longer open for claims");
        }

        // 取出同报告全部待审认领，用于"批准一个、其余自动拒绝"
        List<LostFoundClaim> pending = claimRepository.findByReportIdAndStatus(
                report.getId(), ClaimStatus.SUBMITTED);
        // 审核意见可空：空白归一为 null
        String note = trimToNull(decisionNote);
        claim.approve(note);
        // 其余待审认领统一按固定文案自动拒绝
        List<LostFoundClaim> autoRejected = new ArrayList<>();
        for (LostFoundClaim pendingClaim : pending) {
            if (!pendingClaim.getId().equals(claimId)) {
                pendingClaim.reject("Another claim was approved by admin");
                autoRejected.add(pendingClaim);
            }
        }
        // 报告标记为已认领（CLAIMED），保存报告与全部被更新的认领
        report.markClaimed();
        reportRepository.save(report);
        claimRepository.saveAll(pending);
        // 审计记录批准动作与审核意见
        auditService.record(
                LostFoundAuditAction.CLAIM_APPROVED_BY_ADMIN,
                report.getId(),
                report.getItemName(),
                admin,
                note,
                "claimId=" + claimId + ", claimStatus=SUBMITTED→APPROVED, reportStatus=OPEN→CLAIMED");
        // 站内通知：被批准的申请人、报告发布者、被自动拒绝的申请人
        notificationService.claimApproved(claim);
        autoRejected.forEach(notificationService::claimRejected);
        return toDetail(claim);
    }

    /**
     * 管理员拒绝认领：claim → REJECTED，report 状态不变；拒绝原因必填。
     *
     * <p>与批准不同，拒绝不触碰报告状态（报告保持 OPEN，其他申请人可继续等待审批），
     * 因此不要求报告处于特定类型/状态，只要认领本身处于待审态即可。</p>
     *
     * @param claimId      待拒绝的认领 id；
     * @param decisionNote 拒绝原因（必填，空白则抛 422）；
     * @param admin        执行操作的管理员。
     * @return 拒绝后的认领详情 DTO（status=REJECTED）。
     * @throws LostFoundApiException 认领不存在抛 404；已处理过抛 409 CLAIM_ALREADY_DECIDED；
     *         拒绝原因缺失抛 422 DECISION_NOTE_REQUIRED。
     */
    @Transactional
    public AdminClaimDetailResponse rejectClaim(Long claimId, String decisionNote, User admin) {
        LostFoundClaim claim = requireClaim(claimId);
        // 幂等约束：只有待审（SUBMITTED）的认领才能被审批
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            throw conflict("CLAIM_ALREADY_DECIDED", "This claim has already been decided");
        }
        // 拒绝原因必填：空白归一为 null 后直接拒绝
        String note = trimToNull(decisionNote);
        if (note == null) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DECISION_NOTE_REQUIRED",
                    "A decision note is required when rejecting a claim");
        }
        claim.reject(note);
        LostFoundClaim saved = claimRepository.save(claim);
        // 审计记录拒绝动作与原因
        auditService.record(
                LostFoundAuditAction.CLAIM_REJECTED_BY_ADMIN,
                saved.getReport().getId(),
                saved.getReport().getItemName(),
                admin,
                note,
                "claimId=" + claimId + ", claimStatus=SUBMITTED→REJECTED");
        // 站内通知申请人认领被拒
        notificationService.claimRejected(saved);
        return toDetail(saved);
    }

    /**
     * 按 id 加载认领实体，不存在时抛 404。
     *
     * @param claimId 认领 id。
     * @return 已加载的认领实体。
     * @throws LostFoundApiException 认领不存在时抛 404 CLAIM_NOT_FOUND。
     */
    private LostFoundClaim requireClaim(Long claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "CLAIM_NOT_FOUND",
                        "The requested claim does not exist"));
    }

    /**
     * 按 id 加载报告实体，不存在时抛 404。
     *
     * @param reportId 报告 id。
     * @return 已加载的报告实体。
     * @throws LostFoundApiException 报告不存在时抛 404 LOST_FOUND_REPORT_NOT_FOUND。
     */
    private LostFoundReport requireReport(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "LOST_FOUND_REPORT_NOT_FOUND",
                        "The requested report does not exist"));
    }

    /**
     * 报告实体 → 管理端报告 DTO 映射。
     *
     * @param report 报告实体。
     * @return 管理端报告响应，含发布者邮箱与下架标记。
     */
    private AdminLostFoundReportResponse toResponse(LostFoundReport report) {
        return new AdminLostFoundReportResponse(
                report.getId(),
                report.getReportType(),
                report.getItemName(),
                report.getCategory(),
                report.getColour(),
                report.getLocation(),
                report.getEventDate(),
                report.getStatus(),
                report.isAdminHidden(),
                // 管理端需要知道发布者身份，直接从 createdBy 取邮箱
                report.getCreatedBy().getEmail(),
                report.getCreatedAt(),
                report.getUpdatedAt());
    }

    /** 审计日志实体 → 审计 DTO 映射（全部为历史快照字段，不涉及关联查询）。 */
    private AdminAuditLogResponse toAuditResponse(LostFoundAuditLog log) {
        return new AdminAuditLogResponse(
                log.getId(),
                log.getReportId(),
                log.getItemName(),
                log.getAction(),
                log.getActorEmail(),
                log.getReason(),
                log.getDetail(),
                log.getCreatedAt());
    }

    /** 构造 409 冲突异常（用于重复下架/恢复、已审批等状态机约束）。 */
    private LostFoundApiException conflict(String code, String message) {
        return new LostFoundApiException(HttpStatus.CONFLICT, code, message);
    }

    /** 构造 SQL LIKE 模式：转小写 + 两侧通配符，配合 lower(col) 实现大小写不敏感模糊匹配。 */
    private String likePattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    /**
     * 认领实体 → 认领摘要 DTO 映射（列表展示，字段精简）。
     *
     * @param claim 认领实体。
     * @return 摘要 DTO：证明只截断为前 120 字符，附带申请人摘要与报告摘要。
     */
    private AdminClaimSummaryResponse toSummary(LostFoundClaim claim) {
        LostFoundReport report = claim.getReport();
        return new AdminClaimSummaryResponse(
                claim.getId(),
                claim.getStatus(),
                // 列表只展示证明摘要（截断），避免列表页返回大段文本
                summary(claim.getProofDescription()),
                claim.getDecisionNote(),
                // 申请人摘要（id+邮箱）
                toUserSummary(claim.getClaimant()),
                // 关联报告的摘要信息
                new AdminClaimReportSummary(
                        report.getId(),
                        report.getReportType(),
                        report.getItemName(),
                        report.getCategory(),
                        report.getColour(),
                        report.getLocation(),
                        report.getEventDate(),
                        report.getStatus(),
                        report.isAdminHidden(),
                        toUserSummary(report.getCreatedBy())),
                claim.getCreatedAt(),
                claim.getUpdatedAt());
    }

    /**
     * 认领实体 → 认领详情 DTO 映射（详情接口使用，返回完整信息）。
     *
     * @param claim 认领实体。
     * @return 详情 DTO：含申请人完整信息（id/邮箱/角色）、报告完整信息（含描述/时间描述/
     *         按 sortOrder 排序的图片列表）与审核信息（是否已审核、审核意见、审核时间）。
     */
    private AdminClaimDetailResponse toDetail(LostFoundClaim claim) {
        LostFoundReport report = claim.getReport();
        // 报告图片按 sortOrder 升序排列后映射为图片响应
        List<LostFoundImageResponse> images = report.getImages().stream()
                .sorted(Comparator.comparingInt(LostFoundImage::getSortOrder))
                .map(LostFoundImageResponse::of)
                .toList();
        // 是否已审核：只要不是 SUBMITTED（即 APPROVED/REJECTED）就视为已处理
        boolean reviewed = claim.getStatus() != ClaimStatus.SUBMITTED;
        return new AdminClaimDetailResponse(
                claim.getId(),
                claim.getStatus(),
                claim.getProofDescription(),
                claim.getDecisionNote(),
                // 申请人完整信息
                new AdminClaimUserDetail(
                        claim.getClaimant().getId(),
                        claim.getClaimant().getEmail(),
                        claim.getClaimant().getRole()),
                // 报告完整信息（含图片）
                new AdminClaimReportDetail(
                        report.getId(),
                        report.getReportType(),
                        report.getItemName(),
                        report.getCategory(),
                        report.getDescription(),
                        report.getColour(),
                        report.getLocation(),
                        report.getEventDate(),
                        report.getTimeDescription(),
                        report.getStatus(),
                        report.isAdminHidden(),
                        toUserSummary(report.getCreatedBy()),
                        images),
                // 审核信息：未审核时 reviewedAt 为 null
                new AdminClaimReviewInfo(
                        reviewed,
                        claim.getDecisionNote(),
                        reviewed ? reviewedAtOrFallback(claim) : null),
                claim.getCreatedAt(),
                claim.getUpdatedAt());
    }

    /** 用户 → 摘要 DTO（仅 id 与邮箱，避免把角色等敏感信息塞进列表）。 */
    private AdminClaimUserSummary toUserSummary(User user) {
        return new AdminClaimUserSummary(user.getId(), user.getEmail());
    }

    /** 列表展示的证明摘要；详情接口返回完整证明。 */
    private String summary(String text) {
        // 超过 120 字符时截断到 117 字符并追加省略号
        return text == null || text.length() <= 120
                ? text
                : text.substring(0, 117) + "...";
    }

    /** 审核时间：优先 reviewed_at，历史已审核数据回退到 updatedAt。 */
    private Instant reviewedAtOrFallback(LostFoundClaim claim) {
        return claim.getReviewedAt() != null ? claim.getReviewedAt() : claim.getUpdatedAt();
    }

    /** 空白字符串归一为 null，非空白时去首尾空白后返回。 */
    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
