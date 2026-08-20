/**
 * 失物招领物品类别枚举。
 * <p>
 * 用于对报告中的物品(item_name)归类，在 {@code LostFoundReport} 中通过
 * {@code @Enumerated(EnumType.STRING)} 以枚举名按字符串形式写入数据库
 * lost_found_reports 表的 category 列，并参与候选匹配与按类别筛选。
 */
package com.app.campusagent.lostfound.domain;

public enum ItemCategory {
    /** 电子产品：手机、笔记本电脑、耳机、充电宝等。 */
    ELECTRONICS,
    /** 衣物：外套、帽子、围巾、手套等。 */
    CLOTHING,
    /** 书籍文具：教材、笔记本、笔等。 */
    BOOKS_STATIONERY,
    /** 钥匙：宿舍、教室、门禁、自行车钥匙等。 */
    KEYS,
    /** 钱包、卡包、钱夹。 */
    WALLET_PURSE,
    /** 身份证、学生证、校园卡等证件。 */
    ID_CARD,
    /** 箱包：书包、旅行包、手提袋等。 */
    BAG,
    /** 雨伞。 */
    UMBRELLA,
    /** 其他未单独归类的物品。 */
    OTHER
}
