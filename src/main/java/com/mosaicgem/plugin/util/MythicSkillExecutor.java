package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.config.ConfigManager;
import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketData;
import com.mosaicgem.plugin.model.SocketedGem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * MM 技能宝石执行器：读取玩家主手装备上的 mythicmobs_skill 宝石，
 * 对匹配当前触发器的技能调用 MythicMobs 施放。回退监听与 MythicCrucible 桥共用。
 */
public final class MythicSkillExecutor {

    private final ConfigManager configs;
    private final ItemFactory factory;
    private final MythicMobsBridge mythicMobs;

    public MythicSkillExecutor(ConfigManager configs, ItemFactory factory, MythicMobsBridge mythicMobs) {
        this.configs = configs;
        this.factory = factory;
        this.mythicMobs = mythicMobs;
    }

    /**
     * 按当前触发器施放主手装备上匹配的 MM 技能宝石技能。
     *
     * @param trigger 归一化触发器名（如 SWING / USE）
     * @param target  触发目标（可为 null，MM 自行决定目标）
     * @return 是否至少施放了一个技能
     */
    public boolean cast(Player player, String trigger, Entity target) {
        if (player == null || !player.isOnline() || trigger == null) {
            return false;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            return false;
        }
        SocketData data = factory.readSocketData(item);
        if (data.gems().isEmpty()) {
            return false;
        }
        Entity castTarget = target != null ? target : player;
        boolean cast = false;
        for (SocketedGem gem : data.gems()) {
            GemDefinition definition = configs.getGem(gem.id());
            if (definition == null) {
                continue;
            }
            for (String line : definition.attributeLinesOf(ItemFactory.BUFF_TYPE_MM_SKILL)) {
                MythicSkillLine.Entry entry = MythicSkillLine.parse(factory.resolve(line, gem.values()));
                if (entry == null || !trigger.equals(entry.trigger())) {
                    continue;
                }
                if (mythicMobs.castSkill(player, entry.name(), castTarget)) {
                    cast = true;
                }
            }
        }
        return cast;
    }
}
