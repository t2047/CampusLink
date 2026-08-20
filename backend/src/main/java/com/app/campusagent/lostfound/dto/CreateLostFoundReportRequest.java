/**
 * 创建失物招领单请求 DTO（请求体）。
 * <p>
 * 用户发布一条失物 / 招领信息时提交的请求体，包含报告类型、物品信息、颜色、
 * 地点、发生日期等字段，使用 Java record 表示。
 */
package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateLostFoundReportRequest(
        // 报告类型（LOST 丢失 / FOUND 招领），必填（@NotNull），决定该单的展示与流转逻辑
        @NotNull ReportType reportType,
        // min=2：中文物品名常为 2 字符（钥匙/钱包），与 Agent 端提取口径一致
        @NotBlank @Size(min = 2, max = 100) String itemName,
        // 物品类别（如 电子设备/证件/钥匙 等），必填（@NotNull），用于列表分类筛选
        @NotNull ItemCategory category,
        // 物品详细描述：必填（@NotBlank），min=10 保证描述有实质内容，max=2000 限制提交体大小
        @NotBlank @Size(min = 10, max = 2000) String description,
        // 物品主颜色：可空（例如无明确颜色的物品），限 50 字符，
        // 用于"以图搜物"之外的颜色维度筛选（颜色表见 ColourNormalizer / COLOUR_GROUPS）
        @Size(max = 50) String colour,
        // 丢失/拾获地点：必填（@NotBlank），限 200 字符
        @NotBlank @Size(max = 200) String location,
        // 发生日期：必填（@NotNull），且须是过去或当天（@PastOrPresent），不允许填写未来日期
        @NotNull @PastOrPresent LocalDate eventDate,
        // 时间段描述（如"下午 3 点到 5 点之间"）：可空，限 100 字符
        @Size(max = 100) String timeDescription) {
}
