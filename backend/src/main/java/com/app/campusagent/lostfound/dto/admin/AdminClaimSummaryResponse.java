package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.ClaimStatus;

import java.time.Instant;

public record AdminClaimSummaryResponse(
        Long id,
        ClaimStatus status,
        String proofSummary,
        String decisionNote,
        AdminClaimUserSummary claimant,
        AdminClaimReportSummary report,
        Instant createdAt,
        Instant updatedAt) {
}
