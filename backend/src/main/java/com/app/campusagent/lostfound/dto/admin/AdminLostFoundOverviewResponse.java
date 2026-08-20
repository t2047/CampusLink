/**
 * 管理后台【仪表盘概览】响应 DTO（dto/admin 子包）。
 *
 * <p>对应 {@code GET /api/admin/lost-found/overview} 的响应体，
 * 用一组计数统计失物招领全貌，供管理后台首页/看板展示。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

public record AdminLostFoundOverviewResponse(
        // 报告总数（LOST + FOUND，含已关闭/下架）
        long totalReports,
        // 状态为 OPEN（开放中）的报告数
        long openReports,
        // 状态为 CLAIMED（已被认领）的报告数
        long claimedReports,
        // 状态为 CLOSED（已关闭）的报告数
        long closedReports,
        // LOST 类型（失物）报告数
        long lostReports,
        // FOUND 类型（拾获）报告数
        long foundReports,
        // 待审核认领数（claim 状态 = SUBMITTED）
        long submittedClaims,
        // 已处理认领数（claim 状态 = APPROVED + REJECTED）
        long processedClaims,
        // 被管理员下架隐藏的报告数（adminHidden = true）
        long hiddenReports) {
}
