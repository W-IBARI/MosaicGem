package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketedGem;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * vanilla_attribute：直接附加到物品属性修饰符，展示为「显示名：数值」。
 */
final class VanillaAttributeHandler implements BuffTypeHandler {

    @Override
    public String id() {
        return ItemFactory.BUFF_TYPE_VANILLA;
    }

    @Override
    public List<String> valueLines(SocketedGem gem, ItemFactory factory) {
        GemDefinition definition = factory.configs().getGem(gem.id());
        if (definition == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : definition.attributeLinesOf(ItemFactory.BUFF_TYPE_VANILLA)) {
            ItemFactory.VanillaAttribute attribute = ItemFactory.parseVanillaAttribute(line);
            if (attribute != null) {
                result.add(factory.configs().attributeName(attribute.id())
                        + "：" + factory.resolve(attribute.value(), gem.values()));
            }
        }
        return result;
    }

    @Override
    public void rebuild(ItemStack item, List<SocketedGem> gems, ItemFactory factory) {
        factory.rebuildVanillaAttributes(item, gems);
    }
}
