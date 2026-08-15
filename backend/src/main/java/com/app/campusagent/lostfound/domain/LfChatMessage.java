package com.app.campusagent.lostfound.domain;

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
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 会话消息：原文 + 结构化抽取结果 + 本轮涉及的暂存图 object_key
 * （chat-memory-requirements §5.2）。
 */
@Getter
@Entity
@Table(name = "lf_chat_messages", indexes = {
        @Index(name = "idx_session_created", columnList = "chat_session_id,created_at")
})
public class LfChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chat_session_id", nullable = false)
    private LfChatSession chatSession;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LfMessageRole role;

    @Column(name = "message_text", columnDefinition = "TEXT", nullable = false)
    private String messageText;

    @Column(length = 50)
    private String intent;

    /** 结构化抽取结果（JSON，如 {item_name, category, colour, location, ...}）。 */
    @Column(name = "extracted_fields", columnDefinition = "TEXT")
    private String extractedFields;

    /** 本轮涉及的暂存图 object_key 列表（JSON 数组）。 */
    @Column(name = "image_object_keys", columnDefinition = "TEXT")
    private String imageObjectKeys;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected LfChatMessage() {
    }

    public LfChatMessage(LfMessageRole role,
                         String messageText,
                         String intent,
                         String extractedFields,
                         String imageObjectKeys,
                         String traceId) {
        this.role = role;
        this.messageText = messageText;
        this.intent = intent;
        this.extractedFields = extractedFields;
        this.imageObjectKeys = imageObjectKeys;
        this.traceId = traceId;
    }

    void attachTo(LfChatSession session) {
        this.chatSession = session;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
