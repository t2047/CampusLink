/**
 * 报告业务状态枚举。
 * <p>
 * 表示 lost_found_reports 表中报告的状态流转：发布后为 {@link #OPEN}，
 * 有用户认领后为 {@link #CLAIMED}，最后由发布者或管理员关闭为 {@link #CLOSED}。
 * 在 {@code LostFoundReport} 中通过 {@code @Enumerated(EnumType.STRING)}
 * 以枚举名按字符串形式写入数据库 status 列。
 */
package com.app.campusagent.lostfound.domain;

public enum ReportStatus {
    /** 开放状态：报告对公众可见，可被搜索与认领（报告发布后的初始状态）。 */
    OPEN,
    /** 已认领：已有用户对报告提交了认领申请（或管理员批准了认领）。 */
    CLAIMED,
    /** 已关闭：问题已解决，报告不再接受认领。 */
    CLOSED
}
