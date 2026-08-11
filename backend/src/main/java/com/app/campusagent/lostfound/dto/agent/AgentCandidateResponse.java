package com.app.campusagent.lostfound.dto.agent;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.domain.ReportStatus;

import java.time.LocalDate;
import java.util.List;

public record AgentCandidateResponse(
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
        List<String> imageUrls,
        List<String> visualFingerprints) {
}
