package com.mosaicgem.plugin.service;

import com.mosaicgem.plugin.MosaicGemPlugin;
import com.mosaicgem.plugin.config.ConfigManager;
import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketData;
import com.mosaicgem.plugin.model.SocketedGem;
import com.mosaicgem.plugin.util.FoodHandler;
import com.mosaicgem.plugin.util.GfxEffectHandler;
import com.mosaicgem.plugin.util.GrimoireFXBridge;
import com.mosaicgem.plugin.util.ItemFactory;
import com.mosaicgem.plugin.util.TargetMatcher;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 装备巡检服务：每 N 刻（config.yml 的 {@code settings.gfx-aura.scan-ticks}，默认 40 = 2 秒）
 * 扫描玩家【已装备槽位】（头盔 / 胸甲 / 护腿 / 靴子 / 主手 / 副手）上的宝石，维护两类"外部效果"词条：
 *
 * <ul>
 *   <li>{@code gfx_effect} —— 通过 {@link GrimoireFXBridge} 让 GrimoireFX 施放 / 撤销状态：
 *       宝石进入装备槽就施放、离开就撤销；只撤本插件施放的那条（内存记账）；
 *       时长 / 等级取自宝石配置，改动会在下一轮巡检自动生效。</li>
 *   <li>{@code food} —— 饱食度维持：食物值低于上限时补 step（封顶上限），玩家自己吃满不干预。</li>
 * </ul>
 *
 * <p>槽位过滤：每颗宝石只在自己声明的可镶嵌类型对应的槽位被认可
 * （头盔宝石放在主手不算戴着），见 {@link TargetMatcher#slotsOf(java.util.Collection)}。
 *
 * <p>Folia 安全：每个玩家用 EntityScheduler 的 runAtFixedRate 跑在【该玩家自己的区域线程】上，
 * 不跨线程读写玩家背包（与 SkillTimerService 同款做法）。
 */
public final class GfxAuraService {

    /** 已施放记账：效果 id -> (时长秒 / 等级)。 */
    private record AppliedEffect(Long durationSeconds, int level) {
    }

    /** 本轮装备解析出的期望效果（spec + 来源宝石名，仅用于日志）。 */
    private record DesiredEffect(GfxEffectHandler.EffectSpec spec, String fromGem) {
    }

    private final MosaicGemPlugin plugin;
    private final ConfigManager configs;
    private final ItemFactory factory;
    private final GrimoireFXBridge bridge;

    private final Map<UUID, ScheduledTask> tasks = new HashMap<>();
    private final Map<UUID, Map<String, AppliedEffect>> applied = new HashMap<>();
    private final Set<UUID> maintainingFood = new HashSet<>();
    private boolean warnedError = false;

    public GfxAuraService(MosaicGemPlugin plugin, ConfigManager configs, ItemFactory factory,
                          GrimoireFXBridge bridge) {
        this.plugin = plugin;
        this.configs = configs;
        this.factory = factory;
        this.bridge = bridge;
    }

    /** 为玩家启动巡检任务（重复调用会先取消旧任务）。 */
    public void start(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        UUID id = player.getUniqueId();
        ScheduledTask existing = tasks.remove(id);
        if (existing != null) {
            existing.cancel();
        }
        long interval = configs.gfxAuraScanTicks();
        try {
            ScheduledTask task = player.getScheduler().runAtFixedRate(
                    plugin,
                    scheduled -> tick(player),
                    () -> tasks.remove(id),
                    interval,
                    interval);
            if (task != null) {
                tasks.put(id, task);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("启动装备巡检失败（玩家 " + player.getName() + "）: " + t);
        }
    }

    public void stop(UUID id) {
        if (id == null) {
            return;
        }
        ScheduledTask task = tasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    public void stopAll() {
        for (ScheduledTask task : new ArrayList<>(tasks.values())) {
            task.cancel();
        }
        tasks.clear();
        maintainedFoodClear();
    }

    /** 配置重载后按新的间隔重启全部任务。 */
    public void restartAll() {
        stopAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            start(player);
        }
    }

    /** 当前正在运行的巡检任务数（调试用）。 */
    public int activeTasks() {
        return tasks.size();
    }

    private void maintainedFoodClear() {
        maintainingFood.clear();
    }

    private void tick(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            Map<String, DesiredEffect> desired = new LinkedHashMap<>();
            List<FoodHandler.FoodSpec> foods = new ArrayList<>();
            for (WornItem worn : equipped(player)) {
                collect(worn, desired, foods);
            }
            reconcileEffects(player, desired);
            applyFood(player, foods);
        } catch (Throwable t) {
            if (!warnedError) {
                warnedError = true;
                plugin.getLogger().warning("装备巡检异常（后续同类错误不再刷屏）: " + t);
            }
        }
    }

    /** 解析一件装备上的宝石，收集 gfx_effect / food 词条（只认宝石声明类型对应的槽位）。 */
    private void collect(WornItem worn, Map<String, DesiredEffect> desired, List<FoodHandler.FoodSpec> foods) {
        ItemStack item = worn.item();
        if (item == null || item.getType().isAir()) {
            return;
        }
        SocketData data = factory.readSocketData(item);
        if (data == null || data.gems().isEmpty()) {
            return;
        }
        for (SocketedGem gem : data.gems()) {
            GemDefinition definition = configs.getGem(gem.id());
            if (definition == null) {
                continue;
            }
            Set<EquipmentSlot> slots = TargetMatcher.slotsOf(definition.getTargetType());
            if (!slots.isEmpty() && !slots.contains(worn.slot())) {
                // 宝石声明的是别的槽位（例如头盔宝石被拿在手上）→ 不算佩戴
                continue;
            }
            for (String raw : definition.attributeLinesOf(ItemFactory.BUFF_TYPE_GFX)) {
                GfxEffectHandler.EffectSpec spec = GfxEffectHandler.parse(factory.resolve(raw, gem.values()));
                if (spec != null && !desired.containsKey(spec.effectId())) {
                    desired.put(spec.effectId(), new DesiredEffect(spec, gem.id()));
                }
            }
            for (String raw : definition.attributeLinesOf(ItemFactory.BUFF_TYPE_FOOD)) {
                FoodHandler.FoodSpec spec = FoodHandler.parse(factory.resolve(raw, gem.values()));
                if (spec != null) {
                    foods.add(spec);
                }
            }
        }
    }

    /** ① 撤销已不存在的 ② 施放缺失的 ③ 参数变化的重新施放（只动本插件施放过的）。 */
    private void reconcileEffects(Player player, Map<String, DesiredEffect> desired) {
        UUID id = player.getUniqueId();
        Map<String, AppliedEffect> mine = applied.computeIfAbsent(id, k -> new LinkedHashMap<>());

        Iterator<Map.Entry<String, AppliedEffect>> it = mine.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, AppliedEffect> entry = it.next();
            if (desired.containsKey(entry.getKey())) {
                continue;
            }
            bridge.remove(player, entry.getKey());
            plugin.getLogger().info("宝石效果已撤销: " + entry.getKey() + "（玩家 " + player.getName() + "）");
            it.remove();
        }

        for (Map.Entry<String, DesiredEffect> entry : desired.entrySet()) {
            String effectId = entry.getKey();
            GfxEffectHandler.EffectSpec spec = entry.getValue().spec();
            AppliedEffect current = mine.get(effectId);
            if (current == null && bridge.hasStatus(id, effectId)) {
                // GFX 已经持有（例如本插件刚重载）→ 先记账，避免重复施放；参数变化下一轮会修正
                mine.put(effectId, new AppliedEffect(spec.durationSeconds(), spec.level()));
                continue;
            }
            boolean unchanged = current != null
                    && Objects.equals(current.durationSeconds(), spec.durationSeconds())
                    && current.level() == spec.level();
            if (unchanged) {
                continue;
            }
            if (bridge.apply(player, effectId, spec.level(), spec.durationSeconds())) {
                mine.put(effectId, new AppliedEffect(spec.durationSeconds(), spec.level()));
                if (current == null) {
                    plugin.getLogger().info("宝石效果已施放: " + effectId
                            + "（来自 " + entry.getValue().fromGem() + "，玩家 " + player.getName() + "）");
                }
            }
        }
    }

    /** 饱食度维持：食物低于上限就补，封顶上限；没有该词条时只清记账、不做任何压制。 */
    private void applyFood(Player player, List<FoodHandler.FoodSpec> foods) {
        UUID id = player.getUniqueId();
        if (foods.isEmpty()) {
            if (maintainingFood.remove(id)) {
                plugin.getLogger().info("饱食度维持已停止（玩家 " + player.getName() + "）");
            }
            return;
        }
        int cap = 0;
        int step = 1;
        for (FoodHandler.FoodSpec spec : foods) {
            cap = Math.max(cap, spec.cap());
            step = Math.max(step, spec.step());
        }
        int food = player.getFoodLevel();
        if (food < cap) {
            player.setFoodLevel(Math.min(cap, food + step));
            if (maintainingFood.add(id)) {
                plugin.getLogger().info("饱食度维持开始（玩家 " + player.getName() + "，上限 " + cap + "）");
            }
        }
    }

    /** 玩家身上"会随装备生效"的槽位：四个护甲槽 + 主手 + 副手（带槽位信息，供过滤用）。 */
    private static List<WornItem> equipped(Player player) {
        List<WornItem> items = new ArrayList<>(6);
        PlayerInventory inventory = player.getInventory();
        items.add(new WornItem(EquipmentSlot.HEAD, inventory.getHelmet()));
        items.add(new WornItem(EquipmentSlot.CHEST, inventory.getChestplate()));
        items.add(new WornItem(EquipmentSlot.LEGS, inventory.getLeggings()));
        items.add(new WornItem(EquipmentSlot.FEET, inventory.getBoots()));
        items.add(new WornItem(EquipmentSlot.HAND, inventory.getItemInMainHand()));
        items.add(new WornItem(EquipmentSlot.OFF_HAND, inventory.getItemInOffHand()));
        return items;
    }

    private record WornItem(EquipmentSlot slot, ItemStack item) {
    }
}
