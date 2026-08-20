/**
 * 嵌入向量存储载体（record）。
 * <p>向量以原始 float32 小端字节数组形式存储于数据库；接口对外传输时
 * 再编码为 Base64（见 {@code LostFoundEmbeddingClient.decode}）。
 * <p>对 vector 做防御性拷贝，保持记录不可变语义，防止外部修改污染存储数据。
 */
package com.app.campusagent.lostfound.embedding;

/** 数据库存储使用原始 float32 字节，接口传输时再编码为 Base64。 */
public record StoredEmbedding(byte[] vector, String model, String revision, int dimension) {
    /** 紧凑构造器：把入参 vector 深拷贝一份保存，防止外部数组被修改影响本对象。 */
    public StoredEmbedding {
        vector = vector == null ? null : vector.clone();
    }

    @Override
    public byte[] vector() {
        // 访问器同样返回克隆：保证调用方改动返回数组不会污染内部数据（不可变语义）
        return vector == null ? null : vector.clone();
    }
}
