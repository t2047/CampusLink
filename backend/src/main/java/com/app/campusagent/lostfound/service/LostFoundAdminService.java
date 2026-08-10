package com.app.campusagent.lostfound.service;

import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.dto.admin.AdminLostFoundOverviewResponse;
import com.app.campusagent.lostfound.dto.admin.AdminLostFoundReportResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class LostFoundAdminService {

    private final LostFoundReportRepository reportRepository;
    private final LostFoundClaimRepository claimRepository;

    public LostFoundAdminService(
            LostFoundReportRepository reportRepository,
            LostFoundClaimRepository claimRepository) {
        this.reportRepository = reportRepository;
        this.claimRepository = claimRepository;
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
                claimRepository.countByStatus(ClaimStatus.SUBMITTED));
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
                report.getCreatedBy().getEmail(),
                report.getCreatedAt(),
                report.getUpdatedAt());
    }

    private String likePattern(String value) {
        return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }
}
