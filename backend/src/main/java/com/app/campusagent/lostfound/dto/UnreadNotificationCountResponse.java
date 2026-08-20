/**
 * 未读通知数响应 DTO（响应体）。
 * <p>
 * 用户中心顶栏 / 通知入口展示未读角标数量时返回的响应体，使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

// unread：未读通知数量
public record UnreadNotificationCountResponse(long unread) {
}
