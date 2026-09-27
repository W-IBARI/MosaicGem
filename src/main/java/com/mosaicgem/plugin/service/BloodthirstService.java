package com.mosaicgem.plugin.service;

import com.mosaicgem.plugin.MosaicGemPlugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渴血宝石的「吸血窗口 + 溢出吸收池」。
 *
 * <p><b>为什么放在插件里而不是 MythicMobs：</b>吸血基数必须是"这次攻击实际造成的伤害"。
 * CE 的属性伤害是在伤害事件里分两步定稿的（damage_rules 在 HIGH 改写伤害、damage_final.js 在
 * HIGHEST 再乘 damage_change），而 MM 的 aura{onAttack} 组件挂在 LOW，读到的是 CE 之前的值。
 * 本服务的入口由 BloodthirstListener 在 MONITOR 调用，拿到的就是 CE 结算完的最终伤害。
 *
 * <p><b>窗口：</b>一次施放开一段时间（默认 10 秒，由 mechanic 传入），窗口内该玩家造成的伤害
 * 都吸血；窗口按最后写入时刻计时，重复施放等于刷新。
 *
 * <p><b>吸收池：</b>回复量超过最大生命的部分转为伤害吸收，池的到期时刻 = 最后一笔 + 秒数
 * （新的一笔刷新整池）；到点把池里【尚未被消耗】的吸收一次性收走。吸收被伤害吃掉多少，由
 * 监听器按"事件前后 AbsorptionAmount 差值"告知 onAbsorptionConsumed。
 *
 * <p><b>Folia：</b>同一份映射会被不同玩家的区域线程读写，一律用 ConcurrentHashMap；
 * 到期任务挂在玩家自己的 EntityScheduler 上，只碰该玩家自己的数据。
 */
public final class BloodthirstService {

    /** 吸血窗口：pct 是百分比数字（10 = 10%），cap 是单次回复上限，absorbSeconds 是溢出吸收的持续秒数。 */
    private static final class Window {
        private final double pct;
        private final double cap;
        private final int absorbSeconds;
        private long expireAt;

        private Window(double pct, double cap, int absorbSeconds, long expireAt) {
            this.pct = pct;
            this.cap = cap;
            this.absorbSeconds = absorbSeconds;
            this.expireAt = expireAt;
        }
    }

    /** 吸收池：amount 是"还欠玩家的吸收"（被伤害吃掉的部分会同步扣减），expireAt 是收走时刻。 */
    private static final class Pool {
        private double amount;
        private long expireAt;
    }

    /** 一笔吸血结算的结果（供监听器决定要不要出反馈特效）。 */
    public record LeechResult(double healed, double overflow) {
        public boolean happened() {
            return healed > 0D || overflow > 0D;
        }
    }

    private final MosaicGemPlugin plugin;
    private final Map<UUID, Window> windows = new ConcurrentHashMap<>();
    private final Map<UUID, Pool> pools = new ConcurrentHashMap<>();
    /** 池子到期巡检任务（玩家区域线程上跑；池子清空即取消）—— runDelayed 在本服不生效，改用与 GfxAuraService 同款的周期任务 */
    private final Map<UUID, ScheduledTask> poolTasks = new ConcurrentHashMap<>();
    /** 吸收上限修饰器的稳定 key（重入池时按它先删后加，保证只留一条） */
    private final NamespacedKey ABSORB_MODIFIER_KEY;

    public BloodthirstService(MosaicGemPlugin plugin) {
        this.plugin = plugin;
        this.ABSORB_MODIFIER_KEY = new NamespacedKey(plugin, "bloodthirst_absorb");
    }

    /** 开窗口（MM mechanic 调用）。pctPercent 是百分比数字：10~25 表示 10%~25%。 */
    public void open(Player player, double pctPercent, int durationSeconds, double capPerHit, int absorbSeconds) {
        if (player == null || !player.isOnline()) {
            return;
        }
        long now = System.currentTimeMillis();
        windows.put(player.getUniqueId(), new Window(
                Math.max(0D, pctPercent),
                Math.max(0D, capPerHit),
                Math.max(1, absorbSeconds),
                now + Math.max(1, durationSeconds) * 1000L));
    }

