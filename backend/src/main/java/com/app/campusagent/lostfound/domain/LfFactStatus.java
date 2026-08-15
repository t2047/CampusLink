package com.app.campusagent.lostfound.domain;

/** 用户记忆事实关联物品状态（独立于 ReportStatus，供事实去重/匹配优先级使用）。 */
public enum LfFactStatus {
    OPEN,
    CLAIMED,
    CLOSED
}
