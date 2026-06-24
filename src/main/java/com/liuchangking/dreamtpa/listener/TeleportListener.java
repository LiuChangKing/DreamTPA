package com.liuchangking.dreamtpa.listener;

import com.liuchangking.dreamtpa.DreamTPA;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class TeleportListener implements Listener {

    private final DreamTPA plugin;

    public TeleportListener(DreamTPA plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!plugin.isCrossServerMode()) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.wasFastConsumed(player.getUniqueId())) {
            return;
        }
        Location loc = plugin.pollPendingTeleport(player.getUniqueId());
        if (loc != null) {
            Bukkit.getScheduler().runTask(plugin, () -> player.teleport(loc));
        }
    }
}
