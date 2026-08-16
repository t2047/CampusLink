package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LostFoundReportResponse(
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
        List<LostFoundImageResponse> images,
        boolean createdByMe,
        boolean adminHidden,
        Instant createdAt,
        Instant updatedAt) {
}
