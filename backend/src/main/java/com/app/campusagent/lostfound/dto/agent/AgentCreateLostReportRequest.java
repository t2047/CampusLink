package com.app.campusagent.lostfound.dto.agent;

import com.app.campusagent.lostfound.domain.ItemCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record AgentCreateLostReportRequest(
        @NotBlank @Size(min = 3, max = 100) String itemName,
        @NotNull ItemCategory category,
        @NotBlank @Size(min = 10, max = 2000) String description,
        @Size(max = 50) String colour,
        @NotBlank @Size(max = 200) String location,
        @NotNull @PastOrPresent LocalDate eventDate,
        @Size(max = 100) String timeDescription) {
}
