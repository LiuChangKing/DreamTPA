package com.liuchangking.dreamtpa;

import com.liuchangking.dreamtpa.command.TpaCommand;
import com.liuchangking.dreamtpa.command.TpAcceptCommand;
import com.liuchangking.dreamtpa.command.TpDenyCommand;
import com.liuchangking.dreamtpa.listener.RequestListener;
import com.liuchangking.dreamtpa.request.TeleportRequest;
import com.liuchangking.dreamengine.api.DreamServerAPI;
import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * 跨服传送请求的主插件类
 */
public final class DreamTPA extends JavaPlugin implements PluginMessageListener {

    private final Map<UUID, TeleportRequest> requestsByRequester = new HashMap<>();
    private final Map<String, TeleportRequest> requestsByTarget = new HashMap<>();
    private int expireSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.expireSeconds = getConfig().getInt("request-expire-seconds", 120);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        Bukkit.getMessenger().registerIncomingPluginChannel(this, "dream:tpa", this);

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

    public void sendMessageCrossServer(Player from, String target, String message) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Message");
        out.writeUTF(target);
        out.writeUTF(message);
        from.sendPluginMessage(this, "BungeeCord", out.toByteArray());
    }

    public void forwardCommand(Player sender, String subChannel) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Forward");
        out.writeUTF("ALL");
        out.writeUTF("dream:tpa");
        ByteArrayDataOutput msg = ByteStreams.newDataOutput();
        msg.writeUTF(subChannel);
        msg.writeUTF(sender.getName());
        out.writeShort(msg.toByteArray().length);
        out.write(msg.toByteArray());
        sender.sendPluginMessage(this, "BungeeCord", out.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("dream:tpa")) {
            return;
        }
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        String sub = in.readUTF();
        String targetName = in.readUTF();
        if ("TpAccept".equalsIgnoreCase(sub)) {
            TeleportRequest request = getRequestByTarget(targetName);
            if (request == null) {
                return;
            }
            removeRequest(request);
            String targetServerId = DreamServerAPI.getPlayerServerId(targetName);
            DreamServerAPI.sendPlayerToServer(request.getRequester(), targetServerId);
            request.getRequester().sendMessage("正在传送到 " + targetName);
            sendMessageCrossServer(request.getRequester(), targetName,
                "你接受了 " + request.getRequester().getName() + " 的传送请求");
        } else if ("TpDeny".equalsIgnoreCase(sub)) {
            TeleportRequest request = getRequestByTarget(targetName);
            if (request == null) {
                return;
            }
            removeRequest(request);
            request.getRequester().sendMessage(targetName + " 拒绝了你的传送请求");
            sendMessageCrossServer(request.getRequester(), targetName,
                "你拒绝了 " + request.getRequester().getName() + " 的传送请求");
        }
    }
}
