/**
 * 管理后台【审计日志】响应 DTO（dto/admin 子包）。
 *
 * <p>对应 {@code GET /api/admin/lost-found/audit-logs} 分页列表的每一项，
 * 展示失物招领报告级写操作（创建/更新/下架/恢复/删除/认领审批等）的审计记录。
 * 审计行在报告被删除后仍保留，便于事后追溯。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.LostFoundAuditAction;

import java.time.Instant;

public record AdminAuditLogResponse(
        // 审计日志主键
        Long id,
        // 被审计的报告 id；报告删除后仍指向原 id，用于关联追溯
        Long reportId,
        // 审计时记录的报告物品名快照，报告删除后依然可读
        String itemName,
        // 审计动作类型（LostFoundAuditAction 枚举），如 REPORT_DELISTED / CLAIM_APPROVED_BY_ADMIN
        LostFoundAuditAction action,
        // 触发该操作的操作人邮箱（管理员或报告发布者）
        String actorEmail,
        // 操作原因：管理员下架/恢复/删除时由 AdminReportActionRequest.reason 传入，可为空
        String reason,
        // 结构化变更细节快照，如 "adminHidden=false→true"、"status=OPEN→CLOSED"
        String detail,
        // 审计记录写入时间
        Instant createdAt) {
}
