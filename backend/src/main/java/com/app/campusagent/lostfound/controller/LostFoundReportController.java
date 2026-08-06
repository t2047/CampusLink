package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.CreateLostFoundReportRequest;
import com.app.campusagent.lostfound.dto.LostFoundMetadataResponse;
import com.app.campusagent.lostfound.dto.LostFoundReportResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.service.LostFoundReportService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/lost-found")
public class LostFoundReportController {

    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "eventDate", "itemName");

    private final LostFoundReportService reportService;

    public LostFoundReportController(LostFoundReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping(value = "/reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<LostFoundReportResponse> create(
            @Valid @RequestPart("report") CreateLostFoundReportRequest request,
            @RequestPart(value = "images", required = false) List<MultipartFile> images,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reportService.create(request, images, currentUser));
    }

    @GetMapping("/reports")
    public PageResponse<LostFoundReportResponse> search(
            @RequestParam(required = false) ReportType reportType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ItemCategory category,
            @RequestParam(required = false) String colour,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @AuthenticationPrincipal User currentUser) {
        return reportService.search(
                reportType,
                keyword,
                category,
                colour,
                location,
                dateFrom,
                dateTo,
                status,
                pageable(page, size, sort),
                currentUser);
    }

    @GetMapping("/reports/{reportId}")
    public LostFoundReportResponse getById(
            @PathVariable Long reportId,
            @AuthenticationPrincipal User currentUser) {
        return reportService.getById(reportId, currentUser);
    }

    @GetMapping("/metadata")
    public LostFoundMetadataResponse metadata() {
        return new LostFoundMetadataResponse(
                names(ReportType.values()),
                names(ItemCategory.values()),
                names(ReportStatus.values()),
                names(ClaimStatus.values()));
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
                    "sort field must be createdAt, eventDate or itemName");
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

    private List<String> names(Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }
}
