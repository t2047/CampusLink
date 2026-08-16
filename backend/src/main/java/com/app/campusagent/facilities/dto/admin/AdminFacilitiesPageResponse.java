package com.app.campusagent.facilities.dto.admin;

import org.springframework.data.domain.Page;

import java.util.List;

public record AdminFacilitiesPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static <T> AdminFacilitiesPageResponse<T> from(Page<T> page) {
        return new AdminFacilitiesPageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
