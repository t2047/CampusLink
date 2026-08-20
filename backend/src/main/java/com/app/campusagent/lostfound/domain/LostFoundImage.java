/**
 * 报告图片实体。
 * <p>
 * 对应数据库表 lost_found_images，表示某条报告（{@code report}）关联的一张实物
 * 图片。文件本体存放在 MinIO 对象存储中，本表仅存对象键与元数据，以及用于
 * 以图搜物（视觉相似匹配）的视觉指纹与预训练向量。图片与报告是一对多关系，
 * 由 {@code LostFoundReport.images} 以级联 + 孤儿删除方式维护。
 */
package com.app.campusagent.lostfound.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "lost_found_images")
public class LostFoundImage {

    /** 主键，数据库自增。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属报告，懒加载多对一关联，不可为空。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private LostFoundReport report;

    /** MinIO 中的对象键，全局唯一，作为文件的存储标识，最长 500 字符。 */
    @Column(name = "object_key", nullable = false, unique = true, length = 500)
    private String objectKey;

    /** 用户上传时的原始文件名，最长 255 字符。 */
    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    /** 文件的 MIME 类型（如 image/jpeg），最长 100 字符。 */
    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    /** 文件大小（字节）。 */
    @Column(name = "file_size", nullable = false)
    private long fileSize;

    /** 展示顺序：报告内按该值升序排列图片。 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    /** 视觉指纹（去重与相似检测用），由视觉服务计算，最长 512 字符。 */
    @Column(name = "visual_fingerprint", length = 512)
    private String visualFingerprint;

    /** 预训练视觉向量（原始 float32 二进制），供以图搜物匹配，使用 LONGBLOB 列。 */
    @Lob
    @Column(name = "visual_embedding", columnDefinition = "LONGBLOB")
    private byte[] visualEmbedding;

    /** 生成视觉向量的模型标识。 */
    @Column(name = "visual_embedding_model", length = 200)
    private String visualEmbeddingModel;

    /** 生成视觉向量的模型版本。 */
    @Column(name = "visual_embedding_revision", length = 64)
    private String visualEmbeddingRevision;

    /** 视觉向量最近更新时间，可在首次写入或重新生成时刷新。 */
    @Column(name = "visual_embedding_updated_at")
    private java.time.Instant visualEmbeddingUpdatedAt;

    /** JPA 无参构造（受保护）：仅供 ORM 反序列化使用，业务代码使用有参构造。 */
    protected LostFoundImage() {
    }

    /**
     * 完整构造（含视觉指纹）：常用于上传时已能同步生成指纹的场景。
     *
     * @param objectKey         MinIO 对象键
     * @param originalName      原始文件名
     * @param contentType       MIME 类型
     * @param fileSize          文件字节数
     * @param sortOrder         展示顺序
     * @param visualFingerprint 视觉指纹（可为 null，留待回填）
     */
    public LostFoundImage(String objectKey,
                          String originalName,
                          String contentType,
                          long fileSize,
                          int sortOrder,
                          String visualFingerprint) {
        this.objectKey = objectKey;
        this.originalName = originalName;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.sortOrder = sortOrder;
        this.visualFingerprint = visualFingerprint;
    }

    /**
     * 简化构造（无视觉指纹）：指纹由后续异步任务回填。
     *
     * @param objectKey     MinIO 对象键
     * @param originalName  原始文件名
     * @param contentType   MIME 类型
     * @param fileSize      文件字节数
     * @param sortOrder     展示顺序
     */
    public LostFoundImage(String objectKey,
                          String originalName,
                          String contentType,
                          long fileSize,
                          int sortOrder) {
        this(objectKey, originalName, contentType, fileSize, sortOrder, null);
    }

    /**
     * 双向关联维护（包私有）：由报告侧 {@code LostFoundReport.addImage} 调用，
     * 把本图片挂到指定报告上，保持 report 引用与报告侧集合一致。
     */
    void attachTo(LostFoundReport report) {
        this.report = report;
    }

    /** 回填旧图片的视觉指纹（幂等，仅覆盖当前为空的情况）。 */
    public void assignVisualFingerprint(String visualFingerprint) {
        this.visualFingerprint = visualFingerprint;
    }

    /**
     * 写入预训练视觉向量及其模型信息，并刷新更新时间。
     * 入参向量会被克隆存储（防御性拷贝），避免外部修改数组污染实体状态。
     *
     * @param embedding 预训练视觉向量（可为 null，表示清除）
     * @param model     模型标识
     * @param revision  模型版本
     */
    public void assignVisualEmbedding(byte[] embedding, String model, String revision) {
        this.visualEmbedding = embedding == null ? null : embedding.clone();
        this.visualEmbeddingModel = model;
        this.visualEmbeddingRevision = revision;
        this.visualEmbeddingUpdatedAt = java.time.Instant.now();
    }

    /** 清空预训练视觉向量及其模型信息（如向量失效或模型更换时调用）。 */
    public void clearVisualEmbedding() {
        this.visualEmbedding = null;
        this.visualEmbeddingModel = null;
        this.visualEmbeddingRevision = null;
        this.visualEmbeddingUpdatedAt = null;
    }

    /**
     * 返回视觉向量副本（防御性拷贝）：覆盖 Lombok 生成的 getter，
     * 防止外部拿到内部数组后直接修改实体持久化状态。
     */
    public byte[] getVisualEmbedding() {
        return visualEmbedding == null ? null : visualEmbedding.clone();
    }
}
