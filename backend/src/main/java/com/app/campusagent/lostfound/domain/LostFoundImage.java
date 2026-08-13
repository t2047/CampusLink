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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private LostFoundReport report;

    @Column(name = "object_key", nullable = false, unique = true, length = 500)
    private String objectKey;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "visual_fingerprint", length = 512)
    private String visualFingerprint;

    @Lob
    @Column(name = "visual_embedding", columnDefinition = "LONGBLOB")
    private byte[] visualEmbedding;

    @Column(name = "visual_embedding_model", length = 200)
    private String visualEmbeddingModel;

    @Column(name = "visual_embedding_revision", length = 64)
    private String visualEmbeddingRevision;

    @Column(name = "visual_embedding_updated_at")
    private java.time.Instant visualEmbeddingUpdatedAt;

    protected LostFoundImage() {
    }

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

    public LostFoundImage(String objectKey,
                          String originalName,
                          String contentType,
                          long fileSize,
                          int sortOrder) {
        this(objectKey, originalName, contentType, fileSize, sortOrder, null);
    }

    void attachTo(LostFoundReport report) {
        this.report = report;
    }

    /** 回填旧图片的视觉指纹（幂等，仅覆盖当前为空的情况）。 */
    public void assignVisualFingerprint(String visualFingerprint) {
        this.visualFingerprint = visualFingerprint;
    }

    public void assignVisualEmbedding(byte[] embedding, String model, String revision) {
        this.visualEmbedding = embedding == null ? null : embedding.clone();
        this.visualEmbeddingModel = model;
        this.visualEmbeddingRevision = revision;
        this.visualEmbeddingUpdatedAt = java.time.Instant.now();
    }

    public void clearVisualEmbedding() {
        this.visualEmbedding = null;
        this.visualEmbeddingModel = null;
        this.visualEmbeddingRevision = null;
        this.visualEmbeddingUpdatedAt = null;
    }

    public byte[] getVisualEmbedding() {
        return visualEmbedding == null ? null : visualEmbedding.clone();
    }
}
