package com.mosaicgem.plugin.listener;

import com.mosaicgem.plugin.MosaicGemPlugin;
import com.mosaicgem.plugin.service.BloodthirstService;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 渴血宝石：攻击结算后吸血（由 {@link BloodthirstService} 记账）。
 *
 * <p><b>为什么是 MONITOR + DamageSource.causingEntity：</b>
 * <ul>
 *   <li>MONITOR：CE 的属性伤害由 damage_rules（HIGH）改写、damage_final.js（HIGHEST）再乘
 *       damage_change，只有 MONITOR 读到的 getFinalDamage() 才是最终伤害；</li>
 *   <li>causingEntity：CE 自己也是用伤害源里的 causing entity 认攻击者的，所以近战、弓、矛、
 *       以及【法杖用 MM 技能造成的 magic 伤害】都在同一条路上，不需要为法杖单独开通道。</li>
 * </ul>
 *
 * <p>只有"确实造成了伤害"（getFinalDamage() &gt; 0）、且攻击者是本插件的窗口玩家时才结算；
 * 环境伤害（没有 causing entity）与自己打自己天然被排除。
 */
public final class BloodthirstListener implements Listener {

    /** 视为"攻击"的伤害类型；之外的类型若不是玩家造成的会直接忽略，是玩家造成的则记一次日志便于排查。 */
    private static final Set<EntityDamageEvent.DamageCause> ATTACK_CAUSES = Set.of(
            EntityDamageEvent.DamageCause.ENTITY_ATTACK,
            EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK,
            EntityDamageEvent.DamageCause.PROJECTILE,
            EntityDamageEvent.DamageCause.MAGIC);

    private final MosaicGemPlugin plugin;
    private final BloodthirstService service;
    /** 受击前的吸收量快照（同一受害者的 LOWEST 与 MONITOR 在同一区域线程上执行）。 */
    private final Map<UUID, Double> absorptionBefore = new ConcurrentHashMap<>();
    private final Set<String> reportedCauses = new HashSet<>();

    public BloodthirstListener(MosaicGemPlugin plugin, BloodthirstService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** 受击前快照：只有池里有吸收的玩家才需要，用来算"这一下吃掉了多少吸收"。 */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageSnapshot(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        if (!service.hasPool(victim.getUniqueId())) {
            return;
        }
        absorptionBefore.put(victim.getUniqueId(), victim.getAbsorptionAmount());
    }

    /** 攻击结算：窗口玩家造成伤害 → 按最终伤害吸血；窗口玩家被打 → 扣减池里已消耗的吸收。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        Entity victim = event.getEntity();
        if (victim instanceof Player hurt) {
            Double before = absorptionBefore.remove(hurt.getUniqueId());
            if (before != null) {
                double consumed = before - hurt.getAbsorptionAmount();
                if (consumed > 0D) {
                    service.onAbsorptionConsumed(hurt, consumed);
                }
            }
        }

        Player attacker = resolveAttacker(event);
        if (attacker == null || attacker.equals(victim)) {
            return;
        }
        if (!service.isActive(attacker.getUniqueId())) {
            return;
        }
        double damage = event.getFinalDamage();
        if (damage <= 0D) {
            return;
        }
        if (!ATTACK_CAUSES.contains(event.getCause())) {
            logOnce("cause:" + event.getCause(),
                    "bloodthirst: 玩家造成的伤害类型 " + event.getCause() + " 不在吸血白名单内，已忽略（如应吸血请补进白名单）");
            return;
        }
        applyLeech(attacker, damage);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        absorptionBefore.remove(event.getEntity().getUniqueId());
        service.clear(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        absorptionBefore.remove(event.getPlayer().getUniqueId());
        service.clear(event.getPlayer().getUniqueId());
    }

    /** 上线时清掉可能残留的吸收上限修饰器（属性修饰器随玩家存档保留，重启后记账丢了） */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        service.resetStale(event.getPlayer());
    }

    private void applyLeech(Player attacker, double damage) {
        // 治疗/吸收要写玩家数据，统一放到攻击者自己的区域线程上（Folia）
        try {
            attacker.getScheduler().run(plugin, task -> runLeech(attacker, damage), null);
        } catch (Throwable t) {
            runLeech(attacker, damage);
        }
    }

    private void runLeech(Player attacker, double damage) {
        try {
            BloodthirstService.LeechResult result = service.onAttackDamage(attacker, damage);
            if (!result.happened()) {
                return;
            }
            attacker.getWorld().spawnParticle(Particle.HEART, attacker.getLocation().add(0D, 2.1D, 0D),
                    3, 0.25D, 0.25D, 0.25D, 0D);
            attacker.playSound(attacker.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.5F, 1.5F);
        } catch (Throwable t) {
            logOnce("leech-error", "bloodthirst: 结算吸血失败: " + t);
        }
    }

    /** 攻击者：优先伤害源里的 causing entity（与 CE 同口径），取不到再退回 damager / 弹射物发射者。 */
    private Player resolveAttacker(EntityDamageEvent event) {
        try {
            Entity causing = event.getDamageSource().getCausingEntity();
            if (causing instanceof Player player) {
                return player;
            }
        } catch (Throwable ignored) {
        }
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Player player) {
                return player;
            }
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private void logOnce(String key, String message) {
        if (reportedCauses.add(key)) {
            plugin.getLogger().warning(message);
        }
    }
}