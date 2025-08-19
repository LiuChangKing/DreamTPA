package com.liuchangking.dreamtpa;

import com.liuchangking.dreamtpa.command.TpaCommand;
import com.liuchangking.dreamtpa.command.TpAcceptCommand;
import com.liuchangking.dreamtpa.command.TpDenyCommand;
import com.liuchangking.dreamtpa.listener.RequestListener;
import com.liuchangking.dreamtpa.listener.TeleportListener;
import com.liuchangking.dreamtpa.request.TeleportRequest;
import com.liuchangking.dreamengine.api.DreamServerAPI;
import com.liuchangking.dreamengine.service.RedisManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/**
 * 跨服传送请求的主插件类
 */
public final class DreamTPA extends JavaPlugin {

    private static final String REDIS_CHANNEL = "dream:tpa";

    private final Map<UUID, TeleportRequest> requestsByRequester = new HashMap<>();
    private final Map<String, Deque<TeleportRequest>> requestsByTarget = new HashMap<>();
    private final Map<String, Location> pendingTeleports = new HashMap<>();
    private final Map<String, Set<String>> remoteRequestsByTarget = new HashMap<>();
    private int expireSeconds;
    private JedisPubSub pubSub;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.expireSeconds = getConfig().getInt("request-expire-seconds", 120);
        if (!RedisManager.isInitialized()) {
            getLogger().severe("Redis 未启用，DreamTPA 将停用");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        startRedisSubscriber();

        TpaCommand tpaCommand = new TpaCommand(this);
        getCommand("dreamtpa").setExecutor(tpaCommand);
        getCommand("dreamtpa").setTabCompleter(tpaCommand);
        TpAcceptCommand tpAcceptCommand = new TpAcceptCommand(this);
        getCommand("tpaccept").setExecutor(tpAcceptCommand);
        getCommand("tpaccept").setTabCompleter(tpAcceptCommand);
        getCommand("tpdeny").setExecutor(new TpDenyCommand(this));

        Bukkit.getPluginManager().registerEvents(new RequestListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TeleportListener(this), this);
    }

    @Override
    public void onDisable() {
        if (pubSub != null) {
            try {
                pubSub.unsubscribe();
            } catch (Exception ignored) {
            }
        }
    }

    public int getExpireSeconds() {
        return expireSeconds;
    }

    public void addRequest(TeleportRequest request) {
        requestsByRequester.put(request.getRequester().getUniqueId(), request);
        requestsByTarget.computeIfAbsent(request.getTargetName().toLowerCase(), k -> new ArrayDeque<>())
            .addLast(request);
    }

    public TeleportRequest getRequestByRequester(UUID uuid) {
        return requestsByRequester.get(uuid);
    }

    public TeleportRequest getRequestByTarget(String name) {
        Deque<TeleportRequest> deque = requestsByTarget.get(name.toLowerCase());
        return deque == null ? null : deque.peekLast();
    }

    public TeleportRequest getRequestByTarget(String targetName, String requesterName) {
        Deque<TeleportRequest> deque = requestsByTarget.get(targetName.toLowerCase());
        if (deque == null) {
            return null;
        }
        for (TeleportRequest req : deque) {
            if (req.getRequester().getName().equalsIgnoreCase(requesterName)) {
                return req;
            }
        }
        return null;
    }

    public boolean hasRequest(TeleportRequest request) {
        Deque<TeleportRequest> deque = requestsByTarget.get(request.getTargetName().toLowerCase());
        return deque != null && deque.contains(request);
    }

    public void removeRequest(TeleportRequest request) {
        requestsByRequester.remove(request.getRequester().getUniqueId());
        Deque<TeleportRequest> deque = requestsByTarget.get(request.getTargetName().toLowerCase());
        if (deque != null) {
            deque.remove(request);
            if (deque.isEmpty()) {
                requestsByTarget.remove(request.getTargetName().toLowerCase());
            }
        }
        notifyRequestRemove(request.getTargetName(), request.getRequester().getName());
    }
    public List<String> getRequesters(String targetName) {
        Deque<TeleportRequest> deque = requestsByTarget.get(targetName.toLowerCase());
        List<String> list = deque == null ? new ArrayList<>() :
            deque.stream().map(req -> req.getRequester().getName()).collect(Collectors.toCollection(ArrayList::new));
        Set<String> remote = remoteRequestsByTarget.get(targetName.toLowerCase());
        if (remote != null) {
            list.addAll(remote);
        }
        return list;
    }

