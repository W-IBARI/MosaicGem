package com.mosaicgem.plugin.service;

import com.mosaicgem.plugin.config.AttributeLoreConfig;
import com.mosaicgem.plugin.config.ConfigManager;
import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketedGem;
import com.mosaicgem.plugin.util.ItemFactory;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 属性面板合并：把宝石属性合并进物品原有的属性 lore 行，
 * 显示为「总值（+宝石加成）」，并用 §X 标记隔离加成文字，属性面板只解析纯数值。
 * 属性的识别名由每个宝石的 {@code LoreChange} 映射提供（标识符 → 属性名）；无映射的属性不动作。
 */
public class AttributeLoreService {

    /** 合并行标记：属性面板解析该标记之前的内容，标记后的加成文字不影响属性计算 */
    public static final String MARKER = ItemFactory.LORE_MARKER;

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern RANGE_SECOND = Pattern.compile("-\\s*(\\d+(?:\\.\\d+)?)");

    private final ConfigManager configs;
    private final ItemFactory factory;

    public AttributeLoreService(ConfigManager configs, ItemFactory factory) {
        this.configs = configs;
        this.factory = factory;
    }

    /**
     * 根据当前已镶嵌宝石，重建物品属性面板。
     */
    public void update(ItemStack item, List<SocketedGem> gems) {
        if (item == null || item.getType().isAir()) {
            return;
        }
        AttributeLoreConfig cfg = configs.attributeLore();
        if (!cfg.enabled()) {
            return;
        }

        Map<String, Bonus> bonuses = collectBonuses(gems);
        Map<String, String> baseLines = new LinkedHashMap<>(factory.readBaseLines(item));
        Set<String> knownNames = new LinkedHashSet<>();
        knownNames.addAll(baseLines.keySet());
        knownNames.addAll(bonuses.keySet());

        List<String> lore = new ArrayList<>();
        if (item.getItemMeta() != null && item.getItemMeta().hasLore()) {
            lore.addAll(item.getItemMeta().getLore());
        }
        // 镶嵌信息区块是纯展示行（行首带 SOCKET_MARKER），合并属性前先清掉，避免被误当成属性行
        lore.removeIf(line -> line.startsWith(ItemFactory.SOCKET_MARKER));

        // 1. 还原/清理旧的合并行
        restoreMarkedLines(lore, baseLines, knownNames);
        // 2. 兼容旧版本：清除曾经直接追加的宝石属性行
        for (SocketedGem gem : gems) {
            if (!gem.lines().isEmpty()) {
                lore.removeAll(gem.lines());
            }
        }
        // 3. 按当前宝石重新合并
        applyBonuses(lore, baseLines, bonuses, cfg);

        factory.setLore(item, lore);
        factory.writeBaseLines(item, baseLines);
    }

    // ------------------------------------------------------------------
    // 属性识别与加成汇总
    // ------------------------------------------------------------------

    private Map<String, Bonus> collectBonuses(List<SocketedGem> gems) {
        Map<String, Bonus> bonuses = new LinkedHashMap<>();
        for (SocketedGem gem : gems) {
            GemDefinition definition = configs.getGem(gem.id());
            if (definition == null) {
                continue;
            }
            for (String attributeLine : definition.getAttribute()) {
                // 属性名由宝石自身的 LoreChange 映射提供：标识符 → 属性名；无映射则不动作
                String identifier = ItemFactory.loreIdentifier(attributeLine);
                String name = identifier == null ? null : definition.getLoreChange().get(identifier);
                if (name == null) {
                    continue;
                }
                // 标识符本身可能是数字，取值前必须先剥离，避免把标识符当作属性值
                String resolved = ItemFactory.stripLoreIdentifier(factory.resolve(attributeLine, gem.values()));
                ParsedNumber parsed = parseFirstNumber(resolved);
                if (parsed == null) {
                    continue;
                }
                bonuses.merge(name, new Bonus(parsed.value(), parsed.decimals(), 1), Bonus::merge);
            }
        }
        return bonuses;
    }

    // ------------------------------------------------------------------
    // Lore 重建
    // ------------------------------------------------------------------

    private void restoreMarkedLines(List<String> lore, Map<String, String> baseLines, Set<String> knownNames) {
        ListIterator<String> iterator = lore.listIterator();
        while (iterator.hasNext()) {
            String line = iterator.next();
            if (!line.contains(MARKER)) {
                continue;
            }
            String name = matchAttributeName(line, knownNames);
            String original = name == null ? null : baseLines.get(name);
            if (original != null) {
                iterator.set(original);
            } else {
                iterator.remove();
            }
        }
    }

