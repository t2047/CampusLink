package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.ClaimStatus;

import java.time.Instant;

public record AdminClaimDetailResponse(
        Long id,
        ClaimStatus status,
        String proofDescription,
        String decisionNote,
        AdminClaimUserDetail claimant,
        AdminClaimReportDetail report,
        AdminClaimReviewInfo review,
        Instant createdAt,
        Instant updatedAt) {
}
