package com.app.campusagent.lostfound.dto.admin;

import java.time.Instant;

public record AdminClaimReviewInfo(
        boolean reviewed,
        String decisionNote,
        Instant reviewedAt) {
}
