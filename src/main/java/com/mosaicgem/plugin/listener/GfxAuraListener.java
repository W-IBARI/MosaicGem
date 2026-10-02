package com.mosaicgem.plugin.listener;

import com.mosaicgem.plugin.service.GfxAuraService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

/**
 * 宝石常驻状态的触发点：**装备一变就让 {@link GfxAuraService} 同步一次**。
 *
 * <p>事件表（对应重构方案 §4-B2）：右键（含右键穿装备）/ 快捷栏换手 / F 换手 / 工具耐久变化 /
 * 工具碎 / 丢弃物品 / 背包点击与拖拽（含镶嵌、拆卸路径）/ 上线 / 重生 / 下线。
 * 另外 GfxAuraService 还挂着一个低频兜底扫描，覆盖事件清单之外的外部改动。
 *
 * <p>说明：方案里点名的 Paper 事件 {@code PlayerArmorChangeEvent} 不在本服的构建 API 里，
 * 按方案的"旧版回退"用「右键 + 背包点击」覆盖换盔路径。
 */
public final class GfxAuraListener implements Listener {

    private final GfxAuraService service;

    public GfxAuraListener(GfxAuraService service) {
        this.service = service;
    }

    /** 右键：右键穿戴装备（盔甲/鞘翅/盾牌）以及手持物品变化都从这里过。 */
    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            service.syncLater(event.getPlayer(), 1L);
        }
    }

    /** 快捷栏切换主手物品。 */
    @EventHandler(ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        service.syncLater(event.getPlayer(), 1L);
    }

    /** F 键主副手互换。 */
    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        service.syncLater(event.getPlayer(), 1L);
    }

    /** 工具耐久变化（含即将挖断）。 */
    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        service.syncLater(event.getPlayer(), 1L);
    }

    /** 工具挖断（宝石随之失效）。 */
    @EventHandler(ignoreCancelled = true)
    public void onItemBreak(PlayerItemBreakEvent event) {
        service.syncLater(event.getPlayer(), 1L);
    }

    /** 丢弃物品（可能丢的是正在生效的装备）。 */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        service.syncLater(event.getPlayer(), 1L);
    }

    /** 背包点击：装/脱装备、镶嵌与拆卸等操作都会落在这里。 */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            service.syncLater(player, 1L);
        }
    }

    /** 背包拖拽投放（镶嵌路径之一）。 */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            service.syncLater(player, 1L);
        }
    }

    /**
     * 上线：start 内含"立即同步 + 挂兜底扫描"，再延迟 1 刻补一次
     * —— 必须排在 GFX 自己的 onJoin 恢复之后，避免把刚恢复的状态撤掉。
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.start(event.getPlayer());
        service.syncLater(event.getPlayer(), 1L);
    }

    /** 重生：死亡时掉落/保留的头盔等要在这里纠正。 */
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        service.syncLater(event.getPlayer(), 1L);
    }

    /** 下线：停兜底任务、清记账（状态随会话消亡）。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.stop(event.getPlayer().getUniqueId());
    }
}