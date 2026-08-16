package com.app.campusagent.dto;

/**
 * 昵称更新请求（个人中心需求 §9.3）。校验在 service 层完成：
 * 去除首尾空白后长度须为 1-30 字符。
 */
public record UpdateProfileRequest(String nickname) {
}
