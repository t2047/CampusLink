/**
 * 管理后台【认领审核信息】嵌套 DTO（dto/admin 子包）。
 *
 * <p>作为 AdminClaimDetailResponse.review 的嵌套结构出现，
 * 汇总认领申请是否已审核以及审核结果，供审批界面展示。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

import java.time.Instant;

public record AdminClaimReviewInfo(
        // 是否已审核：status != SUBMITTED 即为 true（已批准或已拒绝）
        boolean reviewed,
        // 审核备注（与 decisionNote 一致，重复字段便于前端直接取用）
        String decisionNote,
        // 审核时间：优先 reviewed_at，历史已审核数据回退到 updatedAt
        Instant reviewedAt) {
}
