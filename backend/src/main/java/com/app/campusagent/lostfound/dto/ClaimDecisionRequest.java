/**
 * 认领审核决定请求 DTO（请求体）。
 * <p>
 * 失主（招领单发布者）在用户中心"认领管理"页面对某条认领申请做出审核决定
 * （通过 / 拒绝）时提交的请求体，使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

import jakarta.validation.constraints.Size;

// decisionNote：审核备注，说明通过或拒绝该认领的理由，可空；
// @Size(max = 500) 限制最长 500 字符，避免过长的说明影响列表展示与日志可读性
public record ClaimDecisionRequest(@Size(max = 500) String decisionNote) {
}
