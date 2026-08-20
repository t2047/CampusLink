/**
 * Agent 内部接口【创建失物报告】请求 DTO（dto/agent 子包）。
 *
 * <p>对应 {@code POST /api/internal/lost-found/reports/lost} 的请求体，
 * 由 L&F Agent（report_lost 工具）通过 internal 接口提交，创建 LOST 类型报告。
 * 与 {@link AgentCreateFoundReportRequest} 字段对称，区别仅在 itemName 的最小长度要求。</p>
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
 * Agent 创建丢失（LOST）报告请求：由 L&F Agent（report_lost 工具）通过
 * internal 接口提交，经 AgentDelegationAuthFilter 校验 Delegation Token 后落库。
 */
public record AgentCreateLostReportRequest(
        // min=2：中文物品名常为 2 字符（钥匙/钱包），与 Agent 端提取口径一致
        @NotBlank @Size(min = 2, max = 100) String itemName,
        // 物品分类：必填（ItemCategory 枚举）
        @NotNull ItemCategory category,
        // 详细描述：必填，10~2000 字符，保证描述有足够信息用于检索
        @NotBlank @Size(min = 10, max = 2000) String description,
        // 物品颜色：可选，最多 50 字符
        @Size(max = 50) String colour,
        // 丢失地点：必填，最多 200 字符
        @NotBlank @Size(max = 200) String location,
        // 丢失日期：必填，且不能晚于今天（不允许未来时间）
        @NotNull @PastOrPresent LocalDate eventDate,
        // 补充时间描述（如 "下午 3 点"）：可选，最多 100 字符
        @Size(max = 100) String timeDescription,
        // Agent 面板已暂存图片的 objectKey 列表，创建时关联为报告图片
        @Size(max = 5) List<String> imageKeys) {
}
