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

    @Id
    @Column(name = "object_key", length = 500)
    private String objectKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "visual_fingerprint", length = 512)
    private String visualFingerprint;

    @Lob
    @Column(name = "visual_embedding", columnDefinition = "LONGBLOB")
    private byte[] visualEmbedding;

    @Column(name = "visual_embedding_model", length = 200)
    private String visualEmbeddingModel;

    @Column(name = "visual_embedding_revision", length = 64)
    private String visualEmbeddingRevision;

    @Enumerated(EnumType.STRING)
    @Column(name = "embedding_status", length = 16)
    private EmbeddingStatus embeddingStatus = EmbeddingStatus.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected LostFoundStagedImage() {
    }

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

    public byte[] getVisualEmbedding() {
        return visualEmbedding == null ? null : visualEmbedding.clone();
    }
}
