/**
 * 失物招领报告级写操作审计动作类型枚举。
 * <p>
 * 标识审计日志(lost_found_audit_logs 表)中记录的每一次报告级写操作的业务动作，
 * 在 {@code LostFoundAuditLog} 中通过 {@code @Enumerated(EnumType.STRING)}
 * 以枚举名按字符串形式写入数据库 action 列，供管理员回溯操作历史。
 */
package com.app.campusagent.lostfound.domain;

/** 失物招领报告级写操作的审计动作类型。 */
public enum LostFoundAuditAction {
    /** 用户发布了一条新的失物/招领报告。 */
    REPORT_CREATED,
    /** 用户编辑更新了报告详情。 */
    REPORT_UPDATED,
    /** 用户（发布者）关闭了报告（如已找到 / 已领取）。 */
    REPORT_CLOSED,
    /** 用户（发布者）删除了报告。 */
    REPORT_DELETED,
    /** 管理员下架了报告（从公开搜索与候选匹配中隐藏）。 */
    REPORT_DELISTED,
    /** 管理员恢复了下架的报告。 */
    REPORT_RESTORED,
    /** 管理员强制删除了报告。 */
    REPORT_DELETED_BY_ADMIN,
    /** 报告被认领（有用户提交了认领申请）。 */
    REPORT_CLAIMED,
    /** 管理员批准了某条认领申请。 */
    CLAIM_APPROVED_BY_ADMIN,
    /** 管理员驳回了某条认领申请。 */
    CLAIM_REJECTED_BY_ADMIN
}
