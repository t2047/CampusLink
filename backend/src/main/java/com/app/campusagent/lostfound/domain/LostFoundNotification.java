/**
 * 站内通知实体。
 * <p>
 * 对应数据库表 lost_found_notifications，表示失物招领业务流程中推送给用户的
 * 站内消息（认领申请提交、认领批准/驳回、报告被认领等）。每条通知绑定接收者
 * 与可选的报告/认领关联，支持"已读"标记，供用户中心的消息列表展示。
 */
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

    /** 主键，数据库自增。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 通知接收者，懒加载多对一关联，不可为空。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    /** 通知类型（如认领提交/批准/驳回、报告被认领），以枚举名按字符串持久化。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationType type;

    /** 通知关联的报告（可为 null：如通知仅与认领相关时只挂认领）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "report_id")
    private LostFoundReport report;

    /** 通知关联的认领申请（可为 null）。 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claim_id")
    private LostFoundClaim claim;

    /** 通知标题（如"认领申请已通过"），最长 200 字符。 */
    @Column(nullable = false, length = 200)
    private String title;

    /** 通知正文，最长 1000 字符。 */
    @Column(nullable = false, length = 1000)
    private String message;

    /** 创建时间，仅写入一次，不可更新。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 已读时间：为 null 表示未读，首次标记已读时由 {@link #markRead()} 写入。 */
    @Column(name = "read_at")
    private Instant readAt;

    /** JPA 无参构造（受保护）：仅供 ORM 反序列化使用，业务代码使用有参构造。 */
    protected LostFoundNotification() {
    }

    /**
     * 创建一条站内通知（业务入口）。
     *
     * @param recipient 接收者
     * @param type      通知类型
     * @param report    关联报告（可为 null）
     * @param claim     关联认领申请（可为 null）
     * @param title     通知标题
     * @param message   通知正文
     */
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

    /**
     * 标记为已读（幂等）：仅当从未读过时才写入 read_at，
     * 重复调用不会覆盖已有的已读时间。
     */
    public void markRead() {
        if (readAt == null) {
            readAt = Instant.now();
        }
    }

    /** 持久化前回调：统一写入创建时间。 */
    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