    /** 玩家当前是否在窗口里（过期即顺手清掉）。 */
    public boolean isActive(UUID id) {
        Window window = windows.get(id);
        if (window == null) {
            return false;
        }
        if (System.currentTimeMillis() >= window.expireAt) {
            windows.remove(id, window);
            return false;
        }
        return true;
    }

    public boolean hasPool(UUID id) {
        return pools.containsKey(id);
    }

    /** 玩家死亡/退出时丢弃记账，并撤掉本插件加的吸收上限（吸收值本身交给原版处理）。 */
    public void clear(UUID id) {
        if (id == null) {
            return;
        }
        windows.remove(id);
        pools.remove(id);
        stopPoolTask(id);
        Player player = plugin.getServer().getPlayer(id);
        if (player != null && player.isOnline()) {
            removeCap(player);
        }
    }

    public void clearAll() {
        windows.clear();
        pools.clear();
        for (ScheduledTask task : new ArrayList<>(poolTasks.values())) {
            task.cancel();
        }
        poolTasks.clear();
    }

    /**
     * 一次命中结算：按最终伤害回血，回复超出最大生命的部分转伤害吸收。
     * 必须在【攻击者自己的区域线程】上调用（监听器已用 EntityScheduler 保证）。
     *
     * @param damage 事件最终伤害（CE 结算后）
     */
    public LeechResult onAttackDamage(Player attacker, double damage) {
        UUID id = attacker.getUniqueId();
        Window window = windows.get(id);
        if (window == null || damage <= 0D) {
            return new LeechResult(0D, 0D);
        }
        if (System.currentTimeMillis() >= window.expireAt) {
            windows.remove(id, window);
            return new LeechResult(0D, 0D);
        }

        double heal = Math.min(damage * window.pct / 100D, window.cap);
        if (heal <= 0D) {
            return new LeechResult(0D, 0D);
        }

        double maxHealth = attacker.getMaxHealth();
        double health = attacker.getHealth();
        double missing = Math.max(0D, maxHealth - health);
        double healed = Math.min(heal, missing);
        double overflow = heal - healed;

        if (healed > 0D) {
            attacker.setHealth(Math.min(maxHealth, health + healed));
        }
        if (overflow > 0D) {
            grantAbsorption(attacker, overflow, window.absorbSeconds);
        }
        return new LeechResult(healed, overflow);
    }

    /** 吸收被伤害吃掉时从池里扣掉，避免到期"收走"时误伤其它来源的吸收（如硕果/金苹果）。 */
    public void onAbsorptionConsumed(Player victim, double consumed) {
        if (consumed <= 0D) {
            return;
        }
        Pool pool = pools.get(victim.getUniqueId());
        if (pool == null) {
            return;
        }
        synchronized (pool) {
            pool.amount = Math.max(0D, pool.amount - consumed);
        }
    }

    private void grantAbsorption(Player player, double amount, int absorbSeconds) {
        Pool pool = pools.computeIfAbsent(player.getUniqueId(), key -> new Pool());
        synchronized (pool) {
            pool.amount += amount;
            pool.expireAt = System.currentTimeMillis() + absorbSeconds * 1000L;
        }
        if (!applyPool(player, pool.amount)) {
            return;
        }
        ensurePoolTask(player);
    }

