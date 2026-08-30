package com.mosaicgem.plugin.config;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宝石配置。
 */
public class GemDefinition extends ItemDefinition {

    private final Integer repetitions;
    private final Map<String, String> random;
    /**
     * 宝石词条（v1.2+）：一颗宝石可含多条不同加成方式；
     * 旧配置 buffType + attribute 在解析时自动转成单条词条。
     */
    private final List<BuffDef> buffs;
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
        this.buffs = parseBuffs(section);
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

    /**
     * 全部词条（配置顺序）。
     */
    public List<BuffDef> getBuffs() {
        return buffs;
    }

    /**
     * 全部词条的属性行（宝石级聚合视图，用于 LoreChange 属性面板等通用处理）。
     */
    public List<String> getAttribute() {
        List<String> out = new ArrayList<>();
        for (BuffDef buff : buffs) {
            out.addAll(buff.attribute());
        }
        return out;
    }

    /**
     * 指定 buffType 下的全部属性行（大小写不敏感）；无该词条时返回空列表。
     */
    public List<String> attributeLinesOf(String type) {
        if (type == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (BuffDef buff : buffs) {
            if (buff.type().equalsIgnoreCase(type)) {
                out.addAll(buff.attribute());
            }
        }
        return out;
    }

    /**
     * 该宝石的全部 buffType（去重，按词条配置顺序）。
     */
    public List<String> buffTypes() {
        List<String> out = new ArrayList<>();
        for (BuffDef buff : buffs) {
            if (!out.contains(buff.type())) {
                out.add(buff.type());
            }
        }
        return out;
    }

    /**
     * 解析词条：优先读 {@code buffs:}（方案A，v1.2+）；
     * 旧格式 {@code buffType + attribute} 自动转成单条词条（向后兼容）。
     * 两者皆无时维持旧默认：单条 sx_attribute 空词条。
     */
    private static List<BuffDef> parseBuffs(ConfigurationSection section) {
        List<BuffDef> buffs = new ArrayList<>();
        List<Map<?, ?>> buffList = section.getMapList("buffs");
        for (Map<?, ?> entry : buffList) {
            Object typeObj = entry.get("type");
            if (typeObj == null || String.valueOf(typeObj).isBlank()) {
                continue;
            }
            List<String> attribute = new ArrayList<>();
            Object attrObj = entry.get("attribute");
            if (attrObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        attribute.add(String.valueOf(item));
                    }
                }
            } else if (attrObj != null) {
                attribute.add(String.valueOf(attrObj));
            }
            buffs.add(new BuffDef(String.valueOf(typeObj).trim().toLowerCase(java.util.Locale.ROOT), attribute));
        }
        if (buffs.isEmpty() && (section.contains("buffType") || section.contains("attribute"))) {
            String type = section.getString("buffType", "sx_attribute");
            buffs.add(new BuffDef(type.trim().toLowerCase(java.util.Locale.ROOT), section.getStringList("attribute")));
        }
        if (buffs.isEmpty()) {
            buffs.add(new BuffDef("sx_attribute", List.of()));
        }
        return List.copyOf(buffs);
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
