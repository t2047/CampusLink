/**
 * 暂存图片实体。
 * <p>
 * 对应数据库表 lost_found_staged_images，表示用户上传但尚未随报告提交的临时
 * 图片：上传时先在 MinIO 中落盘并在本表登记，待发布报告时正式挂载到报告，
 * 或超过有效期后由清理任务删除记录与对象。实体主键即 MinIO 对象键，
 * 预训练视觉向量在上传阶段即可计算并缓存，避免发布报告时重复向量化。
 */
package com.app.campusagent.lostfound.domain;

import com.app.campusagent.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

/** 暂存图片的可信元数据；浏览器只持有 objectKey，不持有预训练向量。 */
@Getter
@Entity
@Table(name = "lost_found_staged_images")
public class LostFoundStagedImage {

    /** 主键即 MinIO 对象键（非自增），全局唯一，最长 500 字符。 */
    @Id
    @Column(name = "object_key", length = 500)
    private String objectKey;

    /** 上传用户，懒加载多对一关联，不可为空。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /** 视觉指纹（去重与相似检测用），最长 512 字符。 */
    @Column(name = "visual_fingerprint", length = 512)
    private String visualFingerprint;

    /** 预训练视觉向量（原始 float32 二进制），LONGBLOB 列。 */
    @Lob
    @Column(name = "visual_embedding", columnDefinition = "LONGBLOB")
    private byte[] visualEmbedding;

    /** 生成视觉向量的模型标识。 */
    @Column(name = "visual_embedding_model", length = 200)
    private String visualEmbeddingModel;

    /** 生成视觉向量的模型版本。 */
    @Column(name = "visual_embedding_revision", length = 64)
    private String visualEmbeddingRevision;

    /** 向量嵌入状态：有向量为 {@link EmbeddingStatus#READY}，否则为 {@link EmbeddingStatus#PENDING}。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "embedding_status", length = 16)
    private EmbeddingStatus embeddingStatus = EmbeddingStatus.PENDING;

    /** 暂存记录创建时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 过期时间：到期后由清理任务删除暂存记录与 MinIO 对象。 */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** JPA 无参构造（受保护）：仅供 ORM 反序列化使用，业务代码使用有参构造。 */
    protected LostFoundStagedImage() {
    }

    /**
     * 创建一条暂存图片记录（业务入口）。
     * 入参向量会被克隆存储（防御性拷贝）；有向量时状态置
     * {@link EmbeddingStatus#READY}，否则置 {@link EmbeddingStatus#PENDING}；
     * 创建时间即时写入。
     *
     * @param objectKey              MinIO 对象键
     * @param createdBy              上传用户
     * @param visualFingerprint      视觉指纹
     * @param visualEmbedding        预训练视觉向量（可为 null）
     * @param visualEmbeddingModel   模型标识
     * @param visualEmbeddingRevision 模型版本
     * @param expiresAt              过期时间
     */
    public LostFoundStagedImage(
            String objectKey,
            User createdBy,
            String visualFingerprint,
            byte[] visualEmbedding,
            String visualEmbeddingModel,
            String visualEmbeddingRevision,
            Instant expiresAt) {
        this.objectKey = objectKey;
        this.createdBy = createdBy;
        this.visualFingerprint = visualFingerprint;
        this.visualEmbedding = visualEmbedding == null ? null : visualEmbedding.clone();
        this.visualEmbeddingModel = visualEmbeddingModel;
        this.visualEmbeddingRevision = visualEmbeddingRevision;
        this.embeddingStatus = visualEmbedding == null
                ? EmbeddingStatus.PENDING
                : EmbeddingStatus.READY;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
    }

    /**
     * 返回预训练视觉向量副本（防御性拷贝）：覆盖 Lombok 生成的 getter，
     * 防止外部拿到内部数组后直接修改实体持久化状态。
     */
    public byte[] getVisualEmbedding() {
        return visualEmbedding == null ? null : visualEmbedding.clone();
    }
}
