/**
 * 失物招领单图片响应 DTO（响应体）。
 * <p>
 * 返回一条失物招领单关联的图片信息，使用 Java record 表示；
 * 通常通过静态工厂方法 {@link #of(LostFoundImage)} 从领域对象转换而来。
 */
package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.LostFoundImage;

public record LostFoundImageResponse(
        Long id,            // 图片记录 ID
        String url,         // 图片访问地址（同源代理端点）
        String contentType, // 图片 MIME 类型（如 image/jpeg）
        long fileSize,      // 图片文件大小（字节）
        int sortOrder) {    // 排序序号，用于决定同一单据下多张图片的展示顺序

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
