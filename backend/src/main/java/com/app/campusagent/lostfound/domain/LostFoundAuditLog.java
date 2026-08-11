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

/**
 * 报告级写操作审计日志。报告被硬删除后审计行仍保留：
 * {@code reportId} 为普通 Long 列（无外键），{@code itemName}/{@code actorEmail}
 * 均为删除时快照，保证历史可追溯。
 */
@Getter
@Entity
@Table(name = "lost_found_audit_logs", indexes = {
        @Index(name = "idx_lf_audit_report", columnList = "report_id"),
        @Index(name = "idx_lf_audit_actor", columnList = "actor_id"),
        @Index(name = "idx_lf_audit_action", columnList = "action"),
        @Index(name = "idx_lf_audit_created_at", columnList = "created_at")
})
public class LostFoundAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    @Column(name = "actor_email", length = 100)
    private String actorEmail;

    @Column(name = "report_id")
    private Long reportId;

    @Column(name = "item_name", length = 100)
    private String itemName;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private LostFoundAuditAction action;

    @Column(length = 500)
    private String reason;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LostFoundAuditLog() {
    }

    public LostFoundAuditLog(
            LostFoundAuditAction action,
            Long reportId,
            String itemName,
            User actor,
            String reason,
            String detail) {
        this.action = action;
        this.reportId = reportId;
        this.itemName = itemName;
        this.actor = actor;
        this.actorEmail = actor == null ? null : actor.getEmail();
        this.reason = reason;
        this.detail = detail;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
