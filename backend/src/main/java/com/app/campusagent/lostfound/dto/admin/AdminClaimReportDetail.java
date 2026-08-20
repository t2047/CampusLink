/**
 * 管理后台【认领详情 - 被认领报告】嵌套 DTO（dto/admin 子包）。
 *
 * <p>作为 AdminClaimDetailResponse.report 的嵌套结构出现，携带报告完整详情与图片预览，
 * 供管理员在审批界面查看物品全貌后作出决定。</p>
 */
package com.app.campusagent.lostfound.dto.admin;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.dto.LostFoundImageResponse;

import java.time.LocalDate;
import java.util.List;

public record AdminClaimReportDetail(
        // 报告主键
        Long id,
        // 报告类型：LOST 失物 / FOUND 拾获（只有 FOUND 报告可被认领）
        ReportType reportType,
        // 物品名称
        String itemName,
        // 物品分类（ItemCategory 枚举）
        ItemCategory category,
        // 物品详细描述
        String description,
        // 物品颜色（归一化后的规范色值）
        String colour,
        // 拾获/丢失地点
        String location,
        // 事件发生日期
        LocalDate eventDate,
        // 事件的补充时间描述（如 "下午 3 点"），可为空
        String timeDescription,
        // 报告状态：OPEN 开放中 / CLAIMED 已被认领 / CLOSED 已关闭
        ReportStatus status,
        // 是否被管理员下架隐藏（下架后对普通用户不可见）
        boolean adminHidden,
        // 报告发布者摘要（嵌套 AdminClaimUserSummary：id/邮箱）
        AdminClaimUserSummary owner,
        // 报告图片列表（嵌套 LostFoundImageResponse，含同源代理 URL），按 sortOrder 排序
        List<LostFoundImageResponse> images) {
}
