package com.liuchangking.dreamtpa.listener;

import com.liuchangking.dreamtpa.DreamTPA;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

public class PaperTeleportListener implements Listener {

    private final DreamTPA plugin;

    private PaperTeleportListener(DreamTPA plugin) {
        this.plugin = plugin;
    }

    @SuppressWarnings("unchecked")
    public static boolean register(DreamTPA plugin) {
        try {
            Class<?> rawEventClass = Class.forName("io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent");
            Class<? extends Event> eventClass = (Class<? extends Event>) rawEventClass.asSubclass(Event.class);
            PaperTeleportListener listener = new PaperTeleportListener(plugin);
            EventExecutor executor = (ignored, event) -> listener.handle(event);
            plugin.getServer().getPluginManager().registerEvent(
                    eventClass, listener, EventPriority.NORMAL, executor, plugin, true);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        } catch (Throwable throwable) {
            plugin.getLogger().warning("Failed to register Paper DreamTPA teleport optimization: "
                    + throwable.getMessage());
            return false;
        }
    }

    private void handle(Event event) {
        try {
            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
            Location loc = plugin.peekPendingTeleport(player.getUniqueId());
            if (loc == null || loc.getWorld() == null) {
                return;
            }
            event.getClass().getMethod("setSpawnLocation", Location.class).invoke(event, loc);
            plugin.removePendingTeleport(player.getUniqueId());
            plugin.markFastConsumed(player.getUniqueId());
        } catch (Throwable ignored) {
            // Keep the pending teleport so PlayerJoinEvent can use the normal fallback.
        }
    }
}
