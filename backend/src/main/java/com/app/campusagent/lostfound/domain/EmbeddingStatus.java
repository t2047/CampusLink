package com.app.campusagent.lostfound.domain;

/** 预训练向量生成状态；基础指纹不受该状态影响。 */
public enum EmbeddingStatus {
    READY,
    PARTIAL,
    PENDING,
    BASELINE
}
