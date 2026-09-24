package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.config.ConfigManager;
import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketData;
import com.mosaicgem.plugin.model.SocketedGem;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MM 技能宝石执行器：读取玩家【主手优先、副手回退】装备上的 mythicmobs_skill 宝石，
 * 对匹配当前触发器的技能调用 MythicMobs 施放。回退监听与 MythicCrucible 桥共用。
 *
 * <p>副手回退的意义：盾牌这类主要放副手用的装备，主手常常拿着武器。
 * MythicCrucible 对"副手右键"触发的是 RIGHTCLICK（主手右键是 USE），
 * 所以盾牌宝石的技能行写 {@code @RIGHTCLICK} 即可命中。
 * 主手只要有一条技能行命中就不再查副手，避免两只手都镶同类宝石时双触发。
 */
public final class MythicSkillExecutor {

    /**
     * 同一玩家 + 同一技能的最小重复施放间隔（毫秒）。
     * MythicCrucible 处理"副手右键"时，会先后抛出主手与副手两个交互事件，
     * 两个事件都能命中宝石技能行 —— 不去重会一次右键施放两次（多一条冷却提示，效果也可能生效两次）。
     */
    private static final long DEDUPE_WINDOW_MS = 150L;

    private final ConfigManager configs;
    private final ItemFactory factory;
    private final MythicMobsBridge mythicMobs;
    private final Map<UUID, Map<String, Long>> lastCastAt = new ConcurrentHashMap<>();

    public MythicSkillExecutor(ConfigManager configs, ItemFactory factory, MythicMobsBridge mythicMobs) {
        this.configs = configs;
        this.factory = factory;
        this.mythicMobs = mythicMobs;
    }

    /**
     * 按当前触发器施放装备上匹配的 MM 技能宝石技能（主手优先，主手无匹配时回退副手）。
     *
     * @param trigger 归一化触发器名（如 SWING / USE / RIGHTCLICK）
     * @param target  触发目标（可为 null，MM 自行决定目标）
     * @return 是否至少施放了一个技能
     */
    public boolean cast(Player player, String trigger, Entity target) {
        if (player == null || !player.isOnline() || trigger == null) {
            return false;
        }
        Entity castTarget = target != null ? target : player;
        if (castFrom(player, player.getInventory().getItemInMainHand(), trigger, castTarget)) {
            return true;
        }
        return castFrom(player, player.getInventory().getItemInOffHand(), trigger, castTarget);
    }

    /** 尝试用某只手的物品施放匹配触发器的技能。 */
    private boolean castFrom(Player player, ItemStack item, String trigger, Entity castTarget) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        SocketData data = factory.readSocketData(item);
        if (data.gems().isEmpty()) {
            return false;
        }
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
                if (!claimCast(player, entry.name())) {
                    continue;
                }
                if (mythicMobs.castSkill(player, entry.name(), castTarget)) {
                    cast = true;
                }
            }
        }
        return cast;
    }

    /** 去重窗口内的重复施放：返回 true = 允许这次施放（并记账）。 */
    private boolean claimCast(Player player, String skillName) {
        long now = System.currentTimeMillis();
        Map<String, Long> perSkill = lastCastAt.computeIfAbsent(player.getUniqueId(), key -> new ConcurrentHashMap<>());
        Long last = perSkill.get(skillName);
        if (last != null && now - last < DEDUPE_WINDOW_MS) {
            return false;
        }
        perSkill.put(skillName, now);
        return true;
    }
}
