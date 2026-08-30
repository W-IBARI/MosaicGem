package com.mosaicgem.plugin.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * buffs 词条（v1.2+ 多加成方式）解析冒烟测试：
 * 新格式 buffs 列表、旧格式 buffType+attribute 兼容、缺省默认，以及按类型取行。
 */
class GemDefinitionBuffsTest {

    private static GemDefinition gemWithBuffs() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("material", "PAPER");
        yaml.set("buffs", List.of(
                Map.of("type", "ce_attribute", "attribute", List.of("bakamc:strength: 10", "bakamc:critical_chance: 0.2")),
                Map.of("type", "enchant", "attribute", List.of("minecraft:sharpness: 3")),
                Map.of("type", "mythicmobs_skill", "attribute", List.of("FireSlash @onSwing"))
        ));
        ConfigurationSection section = yaml;
        return new GemDefinition("复合测试宝石", section);
    }

    @Test
    void buffsParsesMultipleTypes() {
        GemDefinition gem = gemWithBuffs();
        assertEquals(3, gem.getBuffs().size(), "应解析出 3 条词条");
        assertEquals(List.of("ce_attribute", "enchant", "mythicmobs_skill"), gem.buffTypes(), "词条类型按配置顺序去重");
        assertEquals(List.of("bakamc:strength: 10", "bakamc:critical_chance: 0.2"),
                gem.attributeLinesOf("Ce_attribute"), "同类型多行按行提取，类型匹配大小写不敏感");
        assertEquals(List.of("minecraft:sharpness: 3"), gem.attributeLinesOf("enchant"));
        assertTrue(gem.attributeLinesOf("vanilla_attribute").isEmpty(), "未配置的词条类型返回空");
        assertEquals(4, gem.getAttribute().size(), "getAttribute 为全部词条行的聚合视图");
    }

    @Test
    void legacyBuffTypeAutoConverts() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("material", "PAPER");
        yaml.set("buffType", "ce_attribute");
        yaml.set("attribute", List.of("bakamc:strength: 10"));
        GemDefinition gem = new GemDefinition("旧格式宝石", yaml);
        assertEquals(1, gem.getBuffs().size(), "旧格式应转成单条词条");
        assertEquals("ce_attribute", gem.getBuffs().get(0).type());
        assertEquals(List.of("bakamc:strength: 10"), gem.attributeLinesOf("ce_attribute"));
        assertEquals(List.of("ce_attribute"), gem.buffTypes());
    }

    @Test
    void defaultsToSingleSxAttribute() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("material", "PAPER");
        GemDefinition gem = new GemDefinition("默认宝石", yaml);
        assertEquals(1, gem.getBuffs().size(), "两者皆无时维持旧默认：单条 sx_attribute");
        assertEquals("sx_attribute", gem.getBuffs().get(0).type());
        assertTrue(gem.getBuffs().get(0).attribute().isEmpty());
    }

    @Test
    void mixedLegacyAndBuffsPrefersBuffs() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("material", "PAPER");
        yaml.set("buffType", "enchant");
        yaml.set("attribute", List.of("minecraft:sharpness: 1"));
        yaml.set("buffs", List.of(Map.of("type", "ce_attribute", "attribute", List.of("bakamc:strength: 5"))));
        GemDefinition gem = new GemDefinition("混合宝石", yaml);
        assertEquals(List.of("ce_attribute"), gem.buffTypes(), "配置了 buffs 时优先使用 buffs，忽略旧字段");
        assertFalse(gem.buffTypes().contains("enchant"));
    }
}
