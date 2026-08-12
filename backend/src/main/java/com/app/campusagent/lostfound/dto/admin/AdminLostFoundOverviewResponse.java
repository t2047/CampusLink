package com.app.campusagent.lostfound.dto.admin;

public record AdminLostFoundOverviewResponse(
        long totalReports,
        long openReports,
        long claimedReports,
        long closedReports,
        long lostReports,
        long foundReports,
        long submittedClaims,
        long hiddenReports) {
}
