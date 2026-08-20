/**
 * Agent 内部接口【候选报告】响应 DTO（dto/agent 子包）。
 *
 * <p>对应 {@code GET /api/internal/lost-found/candidates} 与
 * {@code GET /api/internal/lost-found/lost-candidates} 分页列表的每一项，
 * 由 L&F Agent（report_found / report_lost 工具）检索候选报告时使用。
 * 除报告文本字段外，还携带以图搜物/向量打分所需的指纹与嵌入数据。</p>
 */
package com.app.campusagent.lostfound.dto.agent;

import com.app.campusagent.lostfound.domain.ItemCategory;
import com.app.campusagent.lostfound.domain.ReportType;
import com.app.campusagent.lostfound.domain.ReportStatus;

import java.time.LocalDate;
import java.util.List;

public record AgentCandidateResponse(
        // 报告主键
        Long id,
        // 报告类型：LOST / FOUND（candidates 固定返回 FOUND，lost-candidates 固定返回 LOST）
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
        // 事件的补充时间描述
        String timeDescription,
        // 报告状态（候选检索只返回 OPEN 的报告）
        ReportStatus status,
        // 图片同源代理 URL 列表，与 visualFingerprints / visualEmbeddings 一一同序
        List<String> imageUrls,
        // 各图片的视觉指纹，与 imageUrls 同序；无指纹的图片位置为 null，Agent 端跳过
        List<String> visualFingerprints,
        // 报告文本的语义嵌入（Base64 编码的原始 float32），用于语义检索；历史数据可能为 null
        String semanticTextEmbedding,
        // 跨模态文本嵌入（Base64 编码），用于图文匹配/以图搜物；可为 null
        String crossModalTextEmbedding,
        // 各图片的视觉嵌入（Base64 编码列表），与 imageUrls 同序；无向量位置为 null
        List<String> visualEmbeddings,
        // 嵌入生成状态（EmbeddingStatus 枚举）：
        // READY 就绪 / PARTIAL 部分就绪 / PENDING 待生成 / BASELINE 降级基础匹配
        String embeddingStatus) {
}
