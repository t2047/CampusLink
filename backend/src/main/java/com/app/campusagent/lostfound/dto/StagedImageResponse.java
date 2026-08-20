/**
 * Agent 图片暂存接口响应 DTO（响应体）。
 * <p>
 * 由 {@code POST /api/lost-found/agent/upload-image} 接口返回，使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

/**
 * Agent 图片暂存接口响应：登录用户在 Agent 面板选中图片后调用
 * {@code POST /api/lost-found/agent/upload-image}，后端上传 MinIO 暂存区并返回
 * objectKey / 指纹 / 可回显的代理 URL。确认创建时由 Agent 把 objectKey 列表交给内部 API。
 */
public record StagedImageResponse(
        String objectKey,         // MinIO 暂存区的对象键，创建失物招领单时凭它提交给后端内部 API 确认
        String visualFingerprint, // 图片视觉指纹（感知哈希），用于以图搜物的去重 / 匹配
        String url,               // 可回显的代理 URL，供 Agent 面板预览暂存的图片
        String contentType,       // 图片 MIME 类型
        String originalName,      // 上传时的原始文件名
        long fileSize,            // 文件大小（字节）
        String embeddingStatus) { // 向量化状态：BASELINE 表示尚未做向量嵌入，此类图片以图搜物时走基础匹配

    public StagedImageResponse(
            String objectKey,
            String visualFingerprint,
            String url,
            String contentType,
            String originalName,
            long fileSize) {
        // 精简构造器：调用方未显式提供 embeddingStatus 时，默认置为 "BASELINE"（未嵌入、基础匹配）
        this(objectKey, visualFingerprint, url, contentType, originalName, fileSize, "BASELINE");
    }
}
