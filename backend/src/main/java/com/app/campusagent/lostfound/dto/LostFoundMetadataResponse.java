/**
 * 失物招领元数据响应 DTO（响应体）。
 * <p>
 * 前端初始化（加载发布表单下拉选项 / 列表筛选器）时一次性拉取各可选项列表的响应体，
 * 使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

import java.util.List;

public record LostFoundMetadataResponse(
        List<String> reportTypes,     // 报告类型可选项（LOST / FOUND）
        List<String> categories,      // 物品类别可选项
        List<String> reportStatuses,  // 招领单状态可选项
        List<String> claimStatuses) { // 认领申请状态可选项
}
