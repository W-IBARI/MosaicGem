package com.mosaicgem.plugin.util;

import java.util.Locale;

/**
 * MythicMobs 技能行的纯解析器（无 Bukkit 依赖，可单元测试）。
 *
 * <p>支持 MythicCrucible 风格：{@code 技能名 @触发器}、{@code skill:技能名 @触发器} 或纯技能名
 * （默认 {@code SWING}）。触发器名大小写不敏感，{@code on} 前缀可省略。
 *
 * <p>可选「展示后缀」：跟在技能名/触发器后第一个 {@code |} 之后，只用于镶嵌信息展示，
 * 不参与施放与触发匹配。例：{@code 净化 @USE | 冷却 ${cd} 秒} → 显示 {@code 净化 冷却 137 秒}。
 */
public final class MythicSkillLine {

    private MythicSkillLine() {
    }

    public record Entry(String name, String trigger, String suffix) {
    }

    /**
     * 解析技能行，返回技能名、归一化后的触发器（如 {@code SWING} / {@code USE}）与展示后缀。
     * 无法解析时返回 null。
     */
    public static Entry parse(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        String trigger = "SWING";
        int at = text.lastIndexOf('@');
        String head = text;
        String tail = null;
        if (at >= 0 && at + 1 < text.length()) {
            head = text.substring(0, at).trim();
            tail = text.substring(at + 1);
        }
        // 后缀跟在触发器（没有 @ 时跟在技能名）之后的第一个 '|' 后面，保留其前导空格
        String suffix = "";
        String source = tail != null ? tail : head;
        int bar = source.indexOf('|');
        if (bar >= 0) {
            suffix = stripTrailing(source.substring(bar + 1));
            String triggerPart = source.substring(0, bar).trim();
            if (tail != null) {
                tail = triggerPart;
            } else {
                head = triggerPart;
            }
        }
        if (tail != null) {
            trigger = normalizeTrigger(tail.trim());
        }
        text = head.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("skill:")) {
            text = text.substring("skill:".length()).trim();
        }
        return text.isEmpty() ? null : new Entry(text, trigger, suffix);
    }

    /**
     * 生成镶嵌信息展示名：技能名 + 展示后缀（如 {@code 净化 冷却 137 秒}）。
     */
    public static String displayName(String raw) {
        Entry entry = parse(raw);
        if (entry == null) {
            return "";
        }
        return entry.name() + entry.suffix();
    }

    private static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * 归一化触发器：去空白、去 {@code on} 前缀、转大写（{@code onSwing} / {@code SWING} → {@code SWING}）。
     */
    public static String normalizeTrigger(String raw) {
        if (raw == null) {
            return "SWING";
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return "SWING";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("on")) {
            value = value.substring(2);
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
