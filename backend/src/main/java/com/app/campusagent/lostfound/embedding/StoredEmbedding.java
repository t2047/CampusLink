package com.app.campusagent.lostfound.embedding;

/** 数据库存储使用原始 float32 字节，接口传输时再编码为 Base64。 */
public record StoredEmbedding(byte[] vector, String model, String revision, int dimension) {
    public StoredEmbedding {
        vector = vector == null ? null : vector.clone();
    }

    @Override
    public byte[] vector() {
        return vector == null ? null : vector.clone();
    }
}
