package com.app.campusagent.lostfound.controller;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.CreateClaimRequest;
import com.app.campusagent.lostfound.dto.CreateLostFoundReportRequest;
import com.app.campusagent.lostfound.dto.LostFoundClaimResponse;
import com.app.campusagent.lostfound.dto.LostFoundReportResponse;
import com.app.campusagent.lostfound.dto.PageResponse;
import com.app.campusagent.lostfound.dto.agent.AgentCandidateResponse;
import com.app.campusagent.lostfound.dto.agent.AgentCreateFoundReportRequest;
import com.app.campusagent.lostfound.dto.agent.AgentCreateLostReportRequest;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.service.LostFoundClaimService;
import com.app.campusagent.lostfound.service.LostFoundReportService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
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
import java.util.List;

@RestController
@RequestMapping("/api/internal/lost-found")
@PreAuthorize("hasRole('AGENT_LOST_FOUND')")
public class LostFoundAgentInternalController {

    private final LostFoundReportService reportService;
    private final LostFoundClaimService claimService;

    public LostFoundAgentInternalController(
            LostFoundReportService reportService,
            LostFoundClaimService claimService) {
        this.reportService = reportService;
        this.claimService = claimService;
    }

    @PostMapping("/reports/lost")
    public ResponseEntity<LostFoundReportResponse> reportLost(
            @Valid @RequestBody AgentCreateLostReportRequest request,
            @AuthenticationPrincipal User currentUser) {
        CreateLostFoundReportRequest serviceRequest = new CreateLostFoundReportRequest(
                ReportType.LOST,
                request.itemName(),
                request.category(),
                request.description(),
                request.colour(),
                request.location(),
                request.eventDate(),
                request.timeDescription());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reportService.create(serviceRequest, List.of(), currentUser));
    }

    @PostMapping("/reports/found")
    public ResponseEntity<LostFoundReportResponse> reportFound(
            @Valid @RequestBody AgentCreateFoundReportRequest request,
            @AuthenticationPrincipal User currentUser) {
        CreateLostFoundReportRequest serviceRequest = new CreateLostFoundReportRequest(
                ReportType.FOUND,
                request.itemName(),
                request.category(),
                request.description(),
                request.colour(),
                request.location(),
                request.eventDate(),
                request.timeDescription());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(reportService.create(serviceRequest, List.of(), currentUser));
    }

    @GetMapping("/candidates")
    public PageResponse<AgentCandidateResponse> candidates(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) ItemCategory category,
            @RequestParam(required = false) String colour,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new LostFoundApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "INVALID_PAGINATION",
                    "page must be at least 0 and size must be between 1 and 100");
        }
        return reportService.searchCandidates(
                keyword,
                category,
                colour,
                location,
                dateFrom,
                dateTo,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/reports/{reportId}")
    public LostFoundReportResponse detail(
            @PathVariable Long reportId,
            @AuthenticationPrincipal User currentUser) {
        return reportService.getById(reportId, currentUser);
    }

    @PostMapping("/reports/{reportId}/claims")
    public ResponseEntity<LostFoundClaimResponse> claim(
            @PathVariable Long reportId,
            @Valid @RequestBody CreateClaimRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(claimService.create(reportId, request, currentUser));
    }
}