    private void applyBonuses(List<String> lore, Map<String, String> baseLines, Map<String, Bonus> bonuses, AttributeLoreConfig cfg) {
        if (bonuses.isEmpty()) {
            return;
        }
        List<String> append = new ArrayList<>();
        for (Map.Entry<String, Bonus> entry : bonuses.entrySet()) {
            String name = entry.getKey();
            Bonus bonus = entry.getValue();
            if (bonus.value() <= 0) {
                continue;
            }

            int index = findAttributeLine(lore, name);
            if (index >= 0) {
                String current = lore.get(index);
                String original = baseLines.getOrDefault(name, current);
                baseLines.putIfAbsent(name, current);
                ParsedNumber parsed = parseFirstNumber(original);
                if (parsed != null) {
                    lore.set(index, buildMerged(original, parsed, bonus, cfg.bonusFormat()));
                }
            } else {
                String valueText = formatNumber(bonus.value(), 2);
                String line = cfg.newLine()
                        .replace("{name}", name)
                        .replace("{value}", valueText)
                        + MARKER
                        + cfg.bonusFormat().replace("{bonus}", formatBonus(bonus));
                append.add(line);
            }
        }
        lore.addAll(append);
    }

    private String buildMerged(String original, ParsedNumber parsed, Bonus bonus, String bonusFormat) {
        String unit = parsed.unit();
        double bonusValue = bonus.value();
        StringBuilder builder = new StringBuilder(parsed.prefix());
        if (parsed.hasRange()) {
            builder.append(formatNumber(parsed.value() + bonusValue, parsed.decimals()))
                    .append('-')
                    .append(formatNumber(parsed.secondValue() + bonusValue, parsed.decimals()));
        } else {
            builder.append(formatNumber(parsed.value() + bonusValue, parsed.decimals()));
        }
        builder.append(unit);
        builder.append(MARKER);
        builder.append(bonusFormat.replace("{bonus}", formatBonus(bonus) + unit));
        return builder.toString();
    }

    // ------------------------------------------------------------------
    // 解析与匹配
    // ------------------------------------------------------------------

    private int findAttributeLine(List<String> lore, String name) {
        for (int i = 0; i < lore.size(); i++) {
            if (ItemFactory.stripLoreText(lore.get(i)).startsWith(name)) {
                return i;
            }
        }
        return -1;
    }

    private String matchAttributeName(String line, Set<String> names) {
        String stripped = ItemFactory.stripLoreText(line);
        String best = null;
        for (String name : names) {
            if (stripped.startsWith(name) && (best == null || name.length() > best.length())) {
                best = name;
            }
        }
        return best;
    }

    private ParsedNumber parseFirstNumber(String line) {
        String masked = mask(line);
        Matcher matcher = NUMBER.matcher(masked);
        if (!matcher.find()) {
            return null;
        }
        int start = matcher.start();
        int end = matcher.end();
        double value = Double.parseDouble(masked.substring(start, end));
        int decimals = decimalsOf(masked.substring(start, end));

        boolean hasRange = false;
        double secondValue = Double.NaN;
        int secondEnd = end;
        Matcher rangeMatcher = RANGE_SECOND.matcher(masked);
        if (rangeMatcher.find(end)) {
            hasRange = true;
            secondValue = Double.parseDouble(rangeMatcher.group(1));
            decimals = Math.max(decimals, decimalsOf(rangeMatcher.group(1)));
            secondEnd = rangeMatcher.end();
        }
        String unit = line.substring(secondEnd).trim();
        return new ParsedNumber(value, hasRange, secondValue, decimals, line.substring(0, start), unit);
    }

    /**
     * 把颜色代码（§x）和 <#XXXXXX> 替换为等长空格，保证索引不偏移。
     */
    private String mask(String line) {
        String masked = line.replaceAll("<#[0-9a-fA-F]{6}>", "         ");
        return masked.replaceAll("\u00A7.", "  ");
    }

    private int decimalsOf(String number) {
        int index = number.indexOf('.');
        return index < 0 ? 0 : number.length() - index - 1;
    }

    private String formatNumber(double value, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    /**
     * 单颗宝石生效时去掉多余小数零；多颗宝石生效时取小数位最多的宝石的位数。
     */
    private String formatBonus(Bonus bonus) {
        if (bonus.count() <= 1) {
            return BigDecimal.valueOf(bonus.value()).stripTrailingZeros().toPlainString();
        }
        return String.format(Locale.ROOT, "%." + bonus.decimals() + "f", bonus.value());
    }

    private record ParsedNumber(
            double value,
            boolean hasRange,
            double secondValue,
            int decimals,
            String prefix,
            String unit
    ) {
    }

    private record Bonus(double value, int decimals, int count) {

        private static Bonus merge(Bonus first, Bonus second) {
            return new Bonus(
                    first.value() + second.value(),
                    Math.max(first.decimals(), second.decimals()),
                    first.count() + second.count()
            );
        }
    }
}
