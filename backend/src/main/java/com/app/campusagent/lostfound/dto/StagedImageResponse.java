package com.app.campusagent.lostfound.dto;

/**
 * Agent 图片暂存接口响应：登录用户在 Agent 面板选中图片后调用
 * {@code POST /api/lost-found/agent/upload-image}，后端上传 MinIO 暂存区并返回
 * objectKey / 指纹 / 可回显的代理 URL。确认创建时由 Agent 把 objectKey 列表交给内部 API。
 */
public record StagedImageResponse(
        String objectKey,
        String visualFingerprint,
        String url,
        String contentType,
        String originalName,
        long fileSize) {
}
