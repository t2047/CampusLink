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
import com.app.campusagent.lostfound.storage.ObjectStorageService;
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

@Service
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class LostFoundAdminService {

    private final LostFoundReportRepository reportRepository;
    private final LostFoundClaimRepository claimRepository;
    private final LostFoundAuditLogRepository auditLogRepository;
    private final LostFoundAuditService auditService;
    private final LostFoundReportService reportService;
    private final LostFoundNotificationService notificationService;
    private final ObjectStorageService storageService;

    public LostFoundAdminService(
            LostFoundReportRepository reportRepository,
            LostFoundClaimRepository claimRepository,
            LostFoundAuditLogRepository auditLogRepository,
            LostFoundAuditService auditService,
            LostFoundReportService reportService,
            LostFoundNotificationService notificationService,
            ObjectStorageService storageService) {
        this.reportRepository = reportRepository;
        this.claimRepository = claimRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditService = auditService;
        this.reportService = reportService;
        this.notificationService = notificationService;
        this.storageService = storageService;
    }

    @Transactional(readOnly = true)
    public AdminLostFoundOverviewResponse overview() {
        return new AdminLostFoundOverviewResponse(
                reportRepository.count(),
                reportRepository.countByStatus(ReportStatus.OPEN),
                reportRepository.countByStatus(ReportStatus.CLAIMED),
                reportRepository.countByStatus(ReportStatus.CLOSED),
                reportRepository.countByReportType(ReportType.LOST),
                reportRepository.countByReportType(ReportType.FOUND),
                claimRepository.countByStatus(ClaimStatus.SUBMITTED),
                reportRepository.countByAdminHiddenTrue());
    }

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
        if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_DATE_RANGE",
                    "dateFrom must be on or before dateTo");
        }

        Specification<LostFoundReport> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (reportType != null) {
                predicates.add(builder.equal(root.get("reportType"), reportType));
            }
            if (category != null) {
                predicates.add(builder.equal(root.get("category"), category));
            }
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (adminHidden != null) {
                predicates.add(builder.equal(root.get("adminHidden"), adminHidden));
            }
            if (StringUtils.hasText(keyword)) {
                String pattern = likePattern(keyword);
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("itemName")), pattern),
                        builder.like(builder.lower(root.get("description")), pattern)));
            }
            if (StringUtils.hasText(colour)) {
                predicates.add(builder.like(builder.lower(root.get("colour")), likePattern(colour)));
            }
            if (StringUtils.hasText(location)) {
                predicates.add(builder.like(builder.lower(root.get("location")), likePattern(location)));
            }
            if (dateFrom != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("eventDate"), dateFrom));
            }
            if (dateTo != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("eventDate"), dateTo));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };

        Page<AdminLostFoundReportResponse> reports = reportRepository
                .findAll(specification, pageable)
                .map(this::toResponse);
        return PageResponse.from(reports);
    }

    /** 管理员下架报告，记录审计原因；对已隐藏报告返回冲突。 */
    @Transactional
    public AdminLostFoundReportResponse delist(Long reportId, String reason, User admin) {
        LostFoundReport report = requireReport(reportId);
        if (report.isAdminHidden()) {
            throw conflict("REPORT_ALREADY_HIDDEN", "This report is already hidden");
        }
        report.hide();
        reportRepository.save(report);
        auditService.record(
                LostFoundAuditAction.REPORT_DELISTED,
                reportId,
                report.getItemName(),
                admin,
                reason,
                "adminHidden=false→true");
        return toResponse(report);
    }

    /** 管理员恢复下架报告，记录审计原因；对未隐藏报告返回冲突。 */
    @Transactional
    public AdminLostFoundReportResponse restore(Long reportId, String reason, User admin) {
        LostFoundReport report = requireReport(reportId);
        if (!report.isAdminHidden()) {
            throw conflict("REPORT_NOT_HIDDEN", "This report is not hidden");
        }
        report.show();
        reportRepository.save(report);
        auditService.record(
                LostFoundAuditAction.REPORT_RESTORED,
                reportId,
                report.getItemName(),
                admin,
                reason,
                "adminHidden=true→false");
        return toResponse(report);
    }

    /** 管理员删除报告（任意状态），记录审计原因；审计行在报告删除后保留。 */
    @Transactional
    public void deleteReport(Long reportId, String reason, User admin) {
        LostFoundReport report = requireReport(reportId);
        String itemName = report.getItemName();
        ReportStatus status = report.getStatus();
        int imageCount = report.imageObjectKeys().size();
        reportService.deleteAsAdmin(reportId);
        auditService.record(
                LostFoundAuditAction.REPORT_DELETED_BY_ADMIN,
                reportId,
                itemName,
                admin,
                reason,
                "status=" + status + "→DELETED, images=" + imageCount);
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminAuditLogResponse> auditLogs(
            Long reportId,
            LostFoundAuditAction action,
            String actorEmail,
            String keyword,
            Pageable pageable) {
        Specification<LostFoundAuditLog> specification = (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (reportId != null) {
                predicates.add(builder.equal(root.get("reportId"), reportId));
            }
            if (action != null) {
                predicates.add(builder.equal(root.get("action"), action));
            }
            if (StringUtils.hasText(actorEmail)) {
                predicates.add(builder.equal(
                        builder.lower(root.get("actorEmail")),
                        actorEmail.trim().toLowerCase(Locale.ROOT)));
            }
            if (StringUtils.hasText(keyword)) {
                String pattern = likePattern(keyword);
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("itemName")), pattern),
                        builder.like(builder.lower(root.get("actorEmail")), pattern)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Page<AdminAuditLogResponse> logs = auditLogRepository.findAll(specification, pageable)
                .map(this::toAuditResponse);
        return PageResponse.from(logs);
    }

    /**
     * 管理员认领申请列表：支持状态/报告/下架筛选、申请人/发布者邮箱过滤、
     * 跨物品与用户字段的关键词搜索，以及分页排序。
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
            boolean keywordSearch = StringUtils.hasText(keyword);
            boolean claimantFilter = StringUtils.hasText(claimantEmail);
            boolean ownerFilter = StringUtils.hasText(reportOwnerEmail);

            // 按需创建 join 并复用，避免同一关联重复 join
            Join<LostFoundClaim, LostFoundReport> reportJoin =
                    reportId != null || adminHidden != null || keywordSearch || ownerFilter
                            ? root.join("report") : null;
            Join<LostFoundClaim, User> claimantJoin =
                    keywordSearch || claimantFilter ? root.join("claimant") : null;
            Join<LostFoundReport, User> ownerJoin =
                    reportJoin != null && (keywordSearch || ownerFilter)
                            ? reportJoin.join("createdBy") : null;

            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (reportId != null) {
                predicates.add(builder.equal(reportJoin.get("id"), reportId));
            }
            if (adminHidden != null) {
                predicates.add(builder.equal(reportJoin.get("adminHidden"), adminHidden));
            }
            if (keywordSearch) {
                String pattern = likePattern(keyword);
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("proofDescription")), pattern),
                        builder.like(builder.lower(root.get("decisionNote")), pattern),
                        builder.like(builder.lower(reportJoin.get("itemName")), pattern),
                        builder.like(builder.lower(reportJoin.get("description")), pattern),
                        builder.like(builder.lower(reportJoin.get("location")), pattern),
                        builder.like(builder.lower(claimantJoin.get("email")), pattern),
                        builder.like(builder.lower(ownerJoin.get("email")), pattern)));
            }
            if (claimantFilter) {
                predicates.add(builder.like(
                        builder.lower(claimantJoin.get("email")),
                        likePattern(claimantEmail)));
            }
            if (ownerFilter) {
                predicates.add(builder.like(
                        builder.lower(ownerJoin.get("email")),
                        likePattern(reportOwnerEmail)));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        Page<AdminClaimSummaryResponse> claims = claimRepository
                .findAll(specification, pageable)
                .map(this::toSummary);
        return PageResponse.from(claims);
    }

    /** 管理员认领申请详情，含申请人、物品完整信息与图片预览。 */
    @Transactional(readOnly = true)
    public AdminClaimDetailResponse getClaimDetail(Long claimId) {
        return toDetail(requireClaim(claimId));
    }

    /**
     * 管理员批准认领：claim → APPROVED，report → CLAIMED，
     * 同 report 其余 SUBMITTED claim 自动 REJECTED（固定文案）。
     */
    @Transactional
    public AdminClaimDetailResponse approveClaim(Long claimId, String decisionNote, User admin) {
        LostFoundClaim claim = requireClaim(claimId);
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            throw conflict("CLAIM_ALREADY_DECIDED", "This claim has already been decided");
        }
        LostFoundReport report = claim.getReport();
        if (report.getReportType() != ReportType.FOUND) {
            throw conflict("ONLY_FOUND_REPORTS_CAN_BE_CLAIMED", "Only found-item reports can be claimed");
        }
        if (report.getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_OPEN", "This report is no longer open for claims");
        }

        List<LostFoundClaim> pending = claimRepository.findByReportIdAndStatus(
                report.getId(), ClaimStatus.SUBMITTED);
        String note = trimToNull(decisionNote);
        claim.approve(note);
        List<LostFoundClaim> autoRejected = new ArrayList<>();
        for (LostFoundClaim pendingClaim : pending) {
            if (!pendingClaim.getId().equals(claimId)) {
                pendingClaim.reject("Another claim was approved by admin");
                autoRejected.add(pendingClaim);
            }
        }
        report.markClaimed();
        reportRepository.save(report);
        claimRepository.saveAll(pending);
        auditService.record(
                LostFoundAuditAction.CLAIM_APPROVED_BY_ADMIN,
                report.getId(),
                report.getItemName(),
                admin,
                note,
                "claimId=" + claimId + ", claimStatus=SUBMITTED→APPROVED, reportStatus=OPEN→CLAIMED");
        notificationService.claimApproved(claim);
        autoRejected.forEach(notificationService::claimRejected);
        return toDetail(claim);
    }

    /** 管理员拒绝认领：claim → REJECTED，report 状态不变；拒绝原因必填。 */
    @Transactional
    public AdminClaimDetailResponse rejectClaim(Long claimId, String decisionNote, User admin) {
        LostFoundClaim claim = requireClaim(claimId);
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            throw conflict("CLAIM_ALREADY_DECIDED", "This claim has already been decided");
        }
        String note = trimToNull(decisionNote);
        if (note == null) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "DECISION_NOTE_REQUIRED",
                    "A decision note is required when rejecting a claim");
        }
        claim.reject(note);
        LostFoundClaim saved = claimRepository.save(claim);
        auditService.record(
                LostFoundAuditAction.CLAIM_REJECTED_BY_ADMIN,
                saved.getReport().getId(),
                saved.getReport().getItemName(),
                admin,
                note,
                "claimId=" + claimId + ", claimStatus=SUBMITTED→REJECTED");
        notificationService.claimRejected(saved);
        return toDetail(saved);
    }

    private LostFoundClaim requireClaim(Long claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "CLAIM_NOT_FOUND",
                        "The requested claim does not exist"));
    }

    private LostFoundReport requireReport(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "LOST_FOUND_REPORT_NOT_FOUND",
                        "The requested report does not exist"));
    }

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
                report.getCreatedBy().getEmail(),
                report.getCreatedAt(),
                report.getUpdatedAt());
    }

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

    private LostFoundApiException conflict(String code, String message) {
        return new LostFoundApiException(HttpStatus.CONFLICT, code, message);
    }

    private String likePattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private AdminClaimSummaryResponse toSummary(LostFoundClaim claim) {
        LostFoundReport report = claim.getReport();
        return new AdminClaimSummaryResponse(
                claim.getId(),
                claim.getStatus(),
                summary(claim.getProofDescription()),
                claim.getDecisionNote(),
                toUserSummary(claim.getClaimant()),
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

    private AdminClaimDetailResponse toDetail(LostFoundClaim claim) {
        LostFoundReport report = claim.getReport();
        List<LostFoundImageResponse> images = report.getImages().stream()
                .sorted(Comparator.comparingInt(LostFoundImage::getSortOrder))
                .map(image -> new LostFoundImageResponse(
                        image.getId(),
                        storageService.createPresignedGetUrl(image.getObjectKey()),
                        image.getContentType(),
                        image.getFileSize(),
                        image.getSortOrder()))
                .toList();
        boolean reviewed = claim.getStatus() != ClaimStatus.SUBMITTED;
        return new AdminClaimDetailResponse(
                claim.getId(),
                claim.getStatus(),
                claim.getProofDescription(),
                claim.getDecisionNote(),
                new AdminClaimUserDetail(
                        claim.getClaimant().getId(),
                        claim.getClaimant().getEmail(),
                        claim.getClaimant().getRole()),
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
                new AdminClaimReviewInfo(
                        reviewed,
                        claim.getDecisionNote(),
                        reviewed ? reviewedAtOrFallback(claim) : null),
                claim.getCreatedAt(),
                claim.getUpdatedAt());
    }

    private AdminClaimUserSummary toUserSummary(User user) {
        return new AdminClaimUserSummary(user.getId(), user.getEmail());
    }

    /** 列表展示的证明摘要；详情接口返回完整证明。 */
    private String summary(String text) {
        return text == null || text.length() <= 120
                ? text
                : text.substring(0, 117) + "...";
    }

    /** 审核时间：优先 reviewed_at，历史已审核数据回退到 updatedAt。 */
    private Instant reviewedAtOrFallback(LostFoundClaim claim) {
        return claim.getReviewedAt() != null ? claim.getReviewedAt() : claim.getUpdatedAt();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
