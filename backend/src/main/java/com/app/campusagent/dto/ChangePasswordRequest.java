package com.app.campusagent.dto;

/**
 * 修改登录密码请求。校验在 service 层完成：
 * 当前密码须与库中一致，新密码长度 6-64 字符。
 */
public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
