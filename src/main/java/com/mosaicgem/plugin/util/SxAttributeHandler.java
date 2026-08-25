package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketedGem;

import java.util.ArrayList;
import java.util.List;

/**
 * sx_attribute：属性行写入装备 lore，由 SX-Attribute 读取；
 * 本身不持久化生效数据，重建无操作。
 */
final class SxAttributeHandler implements BuffTypeHandler {

    @Override
    public String id() {
        return ItemFactory.BUFF_TYPE_SX;
    }

    @Override
    public boolean usesLoreLines() {
        return true;
    }

    @Override
    public List<String> valueLines(SocketedGem gem, ItemFactory factory) {
        GemDefinition definition = factory.configs().getGem(gem.id());
        if (definition == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : definition.getAttribute()) {
            // 标识符是内部标记（LoreChange 映射用），展示前剥离
            String text = ItemFactory.stripLoreText(ItemFactory.stripLoreIdentifier(factory.resolve(line, gem.values())));
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }
}
