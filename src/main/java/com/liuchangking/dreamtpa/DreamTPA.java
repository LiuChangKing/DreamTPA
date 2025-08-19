package com.liuchangking.dreamtpa;

import com.liuchangking.dreamtpa.command.TpaCommand;
import com.liuchangking.dreamtpa.command.TpAcceptCommand;
import com.liuchangking.dreamtpa.command.TpDenyCommand;
import com.liuchangking.dreamtpa.listener.RequestListener;
import com.liuchangking.dreamtpa.request.TeleportRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 跨服传送请求的主插件类
 */
public final class DreamTPA extends JavaPlugin {

    private final Map<UUID, TeleportRequest> requestsByRequester = new HashMap<>();
    private final Map<String, TeleportRequest> requestsByTarget = new HashMap<>();
    private int expireSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.expireSeconds = getConfig().getInt("request-expire-seconds", 120);

        TpaCommand tpaCommand = new TpaCommand(this);
        getCommand("dreamtpa").setExecutor(tpaCommand);
        getCommand("dreamtpa").setTabCompleter(tpaCommand);
        getCommand("tpaccept").setExecutor(new TpAcceptCommand(this));
        getCommand("tpdeny").setExecutor(new TpDenyCommand(this));

        Bukkit.getPluginManager().registerEvents(new RequestListener(this), this);
    }

    @Override
    public void onDisable() {
        // 插件关闭时的处理逻辑
    }

    public int getExpireSeconds() {
        return expireSeconds;
    }

    public void addRequest(TeleportRequest request) {
        requestsByRequester.put(request.getRequester().getUniqueId(), request);
        requestsByTarget.put(request.getTargetName().toLowerCase(), request);
    }

    public TeleportRequest getRequestByRequester(UUID uuid) {
        return requestsByRequester.get(uuid);
    }

    public TeleportRequest getRequestByTarget(String name) {
        return requestsByTarget.get(name.toLowerCase());
    }

    public void removeRequest(TeleportRequest request) {
        requestsByRequester.remove(request.getRequester().getUniqueId());
        requestsByTarget.remove(request.getTargetName().toLowerCase());
    }
}
