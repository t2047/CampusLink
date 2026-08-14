package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.LostFoundImage;

public record LostFoundImageResponse(
        Long id,
        String url,
        String contentType,
        long fileSize,
        int sortOrder) {

    /**
     * 统一构造图片响应：url 一律指向同源代理端点 /api/lost-found/images/{id}，
     * 由后端从 MinIO 读取并返回字节流，避免把 Docker 内网 MinIO 地址/预签名 URL 下发给浏览器。
     */
    public static LostFoundImageResponse of(LostFoundImage image) {
        return new LostFoundImageResponse(
                image.getId(),
                "/api/lost-found/images/" + image.getId(),
                image.getContentType(),
                image.getFileSize(),
                image.getSortOrder());
    }
}
