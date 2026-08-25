package com.mosaicgem.plugin.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 属性面板合并显示配置（作用于全部属性描述修正；属性名由宝石 {@code LoreChange} 提供）。
 *
 * @param enabled     是否启用合并显示
 * @param newLine     宝石属性在物品上不存在时，追加的新属性行模版（{name} {value}）
 * @param bonusFormat 加成标注格式（跟在数值后，{bonus}）
 * @param display     按属性名的显示格式（factor 缩放、unit 后缀、decimals 小数位），
 *                    用于合并显示与镶嵌信息的数值展示；未配置的属性原样显示。
 */
public record AttributeLoreConfig(
        boolean enabled,
        String newLine,
        String bonusFormat,
        Map<String, DisplayFormat> display
) {

    private static final String DEFAULT_NEW_LINE = "&r&f{name}：&e{value}";
    private static final String DEFAULT_BONUS_FORMAT = "&r（+{bonus}）";

    /**
     * 数值显示格式。
     *
     * @param factor   原始数值的缩放倍数（如比率→百分比用 100；缺省 1）
     * @param unit     显示后缀（如 %；缺省为空）
     * @param decimals 显示小数位（缺省 2；0 表示整数）
     */
    public record DisplayFormat(double factor, String unit, int decimals) {
        static final DisplayFormat DEFAULT = new DisplayFormat(1, "", 2);
    }

    public static AttributeLoreConfig from(ConfigurationSection section) {
        if (section == null) {
            return new AttributeLoreConfig(true, DEFAULT_NEW_LINE, DEFAULT_BONUS_FORMAT, Map.of());
        }
        String newLine = section.getString("new-line", DEFAULT_NEW_LINE);
        if (newLine == null || newLine.isEmpty()) {
            newLine = DEFAULT_NEW_LINE;
        }
        Map<String, DisplayFormat> display = new LinkedHashMap<>();
        ConfigurationSection displaySection = section.getConfigurationSection("display");
        if (displaySection != null) {
            for (String name : displaySection.getKeys(false)) {
                ConfigurationSection entry = displaySection.getConfigurationSection(name);
                if (entry == null) {
                    display.put(name, DisplayFormat.DEFAULT);
                    continue;
                }
                double factor = entry.getDouble("factor", 1);
                int decimals = Math.max(0, Math.min(10, entry.getInt("decimals", 2)));
                String unit = entry.getString("unit", "");
                display.put(name, new DisplayFormat(factor, unit == null ? "" : unit, decimals));
            }
        }
        return new AttributeLoreConfig(
                section.getBoolean("enabled", true),
                newLine,
                section.getString("bonus-format", DEFAULT_BONUS_FORMAT),
                Map.copyOf(display)
        );
    }
}
