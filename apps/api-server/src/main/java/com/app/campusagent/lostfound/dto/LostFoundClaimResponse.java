package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.ClaimStatus;

import java.time.Instant;

public record LostFoundClaimResponse(
        Long id,
        ClaimReportSummary report,
        String proofDescription,
        ClaimStatus status,
        String decisionNote,
        boolean submittedByMe,
        Instant createdAt,
        Instant updatedAt) {
}
