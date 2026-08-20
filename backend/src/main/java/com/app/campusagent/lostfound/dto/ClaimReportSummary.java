/**
 * 认领摘要 DTO（响应体）。
 * <p>
 * 在失主审核认领申请、或用户查看认领列表时，用于呈现被认领的那条失物招领单的
 * 精简摘要信息（只返回关键字段，不暴露完整详情），使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportStatus;

public record ClaimReportSummary(
        Long id,                          // 关联的失物招领单 ID
        String itemName,                  // 物品名称
        ItemCategory category,            // 物品类别（如 电子设备/证件/钥匙 等）
        String location,                  // 丢失/拾获地点
        ReportStatus status) {            // 招领单当前状态（如 PENDING / PICKED_UP 等）
}
