package com.app.campusagent.dto;

/**
 * 当前用户资料（个人中心需求 §9.3）。
 *
 * <p>avatarUrl 为可公开回显的代理路径（如 {@code /api/users/avatar/avatar-{uuid}.jpg}），
 * null 表示未上传头像；nickname 为原始值，null 时前端回退为 email 前缀展示。</p>
 */
public record UserProfileResponse(
        String email,
        String role,
        String nickname,
        String avatarUrl) {
}
