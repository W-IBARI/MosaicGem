package com.mosaicgem.plugin.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * 属性面板合并显示配置（作用于全部属性描述修正；属性名由宝石 {@code LoreChange} 提供）。
 *
 * @param enabled     是否启用合并显示
 * @param newLine     宝石属性在物品上不存在时，追加的新属性行模版（{name} {value}）
 * @param bonusFormat 加成标注格式（跟在数值后，{bonus}）
 */
public record AttributeLoreConfig(
        boolean enabled,
        String newLine,
        String bonusFormat
) {

    private static final String DEFAULT_NEW_LINE = "&r&f{name}：&e{value}";
    private static final String DEFAULT_BONUS_FORMAT = "&r（+{bonus}）";

    public static AttributeLoreConfig from(ConfigurationSection section) {
        if (section == null) {
            return new AttributeLoreConfig(true, DEFAULT_NEW_LINE, DEFAULT_BONUS_FORMAT);
        }
        String newLine = section.getString("new-line", DEFAULT_NEW_LINE);
        if (newLine == null || newLine.isEmpty()) {
            newLine = DEFAULT_NEW_LINE;
        }
        return new AttributeLoreConfig(
                section.getBoolean("enabled", true),
                newLine,
                section.getString("bonus-format", DEFAULT_BONUS_FORMAT)
        );
    }
}
