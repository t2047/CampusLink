/**
 * 更新失物招领单请求 DTO（请求体）。
 * <p>
 * 发布者（或管理员）在个人中心"我发布的"里编辑某条失物 / 招领单时提交的请求体，
 * 使用 Java record 表示；字段与创建请求基本一致，但不含 reportType（类型创建后不可修改）。
 */
package com.app.campusagent.lostfound.dto;

import com.app.campusagent.lostfound.domain.ItemCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateLostFoundReportRequest(
        // min=2：中文物品名常为 2 字符（钥匙/钱包），与 Agent 端提取口径一致
        @NotBlank @Size(min = 2, max = 100) String itemName,
        // 物品类别，必填（@NotNull），用于分类筛选
        @NotNull ItemCategory category,
        // 物品详细描述：必填（@NotBlank），min=10 保证有实质内容，max=2000 限制提交体大小
        @NotBlank @Size(min = 10, max = 2000) String description,
        // 物品主颜色：可空，限 50 字符，用于颜色维度筛选
        @Size(max = 50) String colour,
        // 丢失/拾获地点：必填（@NotBlank），限 200 字符
        @NotBlank @Size(max = 200) String location,
        // 发生日期：必填（@NotNull），且须是过去或当天（@PastOrPresent）
        @NotNull @PastOrPresent LocalDate eventDate,
        // 时间段描述：可空，限 100 字符
        @Size(max = 100) String timeDescription) {
}
