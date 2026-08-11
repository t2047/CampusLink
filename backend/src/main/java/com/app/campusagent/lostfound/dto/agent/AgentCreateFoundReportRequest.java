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
        @NotBlank @Size(min = 3, max = 100) String itemName,
        @NotNull ItemCategory category,
        @NotBlank @Size(min = 10, max = 2000) String description,
        @Size(max = 50) String colour,
        @NotBlank @Size(max = 200) String location,
        @NotNull @PastOrPresent LocalDate eventDate,
        @Size(max = 100) String timeDescription,
        // Agent 面板已暂存图片的 objectKey 列表，创建时关联为报告图片
        @Size(max = 5) List<String> imageKeys) {
}
