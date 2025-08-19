package com.liuchangking.dreamtpa.listener;

import com.liuchangking.dreamtpa.DreamTPA;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * 处理跨服传送后的定位
 */
public class TeleportListener implements Listener {

    private final DreamTPA plugin;

    public TeleportListener(DreamTPA plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location loc = plugin.pollPendingTeleport(player.getName());
        if (loc != null) {
            Bukkit.getScheduler().runTask(plugin, () -> player.teleport(loc));
        }
    }
}
