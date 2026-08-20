/**
 * 失物招领报告实体（聚合根）。
 * <p>
 * 对应数据库表 lost_found_reports，表示一条失物（{@link ReportType#LOST}）或
 * 招领（{@link ReportType#FOUND}）公告：包含物品信息、事件信息、发布者、
 * 业务状态流转、管理员下架标记，以及用于语义/交叉模态搜索的文本向量与图片
 * 集合。图片集合以级联 + 孤儿删除方式随报告生命周期维护。
 */
package com.app.campusagent.lostfound.domain;

import com.app.campusagent.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Entity
@Table(name = "lost_found_reports", indexes = {
        @Index(name = "idx_lf_report_type_status", columnList = "report_type,status"),
        @Index(name = "idx_lf_category", columnList = "category"),
        @Index(name = "idx_lf_event_date", columnList = "event_date"),
        @Index(name = "idx_lf_created_by", columnList = "created_by")
})
public class LostFoundReport {

    /** 主键，数据库自增。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 报告类型（失物/招领），以枚举名按字符串形式持久化到 report_type 列。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "report_type", nullable = false, length = 16)
    private ReportType reportType;

    /** 物品名称，最长 100 字符，不可为空。 */
    @Column(name = "item_name", nullable = false, length = 100)
    private String itemName;

    /** 物品类别，以枚举名按字符串形式持久化，不可为空。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ItemCategory category;

    /** 物品详细描述，最长 2000 字符，不可为空。 */
    @Column(nullable = false, length = 2000)
    private String description;

    /** 物品颜色（归一化后的颜色名，供颜色筛选/匹配），最长 50 字符，可为空。 */
    @Column(length = 50)
    private String colour;

    /** 事件地点（丢失/捡到地点），最长 200 字符，不可为空。 */
    @Column(nullable = false, length = 200)
    private String location;

    /** 事件日期（丢失/捡到日期）。 */
    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    /** 事件时间的补充描述（如"上午 10 点左右"），最长 100 字符。 */
    @Column(name = "time_description", length = 100)
    private String timeDescription;

    /** 语义文本向量（纯文本语义搜索用，原始 float32 二进制），LONGBLOB 列。 */
    @Lob
    @Column(name = "semantic_text_embedding", columnDefinition = "LONGBLOB")
    private byte[] semanticTextEmbedding;

    /** 生成语义文本向量的模型标识。 */
    @Column(name = "semantic_text_model", length = 200)
    private String semanticTextModel;

    /** 生成语义文本向量的模型版本。 */
    @Column(name = "semantic_text_revision", length = 64)
    private String semanticTextRevision;

    /** 跨模态文本向量（以文搜图 / 图文交叉匹配用，原始 float32 二进制），LONGBLOB 列。 */
    @Lob
    @Column(name = "cross_modal_text_embedding", columnDefinition = "LONGBLOB")
    private byte[] crossModalTextEmbedding;

    /** 生成跨模态文本向量的模型标识。 */
    @Column(name = "cross_modal_text_model", length = 200)
    private String crossModalTextModel;

    /** 生成跨模态文本向量的模型版本。 */
    @Column(name = "cross_modal_text_revision", length = 64)
    private String crossModalTextRevision;

    /** 向量嵌入生成状态，默认 {@link EmbeddingStatus#PENDING}，由异步任务推进。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "embedding_status", nullable = false, length = 16)
    private EmbeddingStatus embeddingStatus = EmbeddingStatus.PENDING;

    /** 向量最近更新时间。 */
    @Column(name = "embedding_updated_at")
    private Instant embeddingUpdatedAt;

    /** 报告业务状态，默认 {@link ReportStatus#OPEN}。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReportStatus status = ReportStatus.OPEN;

    /** 管理员下架标记：隐藏的记录不进入公开搜索、候选匹配和非 owner/非管理员详情。 */
    @Column(name = "admin_hidden", nullable = false)
    private boolean adminHidden;