    public void addPendingTeleport(String playerName, Location location) {
        pendingTeleports.put(playerName.toLowerCase(), location);
    }

    public Location pollPendingTeleport(String playerName) {
        return pendingTeleports.remove(playerName.toLowerCase());
    }

    public void addRemoteRequest(String target, String requester) {
        remoteRequestsByTarget.computeIfAbsent(target.toLowerCase(), k -> new LinkedHashSet<>()).add(requester);
    }

    public void removeRemoteRequest(String target, String requester) {
        Set<String> set = remoteRequestsByTarget.get(target.toLowerCase());
        if (set != null) {
            set.remove(requester);
            if (set.isEmpty()) {
                remoteRequestsByTarget.remove(target.toLowerCase());
            }
        }
    }

    public void notifyRequestAdd(String target, String requester) {
        publish("REQUEST|ADD|" + target + "|" + requester);
    }

    public void notifyRequestRemove(String target, String requester) {
        publish("REQUEST|REMOVE|" + target + "|" + requester);
    }

    public void sendMessageCrossServer(Player from, String target, String message) {
        publish("MESSAGE|" + target + "|" + message);
    }

    public void forwardCommand(Player sender, String subChannel, String... extra) {
        StringBuilder sb = new StringBuilder("COMMAND|")
            .append(subChannel).append("|").append(sender.getName());
        for (String e : extra) {
            sb.append("|").append(e);
        }
        publish(sb.toString());
    }

    private void startRedisSubscriber() {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                handleRedisMessage(message);
            }
        };
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Jedis jedis = RedisManager.getPool().getResource()) {
                jedis.subscribe(pubSub, REDIS_CHANNEL);
            }
        });
    }

    private void publish(String msg) {
        try (Jedis jedis = RedisManager.getPool().getResource()) {
            jedis.publish(REDIS_CHANNEL, msg);
        }
    }

    private void handleRedisMessage(String message) {
        String[] parts = message.split("\\|");
        if (parts.length < 1) {
            return;
        }
        if ("MESSAGE".equalsIgnoreCase(parts[0])) {
            if (parts.length < 3) {
                return;
            }
            String target = parts[1];
            String msg = parts[2];
            Player p = Bukkit.getPlayerExact(target);
            if (p != null) {
                p.sendMessage(msg);
            }
        } else if ("COMMAND".equalsIgnoreCase(parts[0])) {
            if (parts.length < 3) {
                return;
            }
            String sub = parts[1];
            String targetName = parts[2];
            String requesterName = parts.length > 3 ? parts[3] : null;
            if ("TpAccept".equalsIgnoreCase(sub)) {
                TeleportRequest request = requesterName == null
                    ? getRequestByTarget(targetName)
                    : getRequestByTarget(targetName, requesterName);
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
                TeleportRequest request = requesterName == null
                    ? getRequestByTarget(targetName)
                    : getRequestByTarget(targetName, requesterName);
                if (request == null) {
                    return;
                }
                removeRequest(request);
                request.getRequester().sendMessage(targetName + " 拒绝了你的传送请求");
                sendMessageCrossServer(request.getRequester(), targetName,
                    "你拒绝了 " + request.getRequester().getName() + " 的传送请求");
            }
        } else if ("REQUEST".equalsIgnoreCase(parts[0])) {
            if (parts.length < 4) {
                return;
            }
            String action = parts[1];
            String targetName = parts[2];
            String requester = parts[3];
            if ("ADD".equalsIgnoreCase(action)) {
                Player p = Bukkit.getPlayerExact(targetName);
                if (p != null) {
                    addRemoteRequest(targetName, requester);
                }
            } else if ("REMOVE".equalsIgnoreCase(action)) {
                removeRemoteRequest(targetName, requester);
            }
        }
    }
}
