/**
 * 报告级写操作审计日志实体。
 * <p>
 * 对应数据库表 lost_found_audit_logs，记录失物招领报告生命周期中的每一次
 * 重要写操作（发布、编辑、关闭、删除、下架/恢复、认领审核等），供管理员
 * 追溯操作历史。报告被硬删除后审计行仍须保留，因此关联字段均以"删除时
 * 快照"形式存储（详见类注释），不依赖被审计报告仍存在。
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

    /** 主键，数据库自增。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 操作执行者（用户），懒加载多对一关联，对应 actor_id 列。
     * 可为空：例如系统内部操作或执行者已不可用时只保留邮箱快照。
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    /** 操作者邮箱快照：审计行独立留存，即使账号被删仍可追溯操作人。 */
    @Column(name = "actor_email", length = 100)
    private String actorEmail;

    /**
     * 被操作报告的主键，为普通 Long 列（无外键约束）：
     * 报告被硬删除后该值仍指向已不存在的记录，用于追溯来源。
     */
    @Column(name = "report_id")
    private Long reportId;

    /** 被操作报告的物品名称快照（删除时复制，便于审计阅读）。 */
    @Column(name = "item_name", length = 100)
    private String itemName;

    /** 审计动作类型，以枚举名按字符串形式持久化到 action 列。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private LostFoundAuditAction action;

    /** 操作原因（如管理员下架/驳回时的备注），最长 500 字符，可为空。 */
    @Column(length = 500)
    private String reason;

    /** 操作补充详情（说明文本或序列化数据），最长 500 字符，可为空。 */
    @Column(length = 500)
    private String detail;

    /** 审计时间，仅写入一次，不可更新。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** JPA 无参构造（受保护）：仅供 ORM 反序列化使用，业务代码使用有参构造。 */
    protected LostFoundAuditLog() {
    }

    /**
     * 构造一条审计记录（业务入口）。
     *
     * @param action   审计动作类型
     * @param reportId 被操作报告的主键
     * @param itemName 报告物品名称快照
     * @param actor    操作执行者（可为 null，此时邮箱快照为 null）
     * @param reason   操作原因
     * @param detail   操作详情
     */
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

    /**
     * 持久化前回调：统一写入审计时间，保证 created_at 在入库时由实体自动填充。
     */
    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
