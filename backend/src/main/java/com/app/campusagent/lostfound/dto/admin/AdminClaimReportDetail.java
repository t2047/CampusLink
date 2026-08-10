package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.LostFoundImageResponse;

import java.time.LocalDate;
import java.util.List;

public record AdminClaimReportDetail(
        Long id,
        ReportType reportType,
        String itemName,
        ItemCategory category,
        String description,
        String colour,
        String location,
        LocalDate eventDate,
        String timeDescription,
        ReportStatus status,
        boolean adminHidden,
        AdminClaimUserSummary owner,
        List<LostFoundImageResponse> images) {
}
