package com.app.campusagent.lostfound.controller;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.dto.admin.AdminLostFoundOverviewResponse;
import com.app.campusagent.lostfound.dto.admin.AdminLostFoundReportResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.service.LostFoundAdminService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/lost-found")
@PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
public class LostFoundAdminController {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "updatedAt", "eventDate", "itemName");

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
                pageable(page, size, sort));
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
}
