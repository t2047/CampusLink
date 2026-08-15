package com.app.campusagent.lostfound.dto.memory;

import com.app.campusagent.lostfound.domain.LfFactStatus;
import com.app.campusagent.lostfound.domain.LfFactType;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 用户级长期事实响应。 */
public record MemoryFactResponse(
        Long id,
        LfFactType factType,
        String itemName,
        String category,
        String colour,
        String location,
        LocalDate eventDate,
        String timeDescription,
        LfFactStatus status,
        Float confidence,
        LocalDateTime updatedAt) {
}
