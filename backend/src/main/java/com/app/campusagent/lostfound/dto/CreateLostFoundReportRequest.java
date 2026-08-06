package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateLostFoundReportRequest(
        @NotNull ReportType reportType,
        @NotBlank @Size(min = 3, max = 100) String itemName,
        @NotNull ItemCategory category,
        @NotBlank @Size(min = 10, max = 2000) String description,
        @Size(max = 50) String colour,
        @NotBlank @Size(max = 200) String location,
        @NotNull @PastOrPresent LocalDate eventDate,
        @Size(max = 100) String timeDescription) {

    public CreateLostFoundReportRequest {
        itemName = trim(itemName);
        description = trim(description);
        colour = trimToNull(colour);
        location = trim(location);
        timeDescription = trimToNull(timeDescription);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed == null || trimmed.isEmpty() ? null : trimmed;
    }
}
