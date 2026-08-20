/**
 * 管理后台【报告管理操作】请求 DTO（dto/admin 子包）。
 *
 * <p>对应 {@code POST /api/admin/lost-found/reports/{reportId}/delist|restore|delete}
 * 三个写操作接口的请求体，仅携带一条必填的操作原因，用于审计留痕。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员对报告执行写操作（下架/恢复/删除）时必填的操作原因。 */
public record AdminReportActionRequest(
        // 操作原因：下架/恢复/删除报告时必填，随审计日志持久化（AdminAuditLogResponse.reason）
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason) {
}
