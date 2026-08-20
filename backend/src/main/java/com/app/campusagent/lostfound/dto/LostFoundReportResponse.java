/**
 * 失物招领单详情响应 DTO（响应体）。
 * <p>
 * 返回一条失物 / 招领单的完整信息，用于详情页、列表页、个人中心"我发布的"等场景，
 * 使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record LostFoundReportResponse(
        Long id,                             // 招领单 ID
        ReportType reportType,               // 报告类型（LOST 丢失 / FOUND 招领）
        String itemName,                     // 物品名称
        ItemCategory category,               // 物品类别
        String description,                  // 物品详细描述
        String colour,                       // 物品主颜色（可为空）
        String location,                     // 丢失/拾获地点
        LocalDate eventDate,                 // 发生日期
        String timeDescription,              // 时间段描述（可为空）
        ReportStatus status,                 // 招领单当前状态
        List<LostFoundImageResponse> images, // 关联的图片列表（按 sortOrder 有序）
        boolean createdByMe,                 // 是否由当前登录用户本人发布，前端据此决定是否显示编辑/删除入口
        boolean adminHidden,                 // 是否被管理员隐藏，用于告知用户当前内容的可见性
        Instant createdAt,                   // 创建时间
        Instant updatedAt) {                 // 最近更新时间
}
