package com.app.campusagent.lostfound.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 管理员对报告执行写操作（下架/恢复/删除）时必填的操作原因。 */
public record AdminReportActionRequest(
        @NotBlank(message = "reason is required")
        @Size(max = 500, message = "reason must be at most 500 characters")
        String reason) {
}
