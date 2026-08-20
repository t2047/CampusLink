/**
 * 认领申请详情响应 DTO（响应体）。
 * <p>
 * 返回单条认领申请的完整信息，供失主在用户中心"认领管理"页审核认领申请，
 * 或用户查看自己提交的认领记录时使用，使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.ClaimStatus;

import java.time.Instant;

public record LostFoundClaimResponse(
        Long id,                          // 认领申请 ID
        ClaimReportSummary report,        // 被认领的招领单摘要（见 ClaimReportSummary）
        String proofDescription,          // 认领者提交的证明材料描述
        ClaimStatus status,               // 认领申请状态（PENDING / APPROVED / REJECTED 等）
        String decisionNote,              // 失主审核时的备注（尚未审核时为 null）
        boolean submittedByMe,            // 是否由当前登录用户本人提交，前端据此决定能否取消/查看操作
        Instant createdAt,                // 认领申请创建时间
        Instant updatedAt) {              // 认领申请最近更新时间
}
