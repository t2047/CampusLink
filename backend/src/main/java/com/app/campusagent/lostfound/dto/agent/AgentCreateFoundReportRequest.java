/**
 * Agent 内部接口【创建拾获报告】请求 DTO（dto/agent 子包）。
 *
 * <p>对应 {@code POST /api/internal/lost-found/reports/found} 的请求体，
 * 由 L&F Agent（report_found 工具）通过 internal 接口提交，
 * 经 AgentDelegationAuthFilter 校验 Delegation Token 后，由
 * LostFoundReportService.createFromStaged 关联暂存图片落库为 FOUND 类型报告。</p>
 */
package com.app.campusagent.lostfound.dto.agent;

import com.app.campusagent.lostfound.domain.ItemCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/**
 * Agent 创建捡到（FOUND）报告请求，与 {@link AgentCreateLostReportRequest} 字段对称。
 *
 * <p>由 L&F Agent（report_found 工具）通过 internal 接口提交，经
 * AgentDelegationAuthFilter 校验 Delegation Token。</p>
 */
public record AgentCreateFoundReportRequest(
        // 物品名称：必填，3~100 字符（拾获场景通常有较完整的物品名）
        @NotBlank @Size(min = 3, max = 100) String itemName,
        // 物品分类：必填（ItemCategory 枚举）
        @NotNull ItemCategory category,
        // 详细描述：必填，10~2000 字符，保证描述有足够信息用于检索
        @NotBlank @Size(min = 10, max = 2000) String description,
        // 物品颜色：可选，最多 50 字符
        @Size(max = 50) String colour,
        // 拾获地点：必填，最多 200 字符
        @NotBlank @Size(max = 200) String location,
        // 拾获日期：必填，且不能晚于今天（不允许未来时间）
        @NotNull @PastOrPresent LocalDate eventDate,
        // 补充时间描述（如 "下午 3 点"）：可选，最多 100 字符
        @Size(max = 100) String timeDescription,
        // Agent 面板已暂存图片的 objectKey 列表，创建时关联为报告图片
        @Size(max = 5) List<String> imageKeys) {
}
