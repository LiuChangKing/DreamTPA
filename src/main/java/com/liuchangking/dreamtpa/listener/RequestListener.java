package com.liuchangking.dreamtpa.listener;

import com.liuchangking.dreamtpa.DreamTPA;
import com.liuchangking.dreamtpa.request.TeleportRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

/**
 * 当请求方移动时取消传送请求
 */
public class RequestListener implements Listener {

    private final DreamTPA plugin;

    public RequestListener(DreamTPA plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        TeleportRequest request = plugin.getRequestByRequester(player.getUniqueId());
        if (request == null) {
            return;
        }
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() ||
            event.getFrom().getBlockY() != event.getTo().getBlockY() ||
            event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            plugin.removeRequest(request);
            player.sendMessage("你移动了, 传送请求已取消");
            Player target = Bukkit.getPlayerExact(request.getTargetName());
            if (target != null) {
                target.sendMessage(player.getName() + " 取消了传送请求");
            } else {
                plugin.sendMessageCrossServer(player, request.getTargetName(),
                    player.getName() + " 取消了传送请求");
            }
        }
    }
}
