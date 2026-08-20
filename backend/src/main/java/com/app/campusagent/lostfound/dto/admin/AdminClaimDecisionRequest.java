/**
 * 管理后台【认领审批】请求 DTO（dto/admin 子包）。
 *
 * <p>对应 {@code POST /api/admin/lost-found/claims/{claimId}/approve} 与
 * {@code POST /api/admin/lost-found/claims/{claimId}/reject} 两个接口的请求体，
 * 携带管理员对认领申请作出的审核备注。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

import jakarta.validation.constraints.Size;

/** 管理员审核认领的备注：批准时可选，拒绝时由 Service 层校验非空。 */
public record AdminClaimDecisionRequest(
        // 审核备注：批准认领时可选（可为空）；拒绝认领时必填，
        // 由 Service 层（LostFoundAdminService.rejectClaim）校验非空，
        // 备注会写入 claim 的 decision_note 并展示给认领人
        @Size(max = 500, message = "decisionNote must be at most 500 characters")
        String decisionNote) {
}
