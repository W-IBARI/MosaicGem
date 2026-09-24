package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketedGem;

import java.util.ArrayList;
import java.util.List;

/**
 * gfx_effect：宝石声明"调用哪个 GrimoireFX 效果、给多久、几级"。
 *
 * <p>本类型不写任何物品数据；实际施放 / 续期 / 撤销由 {@code GfxAuraService}
 * 在玩家【装备该宝石期间】通过 {@link GrimoireFXBridge} 调用 GrimoireFX 完成
 * （摘下即撤销，且只撤本插件施放的那条）。
 *
 * <p>属性行格式（可用 ${} 表达式）：
 * <pre>
 * gem_night_vision              # 用 GFX 效果定义自身的时长
 * gem_night_vision: 60          # 覆盖为 60 秒
 * gem_night_vision: 60: 2       # 60 秒、等级 2（时长和等级都可写 -1 = 用效果定义）
 * minecraft:night_vision: 60    # 带命名空间的效果 id（从右往左识别数字尾段，id 可含冒号）
 * </pre>
 */
public final class GfxEffectHandler implements BuffTypeHandler {

    /** 行解析结果：效果 id / 时长秒（null = 用效果定义自身时长）/ 等级。 */
    public record EffectSpec(String effectId, Long durationSeconds, int level) {
    }

    @Override
    public String id() {
        return ItemFactory.BUFF_TYPE_GFX;
    }

    @Override
    public List<String> valueLines(SocketedGem gem, ItemFactory factory) {
        GemDefinition definition = factory.configs().getGem(gem.id());
        if (definition == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String raw : definition.attributeLinesOf(ItemFactory.BUFF_TYPE_GFX)) {
            EffectSpec spec = parse(factory.resolve(raw, gem.values()));
            if (spec == null) {
                continue;
            }
            String duration = (spec.durationSeconds() == null || spec.durationSeconds() < 0)
                    ? "常驻" : spec.durationSeconds() + " 秒";
            String level = spec.level() > 1 ? "，等级 " + spec.level() : "";
            result.add("效果：" + spec.effectId() + "（" + duration + level + "）");
        }
        return result;
    }

    /**
     * 解析一行效果声明：{@code '<效果id>[: <秒数>][: <等级>]'}。
     * 从右往左识别"数字尾段"（最多识别两个：秒数与等级），剩余部分作为效果 id ——
     * 因此 id 可以带命名空间冒号（如 {@code minecraft:night_vision}）。
     */
    public static EffectSpec parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        // 行首数字标识符（如 '1: '）可剥离；效果 id 本身不会是纯数字，不会误伤
        String text = ItemFactory.stripLoreIdentifier(line.trim());
        if (text.isEmpty()) {
            return null;
        }
        String[] fields = text.split(":", -1);
        int end = fields.length;
        Long duration = null;
        int level = 1;
        if (end >= 3 && isLong(fields[end - 1].trim()) && isLong(fields[end - 2].trim())) {
            duration = toLong(fields[end - 2].trim());
            level = (int) Math.max(1L, toLong(fields[end - 1].trim()));
            end -= 2;
        } else if (end >= 2 && isLong(fields[end - 1].trim())) {
            duration = toLong(fields[end - 1].trim());
            end -= 1;
        }
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < end; i++) {
            if (i > 0) {
                id.append(':');
            }
            id.append(fields[i]);
        }
        String effectId = id.toString().trim();
        while (effectId.endsWith(":")) {
            effectId = effectId.substring(0, effectId.length() - 1).trim();
        }
        if (effectId.isEmpty()) {
            return null;
        }
        return new EffectSpec(effectId, duration, level);
    }

    private static boolean isLong(String text) {
        if (text.isEmpty()) {
            return false;
        }
        try {
            Long.parseLong(text);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static long toLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
