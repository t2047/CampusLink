package com.app.campusagent.lostfound.dto.memory;

import com.app.campusagent.lostfound.domain.LfFactStatus;
import com.app.campusagent.lostfound.domain.LfFactType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** 写入/合并一条用户级长期事实请求。 */
public record MemoryUpsertFactRequest(
        @NotNull LfFactType factType,
        @Size(max = 100) String itemName,
        @Size(max = 50) String category,
        @Size(max = 50) String colour,
        @Size(max = 200) String location,
        LocalDate eventDate,
        @Size(max = 100) String timeDescription,
        LfFactStatus status,
        Float confidence,
        @Size(max = 200) String sessionId) {
}
