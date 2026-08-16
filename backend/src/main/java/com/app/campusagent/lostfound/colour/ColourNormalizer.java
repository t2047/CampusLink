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

    private ColourNormalizer() {
    }

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
        String text = value.trim().toLowerCase(Locale.ROOT);
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
            if (entry.getValue().stream().anyMatch(form -> containsForm(text, form))) {
                expanded.addAll(entry.getValue());
            }
        }
        return expanded;
    }

    /** 颜色表面形式匹配：纯 ASCII 形式用词边界正则，含 CJK 的形式用子串匹配。 */
    private static boolean containsForm(String text, String form) {
        if (form.chars().allMatch(ch -> ch < 128)) {
            return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(form) + "(?![a-z0-9])")
                    .matcher(text)
                    .find();
        }
        return text.contains(form);
    }
}
