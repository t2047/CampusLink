/**
 * 认领申请状态枚举。
 * <p>
 * 对应认领申请(lost_found_claims 表)的审核流转状态：认领人提交申请后为
 * {@link #SUBMITTED}，管理员审核后批准为 {@link #APPROVED} 或驳回为
 * {@link #REJECTED}。在 {@code LostFoundClaim} 中通过
 * {@code @Enumerated(EnumType.STRING)} 以枚举名按字符串形式写入数据库 status 列。
 */
package com.app.campusagent.lostfound.domain;

public enum ClaimStatus {
    /** 已提交，等待管理员审核（认领申请提交时的初始状态）。 */
    SUBMITTED,
    /** 已批准：认领人具备认领资格，可前往领取，报告随之进入已认领状态。 */
    APPROVED,
    /** 已驳回：认领人不具备认领资格或提供的证据不足。 */
    REJECTED
}