    /** 报告发布者，懒加载多对一关联，不可为空。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /**
     * 报告图片集合：与 {@code LostFoundImage.report} 反向映射（mappedBy = "report"），
     * 级联增删改且孤儿图片自动删除；加载时按 50 张一批（@BatchSize）减少 N+1 查询。
     */
    @org.hibernate.annotations.BatchSize(size = 50)
    @OneToMany(mappedBy = "report", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<LostFoundImage> images = new ArrayList<>();

    /** 创建时间，仅写入一次，不可更新。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 最近更新时间，随任何修改在 {@link #onUpdate()} 中自动刷新。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 无参构造（受保护）：仅供 ORM 反序列化使用，业务代码使用有参构造。 */
    protected LostFoundReport() {
    }

    /**
     * 创建一条报告（业务入口）：初始化除向量、状态、图片外的核心字段。
     *
     * @param reportType       报告类型（失物/招领）
     * @param itemName         物品名称
     * @param category         物品类别
     * @param description      物品描述
     * @param colour           物品颜色（可为 null）
     * @param location         事件地点
     * @param eventDate        事件日期
     * @param timeDescription  事件时间补充描述（可为 null）
     * @param createdBy        发布者
     */
    public LostFoundReport(ReportType reportType,
                           String itemName,
                           ItemCategory category,
                           String description,
                           String colour,
                           String location,
                           LocalDate eventDate,
                           String timeDescription,
                           User createdBy) {
        this.reportType = reportType;
        this.itemName = itemName;
        this.category = category;
        this.description = description;
        this.colour = colour;
        this.location = location;
        this.eventDate = eventDate;
        this.timeDescription = timeDescription;
        this.createdBy = createdBy;
    }

    /**
     * 添加一张图片：加入图片集合的同时调用 {@code image.attachTo(this)}，
     * 维护双方一致的双向关联（本方法由报告侧统一入口，避免挂载错乱）。
     */
    public void addImage(LostFoundImage image) {
        images.add(image);
        image.attachTo(this);
    }

    /** 状态流转：将报告标记为已认领（{@link ReportStatus#CLAIMED}）。 */
    public void markClaimed() {
        status = ReportStatus.CLAIMED;
    }

    /** 状态流转：关闭报告（{@link ReportStatus#CLOSED}），不再接受认领。 */
    public void markClosed() {
        status = ReportStatus.CLOSED;
    }

    /** 管理员下架：从公开搜索与候选匹配中隐藏。 */
    public void hide() {
        adminHidden = true;
    }

    /** 管理员恢复：重新对公开可见。 */
    public void show() {
        adminHidden = false;
    }

    /**
     * 更新报告详情（编辑场景）：覆盖各业务字段，
     * 同时调用 {@link #markEmbeddingsPending()} 将向量置为待重新生成，
     * 保证搜索索引与最新内容一致。
     */
    public void updateDetails(String itemName,
                              ItemCategory category,
                              String description,
                              String colour,
                              String location,
                              LocalDate eventDate,
                              String timeDescription) {
        this.itemName = itemName;
        this.category = category;
        this.description = description;
        this.colour = colour;
        this.location = location;
        this.eventDate = eventDate;
        this.timeDescription = timeDescription;
        markEmbeddingsPending();
    }

    /**
     * 写入语义与跨模态文本向量（异步向量任务回填时调用）。
     * semantic 与 crossModal 均可为 null；有 crossModal（图文匹配所需）
     * 时状态置 {@link EmbeddingStatus#READY}，否则置 {@link EmbeddingStatus#PARTIAL}。
     *
     * @param semantic           语义文本向量（可为 null）
     * @param semanticModel      语义向量模型标识
     * @param semanticRevision   语义向量模型版本
     * @param crossModal         跨模态文本向量（可为 null）
     * @param crossModalModel    跨模态向量模型标识
     * @param crossModalRevision 跨模态向量模型版本
     */
    public void assignTextEmbeddings(
            byte[] semantic,
            String semanticModel,
            String semanticRevision,
            byte[] crossModal,
            String crossModalModel,
            String crossModalRevision) {
        this.semanticTextEmbedding = copy(semantic);
        this.semanticTextModel = semanticModel;
        this.semanticTextRevision = semanticRevision;
        this.crossModalTextEmbedding = copy(crossModal);
        this.crossModalTextModel = crossModalModel;
        this.crossModalTextRevision = crossModalRevision;
        this.embeddingStatus = crossModal == null ? EmbeddingStatus.PARTIAL : EmbeddingStatus.READY;
        this.embeddingUpdatedAt = Instant.now();
    }

    /** 清除全部文本向量及其模型信息，状态置为待生成，供编辑后重新向量化。 */
    public void markEmbeddingsPending() {
        this.semanticTextEmbedding = null;
        this.semanticTextModel = null;
        this.semanticTextRevision = null;
        this.crossModalTextEmbedding = null;
        this.crossModalTextModel = null;
        this.crossModalTextRevision = null;
        this.embeddingStatus = EmbeddingStatus.PENDING;
        this.embeddingUpdatedAt = null;
    }

    /**
     * 依据现有文本/图片向量整体情况重新计算向量状态：
     * 文本（语义+跨模态）与全部有效图片向量均就绪 → {@link EmbeddingStatus#READY}；
     * 部分就绪 → {@link EmbeddingStatus#PARTIAL}；否则 → {@link EmbeddingStatus#PENDING}。
     * 该方法用于向量任务完成或图片增删后统一对齐状态。
     */
    public void refreshEmbeddingStatus() {
        boolean hasText = semanticTextEmbedding != null;
        boolean allImagesReady = images.stream()
                .filter(image -> image.getFileSize() > 0)
                .allMatch(image -> image.getVisualEmbedding() != null);
        if (hasText && allImagesReady && crossModalTextEmbedding != null) {
            embeddingStatus = EmbeddingStatus.READY;
        } else if (hasText || images.stream().anyMatch(image -> image.getVisualEmbedding() != null)) {
            embeddingStatus = EmbeddingStatus.PARTIAL;
        } else {
            embeddingStatus = EmbeddingStatus.PENDING;
        }
        embeddingUpdatedAt = Instant.now();
    }

    /**
     * 防御性拷贝工具：对入参数组克隆一份返回，null 原样返回 null。
     * 用于写入与读取向量字段时，避免外部直接持有实体内部数组。
     */
    private static byte[] copy(byte[] value) {
        return value == null ? null : value.clone();
    }

    /** 返回语义文本向量副本（防御性拷贝，覆盖 Lombok getter）。 */
    public byte[] getSemanticTextEmbedding() {
        return copy(semanticTextEmbedding);
    }

    /** 返回跨模态文本向量副本（防御性拷贝，覆盖 Lombok getter）。 */
    public byte[] getCrossModalTextEmbedding() {
        return copy(crossModalTextEmbedding);
    }

    /** 报告删除前收集其全部图片的 MinIO 对象键，供 service 层清理存储。 */
    public List<String> imageObjectKeys() {
        return images.stream().map(LostFoundImage::getObjectKey).toList();
    }

    /** 整体替换图片集合（编辑时使用）：旧图随 orphanRemoval 删除，新图按序挂载。 */
    public void replaceImages(List<LostFoundImage> newImages) {
        images.clear();
        newImages.forEach(this::addImage);
    }

    /**
     * 持久化前回调：统一写入创建时间与初始更新时间。
     */
    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    /**
     * 更新前回调：自动刷新更新时间，保证 updated_at 反映最近一次修改。
     */
    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
