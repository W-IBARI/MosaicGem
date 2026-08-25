package com.mosaicgem.plugin.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宝石配置。
 */
public class GemDefinition extends ItemDefinition {

    private final Integer repetitions;
    private final Map<String, String> random;
    private final String buffType;
    private final List<String> attribute;
    /**
     * 拆卸时宝石损毁（消失）概率，0~100 对应 0%~100%；与拆卸器成功率无关，
     * 拆卸器成功后仍需独立判定。配置缺省或非法值时钳制到 0~100（默认 0 = 永不损毁）。
     */
    private final int removeDestroyChance;
    /**
     * 宝石类型标签（gemtype），用于装备上的同类型宝石数量上限校验。
     * 空列表表示该宝石没有标签（不参与类型计数）。
     */
    private final List<String> gemType;
    private final Map<String, String> loreChange;

    public GemDefinition(String id, ConfigurationSection section) {
        super(id, section);
        this.repetitions = section.contains("repetitions") ? section.getInt("repetitions") : null;
        this.random = new LinkedHashMap<>();
        ConfigurationSection randomSection = section.getConfigurationSection("random");
        if (randomSection != null) {
            for (String key : randomSection.getKeys(false)) {
                random.put(key, randomSection.getString(key));
            }
        }
        this.buffType = section.getString("buffType", "sx_attribute");
        this.attribute = section.getStringList("attribute");
        // 不填默认为 0（永不损毁），越界值钳制到 0~100
        this.removeDestroyChance = clampChance(section.getInt("remove-destroy-chance", 0));
        // gemtype：不填则为空列表，不参与类型计数
        this.gemType = section.getStringList("gemtype");
        this.loreChange = new LinkedHashMap<>();
        List<Map<?, ?>> loreChangeSection = section.getMapList("LoreChange");
        if (loreChangeSection != null) {
            for (Map<?, ?> entry : loreChangeSection) {
                for (Map.Entry<?, ?> pair : entry.entrySet()) {
                    if (pair.getKey() != null && pair.getValue() != null) {
                        loreChange.put(String.valueOf(pair.getKey()).trim(), String.valueOf(pair.getValue()).trim());
                    }
                }
            }
        }
    }

    /**
     * 将概率限制在 0~100（非法配置按 0 处理，不损毁）。
     */
    private static int clampChance(int value) {
        return Math.max(0, Math.min(100, value));
    }

    public Integer getRepetitions() {
        return repetitions;
    }

    public Map<String, String> getRandom() {
        return random;
    }

    public String getBuffType() {
        return buffType;
    }

    public List<String> getAttribute() {
        return attribute;
    }

    public int getRemoveDestroyChance() {
        return removeDestroyChance;
    }

    public List<String> getGemType() {
        return gemType;
    }

    /**
     * 属性行首标识符的 LoreChange 映射（标识符 → 属性名）。
     * 无映射的属性「不动作」：不参与属性面板合并，也不影响展示。
     */
    public Map<String, String> getLoreChange() {
        return loreChange;
    }
}
