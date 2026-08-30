package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketedGem;

import java.util.ArrayList;
import java.util.List;

/**
 * mythicmobs_skill：属性行是 MythicMobs 技能名（支持 @触发器），展示时去掉触发器后缀。
 */
final class MythicMobSkillHandler implements BuffTypeHandler {

    @Override
    public String id() {
        return ItemFactory.BUFF_TYPE_MM_SKILL;
    }

    @Override
    public List<String> valueLines(SocketedGem gem, ItemFactory factory) {
        GemDefinition definition = factory.configs().getGem(gem.id());
        if (definition == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : definition.attributeLinesOf(ItemFactory.BUFF_TYPE_MM_SKILL)) {
            String name = MythicSkillLine.displayName(factory.resolve(line, gem.values()));
            if (!name.isEmpty()) {
                result.add(name);
            }
        }
        return result;
    }
}
