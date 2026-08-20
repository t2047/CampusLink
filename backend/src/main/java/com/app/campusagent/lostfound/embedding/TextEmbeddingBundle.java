/**
 * 文本嵌入结果打包（record）。
 * <p>把一次文本嵌入请求得到的两个向量（semantic 语义向量、cross_modal 跨模态向量）
 * 连同"跨模态是否可用"标志一起返回给调用方，供语义检索与以文搜图等功能使用。
 */
package com.app.campusagent.lostfound.embedding;

/**
 * @param semantic            语义空间向量（用于文本-文本相似度计算）
 * @param crossModal          跨模态空间向量（用于与图片向量对比，实现以文搜图）
 * @param crossModalAvailable 服务端是否返回了跨模态向量（false 时 crossModal 可能为 null）
 */
public record TextEmbeddingBundle(
        StoredEmbedding semantic,
        StoredEmbedding crossModal,
        boolean crossModalAvailable) {
}
