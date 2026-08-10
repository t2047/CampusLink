package com.app.campusagent.lostfound.dto.admin;

import jakarta.validation.constraints.Size;

/** 管理员审核认领的备注：批准时可选，拒绝时由 Service 层校验非空。 */
public record AdminClaimDecisionRequest(
        @Size(max = 500, message = "decisionNote must be at most 500 characters")
        String decisionNote) {
}
