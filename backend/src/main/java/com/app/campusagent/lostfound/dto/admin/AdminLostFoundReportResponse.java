package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;

import java.time.Instant;
import java.time.LocalDate;

public record AdminLostFoundReportResponse(
        Long id,
        ReportType reportType,
        String itemName,
        ItemCategory category,
        String colour,
        String location,
        LocalDate eventDate,
        ReportStatus status,
        String createdByEmail,
        Instant createdAt,
        Instant updatedAt) {
}
