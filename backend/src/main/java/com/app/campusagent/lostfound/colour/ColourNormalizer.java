/**
 * 颜色归一化工具（canonical colour normalization）。
 * <p>解决失物招领搜索时"颜色跨语言 / 近义词不一致"的问题：把英文形式
 * （white / ivory / cream）与中文形式（白色 / 米白 / 乳白）统一归一到同一个
 * canonical 颜色 code（如 WHITE），使 SQL 颜色预过滤能按整组表面形式展开匹配，
 * 而不是用原始的 {@code lower(colour) like %input%}（该写法永远匹配不到中文颜色值）。
 * <p>被失物招领的搜索 / 过滤 Service 调用（canonicalCodes / expand）；
 * 颜色分组表必须与 Python 侧 agent 的 {@code matching.py COLOUR_GROUPS} 保持一致，
 * 任何改动需双端同步。
 */
package com.app.campusagent.lostfound.colour;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Canonical colour normalization shared with the agent's
 * {@code matching.py COLOUR_GROUPS} — keep this table in sync with the Python side.
 *
 * <p>Fixes P0 "colour cross-language/synonym inconsistency": maps
 * {@code white / White / 白色 / ivory / cream} to the same canonical code so the SQL
 * colour pre-filter can expand to all surface forms instead of dropping candidates
 * on a raw {@code lower(colour) like %input%} that never matches a Chinese value.
 *
 * <p>Conservative merging: only cross-language + clear synonyms (gray/grey,
 * ivory/cream→White, navy/dark blue→Blue, gold/golden→Gold). silver/grey and
 * gold/yellow stay separate to avoid near-synonym false recalls. Single-character
 * Chinese forms (白/黑/蓝…) are intentionally omitted so substring matching never
 * hits unrelated words like 明白/黑板.
 */
public final class ColourNormalizer {

    /** canonical code → surface forms (lowercase). */
    private static final Map<String, List<String>> GROUPS = buildGroups();

    /** 工具类，禁止实例化（仅提供静态方法）。 */
    private ColourNormalizer() {
    }

    /**
     * 构建 canonical 颜色分组表（LinkedHashMap 保持插入顺序）。
     * <p>策略是"保守合并"：只做跨语言 + 明确的近义词归组
     * （如 ivory/cream→WHITE、navy/dark blue→BLUE、gold/golden→GOLD）；
     * silver 与 grey、gold 与 yellow 刻意分开，避免近似词造成误召回。
     * 单字中文形式（白/黑/蓝…）有意不收，防止子串匹配命中"明白/黑板"等无关词。
     */
    private static Map<String, List<String>> buildGroups() {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("WHITE",
                List.of("white", "ivory", "cream", "白色", "米白", "乳白", "纯白", "象牙白", "奶白"));
        groups.put("BLACK", List.of("black", "charcoal", "黑色", "纯黑", "墨黑", "乌黑"));
        groups.put("GREY", List.of("grey", "gray", "灰色", "银灰", "浅灰", "深灰"));
        groups.put("BLUE", List.of("blue", "navy", "navy blue", "dark blue", "light blue", "sky blue",
                "cyan", "teal", "azure", "蓝色", "深蓝", "浅蓝", "天蓝", "藏蓝", "宝蓝", "淡蓝", "湖蓝"));
        groups.put("RED",
                List.of("red", "maroon", "crimson", "scarlet", "红色", "深红", "浅红", "酒红", "枣红", "朱红", "大红"));
        groups.put("GREEN",
                List.of("green", "olive", "emerald", "jade", "绿色", "深绿", "浅绿", "翠绿", "墨绿", "草绿", "橄榄绿"));
        groups.put("YELLOW", List.of("yellow", "amber", "黄色", "杏黄", "米黄", "淡黄"));
        groups.put("GOLD", List.of("gold", "golden", "金色", "金黄", "金黄色"));
        groups.put("SILVER", List.of("silver", "银色", "银白"));
        groups.put("PURPLE", List.of("purple", "violet", "lavender", "紫色", "淡紫", "紫罗兰"));
        groups.put("PINK", List.of("pink", "粉色", "粉红", "桃红", "浅粉"));
        groups.put("ORANGE", List.of("orange", "橙色", "橘色", "桔色"));
        groups.put("BROWN",
                List.of("brown", "tan", "beige", "bronze", "棕色", "褐色", "咖啡色", "茶色", "卡其色", "驼色"));
        groups.put("TRANSPARENT", List.of("transparent", "clear", "透明", "无色"));
        return groups;
    }

    /** 返回 value 命中的 canonical 颜色 code 集合；空值/未命中返回空集。 */
    public static Set<String> canonicalCodes(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        // 统一去除首尾空白并转小写，保证英文形式匹配不区分大小写
        String text = value.trim().toLowerCase(Locale.ROOT);
        // 遍历所有颜色组，只要组内任一表面形式被命中，就把该组 code 计入结果集
        return GROUPS.entrySet().stream()
                .filter(entry -> entry.getValue().stream().anyMatch(form -> containsForm(text, form)))
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    /**
     * 颜色过滤扩展：输入命中 canonical 表时返回该组全部表面形式（用于 SQL OR 扩展，
     * 使 {@code white} 能命中数据库里 {@code 白色} 的候选）；未命中返回空 list，
     * 调用方回退到原始 {@code lower(colour) like}。
     */
    public static List<String> expand(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        List<String> expanded = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : GROUPS.entrySet()) {
            // 只要组内任意形式命中，就把整组表面形式收集起来，供 SQL IN / OR 扩展
            if (entry.getValue().stream().anyMatch(form -> containsForm(text, form))) {
                expanded.addAll(entry.getValue());
            }
        }
        return expanded;
    }

    /** 颜色表面形式匹配：纯 ASCII 形式用词边界正则，含 CJK 的形式用子串匹配。 */
    private static boolean containsForm(String text, String form) {
        // 形式全为 ASCII（字符码 < 128）时走正则：
        // Pattern.quote 转义正则特殊字符；(?<![a-z0-9]) 与 (?![a-z0-9]) 是自定义词边界
        // 断言，保证 "grey" 不会误命中 "greyhound" 之类的更长单词
        if (form.chars().allMatch(ch -> ch < 128)) {
            return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(form) + "(?![a-z0-9])")
                    .matcher(text)
                    .find();
        }
        // 含中文字符的形式无法分词，直接做子串包含匹配
        return text.contains(form);
    }
}
