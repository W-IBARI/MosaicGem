package com.mosaicgem.plugin.listener;

import com.mosaicgem.plugin.service.GfxAuraService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 装备巡检的上下线挂钩：上线启动巡检任务、下线停止。
 */
public final class GfxAuraListener implements Listener {

    private final GfxAuraService service;

    public GfxAuraListener(GfxAuraService service) {
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.start(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.stop(event.getPlayer().getUniqueId());
    }
}
