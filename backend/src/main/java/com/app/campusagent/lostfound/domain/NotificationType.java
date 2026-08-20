/**
 * 站内通知类型枚举。
 * <p>
 * 标识 lost_found_notifications 表中每条站内通知的业务类型，
 * 在 {@code LostFoundNotification} 中通过 {@code @Enumerated(EnumType.STRING)}
 * 以枚举名按字符串形式写入数据库 type 列，用于通知接收端分类展示。
 */
package com.app.campusagent.lostfound.domain;

public enum NotificationType {
    /** 认领申请已提交：通知报告发布者，提示有人申请认领。 */
    CLAIM_SUBMITTED,
    /** 认领申请已批准：通知认领人，提示可前往领取物品。 */
    CLAIM_APPROVED,
    /** 认领申请已驳回：通知认领人，提示申请未通过。 */
    CLAIM_REJECTED,
    /** 报告已被认领：通知相关用户，报告状态发生变更。 */
    REPORT_CLAIMED
}
