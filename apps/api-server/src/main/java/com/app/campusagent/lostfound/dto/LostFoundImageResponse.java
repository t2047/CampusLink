package com.app.campusagent.lostfound.dto;

public record LostFoundImageResponse(
        Long id,
        String url,
        String contentType,
        long fileSize,
        int sortOrder) {
}
