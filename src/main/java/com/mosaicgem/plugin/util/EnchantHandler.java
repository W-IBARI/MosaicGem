package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketedGem;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * enchant：附加/叠加到物品附魔，展示为「附魔名 +N」。
 */
final class EnchantHandler implements BuffTypeHandler {

    @Override
    public String id() {
        return ItemFactory.BUFF_TYPE_ENCHANT;
    }

    @Override
    public List<String> valueLines(SocketedGem gem, ItemFactory factory) {
        GemDefinition definition = factory.configs().getGem(gem.id());
        if (definition == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : definition.attributeLinesOf(ItemFactory.BUFF_TYPE_ENCHANT)) {
            ItemFactory.VanillaAttribute attribute = ItemFactory.parseVanillaAttribute(line);
            if (attribute != null) {
                result.add(display(ItemFactory.normalizeEnchantId(attribute.id()),
                        factory.resolve(attribute.value(), gem.values()), factory));
            }
        }
        return result;
    }

    @Override
    public void rebuild(ItemStack item, List<SocketedGem> gems, ItemFactory factory) {
        factory.rebuildEnchantments(item, gems);
    }

    private static String display(String id, String resolved, ItemFactory factory) {
        String name = factory.configs().enchantName(id);
        if (name.equals(id) && ItemFactory.isCrazyEnchantId(id)) {
            String custom = factory.crazyEnchants().getDisplayName(ItemFactory.crazyNameOf(id));
            if (custom != null && !custom.equalsIgnoreCase(ItemFactory.crazyNameOf(id))) {
                name = custom;
            }
        }
        try {
            int level = (int) Math.round(Double.parseDouble(resolved.trim()));
            return name + " " + (level >= 0 ? "+" : "") + level;
        } catch (NumberFormatException e) {
            return name + " " + resolved;
        }
    }
}