    /** 第一个池子出现时挂周期巡检（1 秒一次），池子清空就取消 —— 到期回收只靠它。 */
    private void ensurePoolTask(Player player) {
        UUID id = player.getUniqueId();
        if (poolTasks.containsKey(id)) {
            return;
        }
        try {
            ScheduledTask task = player.getScheduler().runAtFixedRate(
                    plugin,
                    scheduled -> tickPool(player),
                    () -> poolTasks.remove(id),
                    20L,
                    20L);
            if (task != null) {
                poolTasks.put(id, task);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("启动伤害吸收到期巡检失败（玩家 " + player.getName() + "）: " + t);
        }
    }

    private void stopPoolTask(UUID id) {
        if (id == null) {
            return;
        }
        ScheduledTask task = poolTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
    }

    /** 周期巡检（玩家区域线程）：池子到期就撤上限 + 重新夹取吸收值。 */
    private void tickPool(Player player) {
        if (player == null || !player.isOnline()) {
            stopPoolTask(player == null ? null : player.getUniqueId());
            return;
        }
        UUID id = player.getUniqueId();
        Pool pool = pools.get(id);
        if (pool == null) {
            stopPoolTask(id);
            return;
        }
        synchronized (pool) {
            if (System.currentTimeMillis() < pool.expireAt) {
                return;
            }
            pools.remove(id, pool);
        }
        // 撤掉上限修饰器后再写一次吸收值：让服务器按剩下的上限（其它来源，如硕果药水）重新夹取
        removeCap(player);
        try {
            player.setAbsorptionAmount(player.getAbsorptionAmount());
        } catch (Throwable t) {
            plugin.getLogger().warning("回收伤害吸收失败（玩家 " + player.getName() + "）: " + t);
        }
        stopPoolTask(id);
    }

    /**
     * 上线/重载时清掉"没人记账"的残留上限（服务端属性修饰器会随玩家存档保留，
     * 例如重启时池子的记账丢了、修饰器还在）。
     */
    public void resetStale(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (pools.containsKey(player.getUniqueId())) {
            return;
        }
        removeCap(player);
        try {
            player.setAbsorptionAmount(player.getAbsorptionAmount());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把"本池总量"写进玩家的伤害吸收。
     *
     * <p>⚠ 这个版本（26.2）的 `LivingEntity#setAbsorptionAmount` 会被
     * `Mth.clamp(0, getMaxAbsorption())` 夹取，而 `MAX_ABSORPTION` 属性**玩家默认是 0** ——
     * 也就是说裸写吸收值一律变成 0（MM 的 shield / heal{overheal} 同样无效）。
     * 原版吸收药水能生效，是因为它会先抬这个上限，所以这里照做：上限 = 本池总量，再写吸收值。
     *
     * @return 是否成功写入
     */
    private boolean applyPool(Player player, double want) {
        try {
            AttributeInstance instance = player.getAttribute(Attribute.MAX_ABSORPTION);
            if (instance != null) {
                removeModifier(instance);
                instance.addModifier(new AttributeModifier(ABSORB_MODIFIER_KEY,
                        want, AttributeModifier.Operation.ADD_NUMBER));
            }
            double current = player.getAbsorptionAmount();
            player.setAbsorptionAmount(Math.max(current, want));
            double applied = player.getAbsorptionAmount();
            if (applied + 0.01D >= Math.max(current, want)) {
                return true;
            }
            // 兜底：裸写入仍被夹掉（本版本以外的情况）→ 退回原版吸收药水，按池子换算成等级（每级 4 点）
            int level = Math.max(0, (int) Math.ceil(want / 4D) - 1);
            player.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,
                    poolSecondsTicks(player), level, true, false, false));
            plugin.getLogger().info("伤害吸收裸写入未生效，已退回原版吸收药水（等级 " + level
                    + "，玩家 " + player.getName() + "）");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("写入伤害吸收失败（玩家 " + player.getName() + "）: " + t);
            return false;
        }
    }

    /** 兜底药水的时长：取当前池的剩余秒数（至少 1 秒）。 */
    private int poolSecondsTicks(Player player) {
        Pool pool = pools.get(player.getUniqueId());
        if (pool == null) {
            return 20;
        }
        long remainMillis;
        synchronized (pool) {
            remainMillis = pool.expireAt - System.currentTimeMillis();
        }
        return (int) Math.max(20L, remainMillis / 50L);
    }

    /** 撤掉本插件加的吸收上限修饰器（其它来源的效果/修饰器不受影响）。 */
    private void removeCap(Player player) {
        try {
            AttributeInstance instance = player.getAttribute(Attribute.MAX_ABSORPTION);
            if (instance != null) {
                removeModifier(instance);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("回收伤害吸收上限失败（玩家 " + player.getName() + "）: " + t);
        }
    }

    private void removeModifier(AttributeInstance instance) {
        for (AttributeModifier modifier : instance.getModifiers()) {
            if (ABSORB_MODIFIER_KEY.equals(modifier.getKey())) {
                instance.removeModifier(modifier);
                break;
            }
        }
    }
}