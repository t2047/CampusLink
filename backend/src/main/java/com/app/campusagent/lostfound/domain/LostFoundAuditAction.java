package com.app.campusagent.lostfound.domain;

/** 失物招领报告级写操作的审计动作类型。 */
public enum LostFoundAuditAction {
    REPORT_CREATED,
    REPORT_UPDATED,
    REPORT_CLOSED,
    REPORT_DELETED,
    REPORT_DELISTED,
    REPORT_RESTORED,
    REPORT_DELETED_BY_ADMIN,
    REPORT_CLAIMED,
    CLAIM_APPROVED_BY_ADMIN,
    CLAIM_REJECTED_BY_ADMIN
}
