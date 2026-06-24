package com.liuchangking.dreamtpa.listener;

import com.liuchangking.dreamtpa.DreamTPA;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class RequestListener implements Listener {

    private final DreamTPA plugin;

    public RequestListener(DreamTPA plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.getRequestByRequester(player.getUniqueId()) == null) {
            return;
        }
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            plugin.cancelMovingRequest(player);
        }
    }
}
