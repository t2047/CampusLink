package com.app.campusagent.lostfound.domain;

import com.app.campusagent.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * L&F 会话：按 (user_id, session_id) 隔离；携带滚动摘要与未完成确认草稿
 * （chat-memory-requirements §5.1）。
 */
@Getter
@Entity
@Table(name = "lf_chat_sessions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_session", columnNames = {"user_id", "session_id"})
}, indexes = {
        @Index(name = "idx_user_last_active", columnList = "user_id,last_active_at")
})
public class LfChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "session_id", nullable = false, length = 200)
    private String sessionId;

    @Column(length = 120)
    private String title;

    /** 滚动摘要（更早消息折叠后的文本）。 */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** 未完成确认草稿（JSON，结构见 chat-memory-requirements §7.5）。 */
    @Column(name = "pending_confirmation", columnDefinition = "TEXT")
    private String pendingConfirmation;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "last_active_at", nullable = false)
    private LocalDateTime lastActiveAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @org.hibernate.annotations.BatchSize(size = 50)
    @OneToMany(mappedBy = "chatSession", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<LfChatMessage> messages = new ArrayList<>();

    protected LfChatSession() {
    }

    public LfChatSession(User user, String sessionId) {
        this.user = user;
        this.sessionId = sessionId;
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.lastActiveAt = now;
    }

    public void addMessage(LfChatMessage message) {
        messages.add(message);
        message.attachTo(this);
    }

    public void touch() {
        lastActiveAt = LocalDateTime.now();
    }

    public void updateSummary(String newSummary) {
        this.summary = newSummary;
    }

    public void updatePendingConfirmation(String pendingJson) {
        this.pendingConfirmation = pendingJson;
    }

    public void updateTitle(String newTitle) {
        this.title = newTitle;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (lastActiveAt == null) {
            lastActiveAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        if (lastActiveAt == null) {
            lastActiveAt = LocalDateTime.now();
        }
    }
}
