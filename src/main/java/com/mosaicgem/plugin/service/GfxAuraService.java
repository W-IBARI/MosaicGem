package com.mosaicgem.plugin.service;

import com.mosaicgem.plugin.MosaicGemPlugin;
import com.mosaicgem.plugin.config.ConfigManager;
import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketData;
import com.mosaicgem.plugin.model.SocketedGem;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 宝石常驻状态的"何时挂 / 何时撤"——事件驱动为主、低频兜底为辅（2026-09-28 重构）。
 *
 * <p><b>职责边界</b>（方案：桌面《MosaicGem-GFX状态巡检重构建议》）：
 * <ul>
 *   <li>本服务只回答"这个玩家现在应当挂着哪些 {@code gfx_effect}"——装备一变就做一次
 *       幂等的 {@link #sync(Player)}：缺的施放、多的撤销（只动自己记账里的 id）。</li>
 *   <li>状态本体怎么活（时长、续期、死亡/重生/重登处理、撤销时清原版药水与属性修饰符）
 *       全部由 GrimoireFX 的 StatusManager 负责。</li>
 *   <li>时长恒为<b>无限</b>（GFX 的哨兵值 {@link #INFINITE}），撤销一律走显式 remove。</li>
 * </ul>
 *
 * <p><b>触发点</b>：见 {@link com.mosaicgem.plugin.listener.GfxAuraListener} 的事件表
 * （换盔 / 换手 / 工具碎 / 丢弃 / 背包点击 / 上线 / 重生 / 下线）；
 * 另外保留一个 {@code settings.gfx-aura.scan-ticks} 的低频兜底扫描，覆盖事件清单之外的外部改动。
 *
 * <p>槽位过滤：每颗宝石只在自己声明的可镶嵌类型对应的槽位被认可
 * （头盔宝石放在主手不算戴着），见 {@link TargetMatcher#slotsOf(java.util.Collection)}。
 *
 * <p>Folia 安全：兜底任务与延迟同步都跑在【该玩家自己的区域线程】上，不跨线程读写背包。
 */
public final class GfxAuraService {

    /**
     * GFX 的"无限时长"哨兵值：{@code ActiveEffect.isInfinite()} 判的就是
     * {@code expiryMs == Long.MAX_VALUE}（字节码核实）。恒用它，就不再有时长单位歧义。
     */
    public static final long INFINITE = Long.MAX_VALUE;

    /** 已施放记账：效果 id -> 等级（时长恒为 {@link #INFINITE}，不再记录）。 */
    private record AppliedEffect(int level) {
    }

    /** 本轮装备解析出的期望效果（spec + 来源宝石名，仅用于日志）。 */
    private record DesiredEffect(GfxEffectHandler.EffectSpec spec, String fromGem) {
    }

    private final MosaicGemPlugin plugin;
    private final ConfigManager configs;
    private final ItemFactory factory;
    private final GrimoireFXBridge bridge;

    /** 兜底扫描任务（低频；事件漏网时自愈）。 */
    private final Map<UUID, ScheduledTask> tasks = new HashMap<>();
    private final Map<UUID, Map<String, AppliedEffect>> applied = new HashMap<>();
    private boolean warnedError = false;

    public GfxAuraService(MosaicGemPlugin plugin, ConfigManager configs, ItemFactory factory,
                          GrimoireFXBridge bridge) {
        this.plugin = plugin;
        this.configs = configs;
        this.factory = factory;
        this.bridge = bridge;
    }

    /** 上线 / 重载时：立刻同步一次，再挂上低频兜底扫描（重复调用先取消旧任务）。 */
    public void start(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        sync(player);
        UUID id = player.getUniqueId();
        ScheduledTask existing = tasks.remove(id);
        if (existing != null) {
            existing.cancel();
        }
        long interval = configs.gfxAuraScanTicks();
        try {
            ScheduledTask task = player.getScheduler().runAtFixedRate(
                    plugin,
                    scheduled -> sync(player),
                    () -> tasks.remove(id),
                    interval,
                    interval);
            if (task != null) {
                tasks.put(id, task);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("启动宝石状态兜底扫描失败（玩家 " + player.getName() + "）: " + t);
        }
    }

    /**
     * 延迟若干刻后同步。上线时必须用它（延迟 1 刻）：要排在 GFX 自己的 onJoin 恢复之后，
     * 否则可能把 GFX 刚恢复的状态按本插件的旧记账撤掉。
     */
    public void syncLater(Player player, long delayTicks) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            player.getScheduler().runDelayed(plugin, task -> sync(player), null, Math.max(0L, delayTicks));
        } catch (Throwable t) {
            sync(player);
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
        applied.remove(id);
    }

    public void stopAll() {
        for (ScheduledTask task : new ArrayList<>(tasks.values())) {
            task.cancel();
        }
        tasks.clear();
        applied.clear();
    }

    /** 配置重载后按新的间隔重启全部兜底任务，并对在线玩家各同步一次。 */
    public void restartAll() {
        stopAll();
        for (Player player : Bukkit.getOnlinePlayers()) {
            start(player);
        }
    }

    /** 当前正在运行的兜底任务数（调试用）。 */
    public int activeTasks() {
        return tasks.size();
    }

    /**
     * 幂等同步：算出"这个玩家当前应当挂的 gfx_effect 集合"，与记账 diff 之后补挂 / 撤销。
     * 事件触发与兜底扫描都走这里，重复调用无副作用。
     */
    public void sync(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        try {
            Map<String, DesiredEffect> desired = new LinkedHashMap<>();
            for (WornItem worn : equipped(player)) {
                collect(worn, desired);
            }
            reconcileEffects(player, desired);
        } catch (Throwable t) {
            if (!warnedError) {
                warnedError = true;
                plugin.getLogger().warning("宝石状态同步异常（后续同类错误不再刷屏）: " + t);
            }
        }
    }

    /** 解析一件装备上的宝石，收集 gfx_effect 词条（只认宝石声明类型对应的槽位）。 */
    private void collect(WornItem worn, Map<String, DesiredEffect> desired) {
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
        }
    }

    /**
     * ① 撤销已不存在的 ② 施放缺失的 ③ 等级变化的重新施放（只动本插件施放过的）。
     * 施放一律用无限时长 {@link #INFINITE}，生命周期交给 GFX。
     */
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
                // GFX 已经持有（例如本插件刚重载）→ 先记账，避免重复施放
                mine.put(effectId, new AppliedEffect(spec.level()));
                continue;
            }
            if (current != null && current.level() == spec.level()) {
                continue;
            }
            if (bridge.apply(player, effectId, spec.level(), INFINITE)) {
                mine.put(effectId, new AppliedEffect(spec.level()));
                if (current == null) {
                    plugin.getLogger().info("宝石效果已施放: " + effectId
                            + "（来自 " + entry.getValue().fromGem() + "，玩家 " + player.getName() + "）");
                } else {
                    plugin.getLogger().info("宝石效果等级已调整: " + effectId
                            + " -> " + spec.level() + "（玩家 " + player.getName() + "）");
                }
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