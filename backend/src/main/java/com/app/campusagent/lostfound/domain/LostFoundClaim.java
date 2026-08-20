/**
 * 认领申请实体。
 * <p>
 * 对应数据库表 lost_found_claims，表示某位用户（{@code claimant}）对某条失物
 * 招领报告（{@code report}）提交的认领申请：包含认领证据描述与管理员审核结果，
 * 并驱动后续站内通知（认领提交/批准/驳回）的生成。状态在
 * {@link ClaimStatus#SUBMITTED} → {@link ClaimStatus#APPROVED} / {@link ClaimStatus#REJECTED}
 * 之间流转。
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Getter
@Entity
@Table(name = "lost_found_claims", indexes = {
        @Index(name = "idx_lf_claim_report_status", columnList = "report_id,status"),
        @Index(name = "idx_lf_claim_claimant", columnList = "claimant_id")
})
public class LostFoundClaim {

    /** 主键，数据库自增。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 认领所针对的报告，懒加载多对一关联，不可为空。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private LostFoundReport report;

    /** 提交认领申请的用户，懒加载多对一关联，不可为空。 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claimant_id", nullable = false)
    private User claimant;

    /** 认领证据描述（如物品特征、拾取时间地点等），最长 1000 字符，不可为空。 */
    @Column(name = "proof_description", nullable = false, length = 1000)
    private String proofDescription;

    /** 认领审核状态，以枚举名按字符串形式持久化，默认 {@link ClaimStatus#SUBMITTED}。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ClaimStatus status = ClaimStatus.SUBMITTED;

    /** 审核结论备注：管理员批准或驳回时填写的说明，可为空。 */
    @Column(name = "decision_note", length = 500)
    private String decisionNote;

    /** 审核时间：管理员批准或拒绝时写入；历史已审核数据可为空。 */
    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    /** 创建时间，仅写入一次，不可更新。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 最近更新时间，随任何修改在 {@link #onUpdate()} 中自动刷新。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** JPA 无参构造（受保护）：仅供 ORM 反序列化使用，业务代码使用有参构造。 */
    protected LostFoundClaim() {
    }

    /**
     * 创建一条认领申请（业务入口）。
     *
     * @param report           被认领的报告
     * @param claimant         认领用户
     * @param proofDescription 认领证据描述
     */
    public LostFoundClaim(LostFoundReport report, User claimant, String proofDescription) {
        this.report = report;
        this.claimant = claimant;
        this.proofDescription = proofDescription;
    }

    /**
     * 批准认领：将状态置为 {@link ClaimStatus#APPROVED}，并记录审核备注与审核时间。
     *
     * @param note 管理员填写的审核备注（存入 decisionNote）
     */
    public void approve(String note) {
        status = ClaimStatus.APPROVED;
        decisionNote = note;
        reviewedAt = Instant.now();
    }

    /**
     * 驳回认领：将状态置为 {@link ClaimStatus#REJECTED}，并记录审核备注与审核时间。
     *
     * @param note 管理员填写的审核备注（存入 decisionNote）
     */
    public void reject(String note) {
        status = ClaimStatus.REJECTED;
        decisionNote = note;
        reviewedAt = Instant.now();
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
