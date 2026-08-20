/**
 * 管理后台【认领列表 - 被认领报告摘要】嵌套 DTO（dto/admin 子包）。
 *
 * <p>作为 AdminClaimSummaryResponse.report 的嵌套结构出现在认领列表项中，
 * 携带报告关键字段供列表页快速浏览；不含 description 与 images（由详情接口提供）。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;

import java.time.LocalDate;

public record AdminClaimReportSummary(
        // 报告主键
        Long id,
        // 报告类型：LOST 失物 / FOUND 拾获
        ReportType reportType,
        // 物品名称
        String itemName,
        // 物品分类（ItemCategory 枚举）
        ItemCategory category,
        // 物品颜色（归一化后的规范色值）
        String colour,
        // 拾获/丢失地点
        String location,
        // 事件发生日期
        LocalDate eventDate,
        // 报告状态：OPEN / CLAIMED / CLOSED
        ReportStatus status,
        // 是否被管理员下架隐藏
        boolean adminHidden,
        // 报告发布者摘要（嵌套 AdminClaimUserSummary：id/邮箱）
        AdminClaimUserSummary owner) {
}
