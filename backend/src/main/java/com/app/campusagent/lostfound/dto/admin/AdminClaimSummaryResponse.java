/**
 * 管理后台【认领列表项】响应 DTO（dto/admin 子包）。
 *
 * <p>对应 {@code GET /api/admin/lost-found/claims} 分页列表的每一项，
 * 返回认领申请的核心信息以及嵌套的认领人/被认领报告摘要，
 * 支持状态、关键词、报告、邮箱与下架等筛选条件。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.ClaimStatus;

import java.time.Instant;

public record AdminClaimSummaryResponse(
        // 认领申请主键
        Long id,
        // 认领状态（ClaimStatus 枚举）：SUBMITTED / APPROVED / REJECTED
        ClaimStatus status,
        // 认领证明的摘要：超长截断为 120 字符，完整证明见详情接口
        String proofSummary,
        // 管理员的审核备注
        String decisionNote,
        // 认领人摘要（嵌套 AdminClaimUserSummary：id/邮箱）
        AdminClaimUserSummary claimant,
        // 被认领报告摘要（嵌套 AdminClaimReportSummary）
        AdminClaimReportSummary report,
        // 认领提交时间
        Instant createdAt,
        // 认领最后更新时间（审批后更新）
        Instant updatedAt) {
}
