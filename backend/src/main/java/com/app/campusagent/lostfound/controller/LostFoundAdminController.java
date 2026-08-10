package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundAuditAction;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.dto.admin.AdminAuditLogResponse;
import com.app.campusagent.lostfound.dto.admin.AdminClaimDecisionRequest;
import com.app.campusagent.lostfound.dto.admin.AdminClaimDetailResponse;
import com.app.campusagent.lostfound.dto.admin.AdminClaimSummaryResponse;
import com.app.campusagent.lostfound.dto.admin.AdminLostFoundOverviewResponse;
import com.app.campusagent.lostfound.dto.admin.AdminLostFoundReportResponse;
import com.app.campusagent.lostfound.dto.admin.AdminReportActionRequest;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.service.LostFoundAdminService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/lost-found")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class LostFoundAdminController {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "updatedAt", "eventDate", "itemName");

    private static final Set<String> ALLOWED_AUDIT_SORT_FIELDS = Set.of("createdAt");

    /** 认领列表排序字段 → JPA 属性路径（跨 join 的字段映射到嵌套属性）。 */
    private static final Map<String, String> CLAIM_SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "updatedAt", "updatedAt",
            "status", "status",
            "itemName", "report.itemName",
            "eventDate", "report.eventDate",
            "claimantEmail", "claimant.email",
            "reportOwnerEmail", "report.createdBy.email");

    private final LostFoundAdminService adminService;

    public LostFoundAdminController(LostFoundAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/overview")
    public AdminLostFoundOverviewResponse overview() {
        return adminService.overview();
    }

    @GetMapping("/reports")
    public PageResponse<AdminLostFoundReportResponse> search(
            @RequestParam(required = false) ReportType reportType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ItemCategory category,
            @RequestParam(required = false) String colour,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) Boolean adminHidden,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
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

    @PostMapping("/reports/{reportId}/delist")
    public AdminLostFoundReportResponse delist(
            @PathVariable Long reportId,
            @Valid @RequestBody AdminReportActionRequest request,
            @AuthenticationPrincipal User currentUser) {
        return adminService.delist(reportId, request.reason(), currentUser);
    }

    @PostMapping("/reports/{reportId}/restore")
    public AdminLostFoundReportResponse restore(
            @PathVariable Long reportId,
            @Valid @RequestBody AdminReportActionRequest request,
            @AuthenticationPrincipal User currentUser) {
        return adminService.restore(reportId, request.reason(), currentUser);
    }

    @PostMapping("/reports/{reportId}/delete")
    public ResponseEntity<Void> delete(
            @PathVariable Long reportId,
            @Valid @RequestBody AdminReportActionRequest request,
            @AuthenticationPrincipal User currentUser) {
        adminService.deleteReport(reportId, request.reason(), currentUser);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/audit-logs")
    public PageResponse<AdminAuditLogResponse> auditLogs(
            @RequestParam(required = false) Long reportId,
            @RequestParam(required = false) LostFoundAuditAction action,
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return adminService.auditLogs(
                reportId,
                action,
                actorEmail,
                keyword,
                auditPageable(page, size, sort));
    }

    @GetMapping("/claims")
    public PageResponse<AdminClaimSummaryResponse> claims(
            @RequestParam(required = false) ClaimStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long reportId,
            @RequestParam(required = false) String claimantEmail,
            @RequestParam(required = false) String reportOwnerEmail,
            @RequestParam(required = false) Boolean adminHidden,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return adminService.searchClaims(
                status,
                keyword,
                reportId,
                claimantEmail,
                reportOwnerEmail,
                adminHidden,
                claimsPageable(page, size, sort));
    }

    @GetMapping("/claims/{claimId}")
    public AdminClaimDetailResponse claim(@PathVariable Long claimId) {
        return adminService.getClaimDetail(claimId);
    }

    @PostMapping("/claims/{claimId}/approve")
    public AdminClaimDetailResponse approve(
            @PathVariable Long claimId,
            @Valid @RequestBody AdminClaimDecisionRequest request,
            @AuthenticationPrincipal User currentUser) {
        return adminService.approveClaim(claimId, request.decisionNote(), currentUser);
    }

    @PostMapping("/claims/{claimId}/reject")
    public AdminClaimDetailResponse reject(
            @PathVariable Long claimId,
            @Valid @RequestBody AdminClaimDecisionRequest request,
            @AuthenticationPrincipal User currentUser) {
        return adminService.rejectClaim(claimId, request.decisionNote(), currentUser);
    }

    private Pageable pageable(int page, int size, String sortValue) {
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        String[] parts = sortValue.split(",", 2);
        String field = parts[0];
        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_SORT_FIELD",
                    "sort field must be createdAt, updatedAt, eventDate or itemName");
        }
        Sort.Direction direction = parts.length == 2
                ? Sort.Direction.fromOptionalString(parts[1]).orElseThrow(() ->
                        new LostFoundApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "INVALID_SORT_DIRECTION",
                                "sort direction must be asc or desc"))
                : Sort.Direction.ASC;
        return PageRequest.of(page, size, Sort.by(direction, field));
    }

    private Pageable claimsPageable(int page, int size, String sortValue) {
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        String[] parts = sortValue.split(",", 2);
        String field = parts[0];
        String property = CLAIM_SORT_FIELDS.get(field);
        if (property == null) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_SORT_FIELD",
                    "sort field must be createdAt, updatedAt, status, itemName, eventDate, claimantEmail or reportOwnerEmail");
        }
        Sort.Direction direction = parts.length == 2
                ? Sort.Direction.fromOptionalString(parts[1]).orElseThrow(() ->
                        new LostFoundApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "INVALID_SORT_DIRECTION",
                                "sort direction must be asc or desc"))
                : Sort.Direction.ASC;
        // 同排序值下的分页漂移用 id 作为稳定次要排序
        return PageRequest.of(page, size, Sort.by(direction, property).and(Sort.by(Sort.Direction.DESC, "id")));
    }

    private Pageable auditPageable(int page, int size, String sortValue) {
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        String[] parts = sortValue.split(",", 2);
        String field = parts[0];
        if (!ALLOWED_AUDIT_SORT_FIELDS.contains(field)) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_SORT_FIELD",
                    "audit log sort field must be createdAt");
        }
        Sort.Direction direction = parts.length == 2
                ? Sort.Direction.fromOptionalString(parts[1]).orElseThrow(() ->
                        new LostFoundApiException(
                                HttpStatus.UNPROCESSABLE_ENTITY,
                                "INVALID_SORT_DIRECTION",
                                "sort direction must be asc or desc"))
                : Sort.Direction.DESC;
        // 同 createdAt 的分页漂移用 id 作为稳定次要排序
        return PageRequest.of(page, size, Sort.by(direction, field).and(Sort.by(Sort.Direction.DESC, "id")));
    }
}
