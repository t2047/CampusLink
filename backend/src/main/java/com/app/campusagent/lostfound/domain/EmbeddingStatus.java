/**
 * 预训练向量嵌入生成状态枚举。
 * <p>
 * 描述失物招领报告与暂存图片的语义/视觉向量(embedding)的生成进度，
 * 决定智能搜索(以文搜物、以图搜物、交叉模态匹配)是否可用或需降级。
 * 基础视觉指纹(visual fingerprint)不依赖预训练向量，因此不受该状态影响。
 * 在实体中通过 {@code @Enumerated(EnumType.STRING)} 以枚举名按字符串形式写入数据库。
 */
package com.app.campusagent.lostfound.domain;

/** 预训练向量生成状态；基础指纹不受该状态影响。 */
public enum EmbeddingStatus {
    /** 全部所需向量已生成，智能匹配功能可用。 */
    READY,
    /** 部分向量已生成（如仅有文本向量而无图片向量），匹配能力部分降级。 */
    PARTIAL,
    /** 向量尚未生成，等待异步任务回填，匹配降级为基础匹配。 */
    PENDING,
    /** 历史数据无向量，仅以基础特征（如颜色、指纹）回退匹配的基线状态。 */
    BASELINE
}
