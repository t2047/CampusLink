/**
 * 管理后台【认领详情】响应 DTO（dto/admin 子包）。
 *
 * <p>对应 {@code GET /api/admin/lost-found/claims/{claimId}} 的响应体，
 * 返回单个认领申请的完整信息：认领人、被认领报告（含图片预览）以及审核信息。
 * 由 LostFoundAdminService.getClaimDetail 返回，批准/拒绝操作后也返回该结构。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.ClaimStatus;

import java.time.Instant;

public record AdminClaimDetailResponse(
        // 认领申请主键
        Long id,
        // 认领状态（ClaimStatus 枚举）：SUBMITTED 待审核 / APPROVED 已批准 / REJECTED 已拒绝
        ClaimStatus status,
        // 认领者提交的完整证明描述，用于佐证其对物品的所有权
        String proofDescription,
        // 管理员的审核备注；批准/拒绝时由 AdminClaimDecisionRequest.decisionNote 写入
        String decisionNote,
        // 认领人的完整信息（嵌套 AdminClaimUserDetail：id/邮箱/角色）
        AdminClaimUserDetail claimant,
        // 被认领报告详情（嵌套 AdminClaimReportDetail：物品信息 + 图片列表 + 发布者）
        AdminClaimReportDetail report,
        // 审核信息（嵌套 AdminClaimReviewInfo：是否已审核/备注/审核时间）
        AdminClaimReviewInfo review,
        // 认领提交时间
        Instant createdAt,
        // 认领最后更新时间（审批后更新）
        Instant updatedAt) {
}
