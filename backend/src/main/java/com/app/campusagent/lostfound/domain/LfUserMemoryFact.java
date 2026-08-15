package com.app.campusagent.lostfound.domain;

import com.app.campusagent.domain.User;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户级长期记忆事实：历次报失/报拾的物品、类别、颜色、地点、时间、状态
 * （chat-memory-requirements §5.3）。去重键 (user_id, fact_type, category, location)。
 */
@Getter
@Entity
@Table(name = "lf_user_memory_facts", indexes = {
        @Index(name = "idx_user_updated", columnList = "user_id,updated_at"),
        @Index(name = "idx_user_type", columnList = "user_id,fact_type")
})
public class LfUserMemoryFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** 来源会话（审计/溯源）。 */
    @Column(name = "session_id", length = 200)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "fact_type", nullable = false, length = 16)
    private LfFactType factType;

    @Column(name = "item_name", length = 100)
    private String itemName;

    @Column(length = 50)
    private String category;

    @Column(length = 50)
    private String colour;

    @Column(length = 200)
    private String location;

    @Column(name = "event_date")
    private LocalDate eventDate;

    @Column(name = "time_description", length = 100)
    private String timeDescription;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private LfFactStatus status;

    @Column
    private Float confidence;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected LfUserMemoryFact() {
    }

    public LfUserMemoryFact(User user,
                            String sessionId,
                            LfFactType factType,
                            String itemName,
                            String category,
                            String colour,
                            String location,
                            LocalDate eventDate,
                            String timeDescription,
                            LfFactStatus status,
                            Float confidence) {
        this.user = user;
        this.sessionId = sessionId;
        this.factType = factType;
        this.itemName = itemName;
        this.category = category;
        this.colour = colour;
        this.location = location;
        this.eventDate = eventDate;
        this.timeDescription = timeDescription;
        this.status = status;
        this.confidence = confidence;
    }

    /** 合并去重命中：仅覆盖传入的非空字段，保留原字段。 */
    public void mergeNonNull(String itemName,
                             String category,
                             String colour,
                             String location,
                             LocalDate eventDate,
                             String timeDescription,
                             LfFactStatus status,
                             Float confidence) {
        if (itemName != null) {
            this.itemName = itemName;
        }
        if (category != null) {
            this.category = category;
        }
        if (colour != null) {
            this.colour = colour;
        }
        if (location != null) {
            this.location = location;
        }
        if (eventDate != null) {
            this.eventDate = eventDate;
        }
        if (timeDescription != null) {
            this.timeDescription = timeDescription;
        }
        if (status != null) {
            this.status = status;
        }
        if (confidence != null) {
            this.confidence = confidence;
        }
    }

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (status == null) {
            status = LfFactStatus.OPEN;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
