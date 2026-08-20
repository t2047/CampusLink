/**
 * 报告类型枚举。
 * <p>
 * 区分失物招领报告的两种方向："我丢了东西"({@link #LOST})与
 * "我捡到东西"({@link #FOUND})。在 {@code LostFoundReport} 中通过
 * {@code @Enumerated(EnumType.STRING)} 以枚举名按字符串形式写入数据库
 * lost_found_reports 表的 report_type 列，用于搜索匹配时的方向约束。
 */
package com.app.campusagent.lostfound.domain;

public enum ReportType {
    /** 失物：用户遗失了物品，希望他人归还或提供线索。 */
    LOST,
    /** 招领：用户拾到了物品，等待失主认领。 */
    FOUND
}
