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
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Getter
@Entity
@Table(name = "lost_found_notifications", indexes = {
        @Index(name = "idx_lf_notification_recipient_read", columnList = "recipient_id,read_at"),
        @Index(name = "idx_lf_notification_recipient_created", columnList = "recipient_id,created_at"),
        @Index(name = "idx_lf_notification_created_at", columnList = "created_at")
})
public class LostFoundNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id")
    private LostFoundReport report;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id")
    private LostFoundClaim claim;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 1000)
    private String message;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "read_at")
    private Instant readAt;

    protected LostFoundNotification() {
    }

    public LostFoundNotification(
            User recipient,
            NotificationType type,
            LostFoundReport report,
            LostFoundClaim claim,
            String title,
            String message) {
        this.recipient = recipient;
        this.type = type;
        this.report = report;
        this.claim = claim;
        this.title = title;
        this.message = message;
    }

    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
