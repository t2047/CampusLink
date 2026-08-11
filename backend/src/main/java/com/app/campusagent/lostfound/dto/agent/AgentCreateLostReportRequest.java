package com.app.campusagent.lostfound.dto.agent;

import com.app.campusagent.lostfound.domain.ItemCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record AgentCreateLostReportRequest(
        // min=2：中文物品名常为 2 字符（钥匙/钱包），与 Agent 端提取口径一致
        @NotBlank @Size(min = 2, max = 100) String itemName,
        @NotNull ItemCategory category,
        @NotBlank @Size(min = 10, max = 2000) String description,
        @Size(max = 50) String colour,
        @NotBlank @Size(max = 200) String location,
        @NotNull @PastOrPresent LocalDate eventDate,
        @Size(max = 100) String timeDescription) {
}
