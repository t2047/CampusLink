package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;

public record ClaimReportSummary(
        Long id,
        String itemName,
        ItemCategory category,
        String location,
        ReportStatus status) {
}
