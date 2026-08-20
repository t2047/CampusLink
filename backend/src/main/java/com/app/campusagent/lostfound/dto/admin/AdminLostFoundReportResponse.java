/**
 * 管理后台【报告列表项 / 操作响应】响应 DTO（dto/admin 子包）。
 *
 * <p>用于 {@code GET /api/admin/lost-found/reports} 搜索分页列表的每一项，
 * 同时也是下架（delist）与恢复（restore）接口的响应体，
 * 返回报告关键信息、管理员下架标记与发布人邮箱。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;

import java.time.Instant;
import java.time.LocalDate;

public record AdminLostFoundReportResponse(
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
        // 报告发布者邮箱，用于定位/联系发布人
        String createdByEmail,
        // 报告创建时间
        Instant createdAt,
        // 报告最后更新时间
        Instant updatedAt) {
}
