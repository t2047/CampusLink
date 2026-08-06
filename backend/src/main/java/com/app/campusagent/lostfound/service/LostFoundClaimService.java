package com.app.campusagent.lostfound.service;

import com.app.campusagent.domain.User;
import com.app.campusagent.lostfound.domain.ClaimStatus;
import com.app.campusagent.lostfound.domain.LostFoundClaim;
import com.app.campusagent.lostfound.domain.LostFoundReport;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.ClaimDecisionRequest;
import com.app.campusagent.lostfound.dto.ClaimReportSummary;
import com.app.campusagent.lostfound.dto.CreateClaimRequest;
import com.app.campusagent.lostfound.dto.LostFoundClaimResponse;
import com.app.campusagent.lostfound.exception.LostFoundApiException;
import com.app.campusagent.lostfound.repository.LostFoundClaimRepository;
import com.app.campusagent.lostfound.repository.LostFoundReportRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
public class LostFoundClaimService {

    private final LostFoundClaimRepository claimRepository;
    private final LostFoundReportRepository reportRepository;

    public LostFoundClaimService(
            LostFoundClaimRepository claimRepository,
            LostFoundReportRepository reportRepository) {
        this.claimRepository = claimRepository;
        this.reportRepository = reportRepository;
    }

    @Transactional
    public LostFoundClaimResponse create(
            Long reportId,
            CreateClaimRequest request,
            User currentUser) {
        LostFoundReport report = requireReport(reportId);
        if (report.getReportType() != ReportType.FOUND) {
            throw conflict("ONLY_FOUND_REPORTS_CAN_BE_CLAIMED", "Only found-item reports can be claimed");
        }
        if (report.getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_OPEN", "This report is no longer open for claims");
        }
        if (report.getCreatedBy().getId().equals(currentUser.getId())) {
            throw conflict("CANNOT_CLAIM_OWN_REPORT", "You cannot claim an item that you reported");
        }
        boolean duplicate = claimRepository.existsByReportIdAndClaimantIdAndStatusIn(
                reportId,
                currentUser.getId(),
                List.of(ClaimStatus.SUBMITTED, ClaimStatus.APPROVED));
        if (duplicate) {
            throw conflict("CLAIM_ALREADY_EXISTS", "You already have an active claim for this item");
        }

        LostFoundClaim claim = claimRepository.save(new LostFoundClaim(
                report,
                currentUser,
                request.proofDescription().trim()));
        return toResponse(claim, currentUser);
    }

    @Transactional(readOnly = true)
    public List<LostFoundClaimResponse> mine(User currentUser) {
        return claimRepository.findByClaimantIdOrderByCreatedAtDesc(currentUser.getId()).stream()
                .map(claim -> toResponse(claim, currentUser))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LostFoundClaimResponse> received(User currentUser) {
        return claimRepository.findByReportCreatedByIdOrderByCreatedAtDesc(currentUser.getId()).stream()
                .map(claim -> toResponse(claim, currentUser))
                .toList();
    }

    @Transactional
    public LostFoundClaimResponse approve(
            Long claimId,
            ClaimDecisionRequest request,
            User currentUser) {
        LostFoundClaim claim = requireClaim(claimId);
        assertCanReview(claim, currentUser);
        assertSubmitted(claim);
        if (claim.getReport().getStatus() != ReportStatus.OPEN) {
            throw conflict("REPORT_NOT_OPEN", "This report is no longer open for claims");
        }

        List<LostFoundClaim> pending = claimRepository.findByReportIdAndStatus(
                claim.getReport().getId(), ClaimStatus.SUBMITTED);
        String note = trimToNull(request.decisionNote());
        claim.approve(note);
        for (LostFoundClaim pendingClaim : pending) {
            if (!pendingClaim.getId().equals(claimId)) {
                pendingClaim.reject("Another claim was approved");
            }
        }
        claim.getReport().markClaimed();
        reportRepository.save(claim.getReport());
        claimRepository.saveAll(pending);
        return toResponse(claim, currentUser);
    }

    @Transactional
    public LostFoundClaimResponse reject(
            Long claimId,
            ClaimDecisionRequest request,
            User currentUser) {
        LostFoundClaim claim = requireClaim(claimId);
        assertCanReview(claim, currentUser);
        assertSubmitted(claim);
        claim.reject(trimToNull(request.decisionNote()));
        return toResponse(claimRepository.save(claim), currentUser);
    }

    private LostFoundReport requireReport(Long reportId) {
        return reportRepository.findById(reportId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "LOST_FOUND_REPORT_NOT_FOUND",
                        "The requested report does not exist"));
    }

    private LostFoundClaim requireClaim(Long claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new LostFoundApiException(
                        HttpStatus.NOT_FOUND,
                        "CLAIM_NOT_FOUND",
                        "The requested claim does not exist"));
    }

    private void assertCanReview(LostFoundClaim claim, User currentUser) {
        if (!claim.getReport().getCreatedBy().getId().equals(currentUser.getId())) {
            throw new LostFoundApiException(
                    HttpStatus.FORBIDDEN,
                    "CLAIM_REVIEW_FORBIDDEN",
                    "Only the found-item reporter can review this claim");
        }
    }

    private void assertSubmitted(LostFoundClaim claim) {
        if (claim.getStatus() != ClaimStatus.SUBMITTED) {
            throw conflict("CLAIM_ALREADY_DECIDED", "This claim has already been decided");
        }
    }

    private LostFoundClaimResponse toResponse(LostFoundClaim claim, User currentUser) {
        LostFoundReport report = claim.getReport();
        return new LostFoundClaimResponse(
                claim.getId(),
                new ClaimReportSummary(
                        report.getId(),
                        report.getItemName(),
                        report.getCategory(),
                        report.getLocation(),
                        report.getStatus()),
                claim.getProofDescription(),
                claim.getStatus(),
                claim.getDecisionNote(),
                claim.getClaimant().getId().equals(currentUser.getId()),
                claim.getCreatedAt(),
                claim.getUpdatedAt());
    }

    private LostFoundApiException conflict(String code, String message) {
        return new LostFoundApiException(HttpStatus.CONFLICT, code, message);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
