package com.liuchangking.dreamtpa;

import com.liuchangking.dreamengine.api.DreamServerAPI;
import com.liuchangking.dreamengine.api.MysqlConfigAPI;
import com.liuchangking.dreamengine.mysqlconfig.MysqlConfigStore;
import com.liuchangking.dreamengine.service.RedisManager;
import com.liuchangking.dreamengine.utils.MessageUtil;
import com.liuchangking.dreamtpa.command.TpAcceptCommand;
import com.liuchangking.dreamtpa.command.TpDenyCommand;
import com.liuchangking.dreamtpa.command.TpaCommand;
import com.liuchangking.dreamtpa.gui.TpaGui;
import com.liuchangking.dreamtpa.listener.PaperTeleportListener;
import com.liuchangking.dreamtpa.listener.RequestListener;
import com.liuchangking.dreamtpa.listener.TeleportListener;
import com.liuchangking.dreamtpa.request.RemoteTeleportRequest;
import com.liuchangking.dreamtpa.request.TeleportRequest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPubSub;

/**
 * Cross-server and single-server teleport request plugin.
 */
public final class DreamTPA extends JavaPlugin {

    private static final String REDIS_CHANNEL = "dream:tpa";
    private static final long FAST_CONSUMED_TTL_MS = 10_000L;
    private static final long DREAM_RTP_BYPASS_TTL_MS = 120_000L;
    private static final String CONFIG_TABLE = "dreamtpa_config";
    private static final String CONFIG_KEY = "config.yml";

    private final Map<UUID, TeleportRequest> requestsByRequester = new HashMap<>();
    private final Map<UUID, Deque<TeleportRequest>> requestsByTarget = new HashMap<>();
    private final Map<UUID, Location> pendingTeleports = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, RemoteTeleportRequest>> remoteRequestsByTarget = new HashMap<>();
    private final Map<UUID, Long> fastConsumed = new ConcurrentHashMap<>();

    private int expireSeconds;
    private boolean crossServerMode;
    private JedisPubSub pubSub;
    private TpaGui gui;
    private MysqlConfigStore configStore;

    @Override
    public void onEnable() {
        loadMysqlConfig(true);
        this.gui = new TpaGui(this);

        if (crossServerMode) {
            if (!RedisManager.isInitialized()) {
                getLogger().severe("Redis 未启用，DreamTPA 跨服模式无法工作，插件已停用");
                Bukkit.getPluginManager().disablePlugin(this);
                return;
            }
            startRedisSubscriber();
            if (!PaperTeleportListener.register(this)) {
                getLogger().info("Paper AsyncPlayerSpawnLocationEvent not found, DreamTPA will use join fallback.");
            }
        } else {
            getLogger().info("DreamTPA 已启用单服模式，不会初始化 Redis 跨服通道");
        }

        TpaCommand tpaCommand = new TpaCommand(this);
        getCommand("dreamtpa").setExecutor(tpaCommand);
        getCommand("dreamtpa").setTabCompleter(tpaCommand);

        TpAcceptCommand tpAcceptCommand = new TpAcceptCommand(this);
        getCommand("tpaccept").setExecutor(tpAcceptCommand);
        getCommand("tpaccept").setTabCompleter(tpAcceptCommand);

        TpDenyCommand tpDenyCommand = new TpDenyCommand(this);
        getCommand("tpdeny").setExecutor(tpDenyCommand);
        getCommand("tpdeny").setTabCompleter(tpDenyCommand);

        Bukkit.getPluginManager().registerEvents(new RequestListener(this), this);
        Bukkit.getPluginManager().registerEvents(new TeleportListener(this), this);
        Bukkit.getPluginManager().registerEvents(gui, this);
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

    public boolean isCrossServerMode() {
        return crossServerMode;
    }

    public int getExpireSeconds() {
        return expireSeconds;
    }

    public void reloadMysqlConfig() {
        loadMysqlConfig(false);
    }

    private void loadMysqlConfig(boolean allowModeChange) {
        if (configStore == null) {
            configStore = MysqlConfigAPI.createStore(this, CONFIG_TABLE, java.util.Set.of(CONFIG_KEY));
        }
        configStore.init();
        configStore.reload(CONFIG_KEY);
        YamlConfiguration config = configStore.getYaml(CONFIG_KEY);
        this.expireSeconds = config.getInt("request-expire-seconds", 120);
        boolean configuredCrossServerMode = config.getBoolean("cross-server-mode", true);
        if (allowModeChange) {
            this.crossServerMode = configuredCrossServerMode;
            return;
        }
        if (configuredCrossServerMode != this.crossServerMode) {
            getLogger().warning("cross-server-mode 已在 MySQL 配置中改变，但该项需要重启服务器后生效");
        }
    }

    public TpaGui getGui() {
        return gui;
    }

    public List<String> getAvailablePlayerNames(Player viewer) {
        String self = viewer.getName();
        if (crossServerMode) {
            return DreamServerAPI.getClusterPlayers().stream()
                    .filter(name -> !name.equalsIgnoreCase(self))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .collect(Collectors.toList());
        }
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> !name.equalsIgnoreCase(self))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());
    }

    public boolean sendRequest(Player requester, String targetInput) {
        TargetRef target = resolveTarget(targetInput);
        if (target == null) {
            MessageUtil.notifyError(requester, "玩家不在线", "该玩家不在线");
            return false;
        }
        if (requester.getUniqueId().equals(target.uuid())) {
            MessageUtil.notifyError(requester, "无法申请", "你不能向自己发送传送请求");
            return false;
        }
        if (!canAccessStpServer(requester, target.serverId())) {
            MessageUtil.notifyError(requester, stpNoPermissionMessage(target.serverId()));
            return false;
        }

        TeleportRequest previous = getRequestByRequester(requester.getUniqueId());
        if (previous != null) {
            cancelRequest(previous, true, "已自动取消之前对 " + previous.getTargetName() + " 的传送请求");
        }

        TeleportRequest request = new TeleportRequest(requester, target.uuid(), target.name(), target.serverId(), expireSeconds);
        addRequest(request);
        notifyRequestAdd(request);

        MessageUtil.notifySuccess(requester, "请求已发送",
                "已向 " + target.name() + " 发送传送请求，请在 " + expireSeconds + " 秒内保持不动");
        sendToTarget(target.uuid(), target.name(),
                request.getRequesterName() + " 请求传送到你这里，输入 /tpaccept 同意或 /tpdeny 拒绝");

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (hasRequest(request)) {
                expireRequest(request);
            }
        }, expireSeconds * 20L);
        return true;
    }

    public boolean acceptRequest(Player target, String requesterInput) {
        TeleportRequest request = requesterInput == null
                ? getRequestByTarget(target.getUniqueId())
                : getRequestByTarget(target.getUniqueId(), requesterInput);
        if (request != null) {
            removeRequest(request);
            markDreamRtpBypass(request.getRequesterUuid());
            request.getRequester().teleport(target);
            MessageUtil.notifyInfo(request.getRequester(), "正在传送", "正在传送到 " + target.getName());
            MessageUtil.notifySuccess(target, "已接受", "你接受了 " + request.getRequesterName() + " 的传送请求");
            return true;
        }

        if (!crossServerMode) {
            MessageUtil.notifyError(target, "没有请求", "当前没有待处理的传送请求");
            return false;
        }

        RemoteTeleportRequest remoteRequest = requesterInput == null
                ? getLastRemoteRequest(target.getUniqueId())
                : getRemoteRequest(target.getUniqueId(), requesterInput);
        if (remoteRequest == null) {
            forwardCommand(target, "TpAccept", null);
            return false;
        }

        removeRemoteRequest(target.getUniqueId(), remoteRequest.getRequesterUuid());
        addPendingTeleport(remoteRequest.getRequesterUuid(), target.getLocation());
        forwardCommand(target, "TpAccept", remoteRequest);
        MessageUtil.notifySuccess(target, "已接受", "已接受 " + remoteRequest.getRequesterName() + " 的跨服传送请求");
        refreshInboxIfViewing(target.getUniqueId());
        return true;
    }

    public boolean denyRequest(Player target, String requesterInput) {
        TeleportRequest request = requesterInput == null
                ? getRequestByTarget(target.getUniqueId())
                : getRequestByTarget(target.getUniqueId(), requesterInput);
        if (request != null) {
            removeRequest(request);
            MessageUtil.notifyWarning(target, "已拒绝", "你拒绝了 " + request.getRequesterName() + " 的传送请求");
            MessageUtil.notifyError(request.getRequester(), "请求被拒绝", target.getName() + " 拒绝了你的传送请求");
            return true;
        }

        if (!crossServerMode) {
            MessageUtil.notifyError(target, "没有请求", "当前没有待处理的传送请求");
            return false;
        }

        RemoteTeleportRequest remoteRequest = requesterInput == null
                ? getLastRemoteRequest(target.getUniqueId())
                : getRemoteRequest(target.getUniqueId(), requesterInput);
        if (remoteRequest == null) {
            forwardCommand(target, "TpDeny", null);
            return false;
        }

        removeRemoteRequest(target.getUniqueId(), remoteRequest.getRequesterUuid());
        forwardCommand(target, "TpDeny", remoteRequest);
        MessageUtil.notifyWarning(target, "已拒绝", "你拒绝了 " + remoteRequest.getRequesterName() + " 的跨服传送请求");
        refreshInboxIfViewing(target.getUniqueId());
        return true;
    }

    public void cancelMovingRequest(Player player) {
        TeleportRequest request = getRequestByRequester(player.getUniqueId());
        if (request == null) {
            return;
        }
        removeRequest(request);
        MessageUtil.notifyError(player, "请求已取消", "你移动了，传送请求已取消");
        sendToTarget(request.getTargetUuid(), request.getTargetName(), player.getName() + " 取消了传送请求");
    }

    private void cancelRequest(TeleportRequest request, boolean notifyRequester, String requesterMessage) {
        removeRequest(request);
        if (notifyRequester) {
            MessageUtil.notifyWarning(request.getRequester(), "请求已取消", requesterMessage);
        }
        sendToTarget(request.getTargetUuid(), request.getTargetName(), request.getRequesterName() + " 取消了传送请求");
    }

    private void expireRequest(TeleportRequest request) {
        removeRequest(request);
        MessageUtil.notifyError(request.getRequester(), "请求已过期", "传送请求已过期");
        sendToTarget(request.getTargetUuid(), request.getTargetName(), "来自 " + request.getRequesterName() + " 的传送请求已过期");
    }

    private TargetRef resolveTarget(String input) {
        Player local = Bukkit.getPlayerExact(input);
        if (local != null) {
            return new TargetRef(local.getUniqueId(), local.getName(), DreamServerAPI.getServerId());
        }
        if (!crossServerMode) {
            return null;
        }
        UUID uuid = DreamServerAPI.getPlayerUuid(input);
        if (uuid == null) {
            return null;
        }
        String name = DreamServerAPI.getPlayerName(uuid);
        String serverId = DreamServerAPI.getPlayerServerId(uuid);
        return new TargetRef(uuid, name == null ? input : name, serverId);
    }

    private void sendToTarget(UUID targetUuid, String targetName, String message) {
        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null) {
            MessageUtil.notifyInfo(target, "传送请求", message);
            return;
        }
        sendMessageCrossServer(targetUuid, targetName, message);
    }

    public void addRequest(TeleportRequest request) {
        requestsByRequester.put(request.getRequesterUuid(), request);
        requestsByTarget.computeIfAbsent(request.getTargetUuid(), k -> new ArrayDeque<>()).addLast(request);
        refreshInboxIfViewing(request.getTargetUuid());
    }

    public TeleportRequest getRequestByRequester(UUID uuid) {
        return requestsByRequester.get(uuid);
    }

    public TeleportRequest getRequestByTarget(UUID targetUuid) {
        Deque<TeleportRequest> deque = requestsByTarget.get(targetUuid);
        return deque == null ? null : deque.peekLast();
    }

    public TeleportRequest getRequestByTarget(UUID targetUuid, String requesterInput) {
        Deque<TeleportRequest> deque = requestsByTarget.get(targetUuid);
        if (deque == null) {
            return null;
        }
        UUID requesterUuid = parseUuid(requesterInput);
        for (TeleportRequest req : deque) {
            if (requesterUuid != null && req.getRequesterUuid().equals(requesterUuid)) {
                return req;
            }
            if (req.getRequesterName().equalsIgnoreCase(requesterInput)) {
                return req;
            }
        }
        return null;
    }

    public boolean hasRequest(TeleportRequest request) {
        Deque<TeleportRequest> deque = requestsByTarget.get(request.getTargetUuid());
        return deque != null && deque.contains(request);
    }

    public void removeRequest(TeleportRequest request) {
        requestsByRequester.remove(request.getRequesterUuid());
        Deque<TeleportRequest> deque = requestsByTarget.get(request.getTargetUuid());
        if (deque != null) {
            deque.remove(request);
            if (deque.isEmpty()) {
                requestsByTarget.remove(request.getTargetUuid());
            }
        }
        notifyRequestRemove(request);
        refreshInboxIfViewing(request.getTargetUuid());
    }

    public List<TeleportRequest> getLocalRequestsForTarget(UUID targetUuid) {
        Deque<TeleportRequest> deque = requestsByTarget.get(targetUuid);
        return deque == null ? Collections.emptyList() : new ArrayList<>(deque);
    }

    public List<RemoteTeleportRequest> getRemoteRequests(UUID targetUuid) {
        Map<UUID, RemoteTeleportRequest> remote = remoteRequestsByTarget.get(targetUuid);
        return remote == null ? Collections.emptyList() : new ArrayList<>(remote.values());
    }

    public List<String> getRequesters(UUID targetUuid) {
        List<String> list = getLocalRequestsForTarget(targetUuid).stream()
                .map(TeleportRequest::getRequesterName)
                .collect(Collectors.toCollection(ArrayList::new));
        getRemoteRequests(targetUuid).stream()
                .map(RemoteTeleportRequest::getRequesterName)
                .forEach(list::add);
        return list;
    }

    public void addPendingTeleport(UUID playerUuid, Location location) {
        if (crossServerMode) {
            markDreamRtpBypass(playerUuid);
            pendingTeleports.put(playerUuid, location);
        }
    }

    public Location peekPendingTeleport(UUID playerUuid) {
        return pendingTeleports.get(playerUuid);
    }

    public Location pollPendingTeleport(UUID playerUuid) {
        return pendingTeleports.remove(playerUuid);
    }

    public void removePendingTeleport(UUID playerUuid) {
        pendingTeleports.remove(playerUuid);
    }

    public void markFastConsumed(UUID playerId) {
        fastConsumed.put(playerId, System.currentTimeMillis() + FAST_CONSUMED_TTL_MS);
    }

    public boolean wasFastConsumed(UUID playerId) {
        Long expire = fastConsumed.get(playerId);
        if (expire == null) {
            return false;
        }
        if (expire < System.currentTimeMillis()) {
            fastConsumed.remove(playerId);
            return false;
        }
        return true;
    }

    public void addRemoteRequest(UUID targetUuid, UUID requesterUuid, String requesterName) {
        if (getRequestByTarget(targetUuid, requesterUuid.toString()) != null) {
            return;
        }
        remoteRequestsByTarget.computeIfAbsent(targetUuid, k -> new LinkedHashMap<>())
                .put(requesterUuid, new RemoteTeleportRequest(requesterUuid, requesterName));
        refreshInboxIfViewing(targetUuid);
    }

    public void removeRemoteRequest(UUID targetUuid, UUID requesterUuid) {
        Map<UUID, RemoteTeleportRequest> map = remoteRequestsByTarget.get(targetUuid);
        if (map != null) {
            map.remove(requesterUuid);
            if (map.isEmpty()) {
                remoteRequestsByTarget.remove(targetUuid);
            }
        }
        refreshInboxIfViewing(targetUuid);
    }

    private RemoteTeleportRequest getLastRemoteRequest(UUID targetUuid) {
        List<RemoteTeleportRequest> list = getRemoteRequests(targetUuid);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    private RemoteTeleportRequest getRemoteRequest(UUID targetUuid, String requesterInput) {
        Map<UUID, RemoteTeleportRequest> map = remoteRequestsByTarget.get(targetUuid);
        if (map == null) {
            return null;
        }
        UUID requesterUuid = parseUuid(requesterInput);
        if (requesterUuid != null) {
            return map.get(requesterUuid);
        }
        for (RemoteTeleportRequest request : map.values()) {
            if (request.getRequesterName().equalsIgnoreCase(requesterInput)) {
                return request;
            }
        }
        return null;
    }

    public void refreshInboxIfViewing(UUID targetUuid) {
        Player target = Bukkit.getPlayer(targetUuid);
        if (target != null && gui != null) {
            gui.refreshInboxIfViewing(target);
        }
    }

    public void notifyRequestAdd(TeleportRequest request) {
        publish("REQUEST|ADD|" + request.getTargetUuid() + "|" + request.getTargetName()
                + "|" + request.getRequesterUuid() + "|" + request.getRequesterName());
    }

    public void notifyRequestRemove(TeleportRequest request) {
        publish("REQUEST|REMOVE|" + request.getTargetUuid() + "|" + request.getTargetName()
                + "|" + request.getRequesterUuid() + "|" + request.getRequesterName());
    }

    public void sendMessageCrossServer(UUID targetUuid, String targetName, String message) {
        publish("MESSAGE|" + targetUuid + "|" + targetName + "|" + message);
    }

    public void forwardCommand(Player sender, String subChannel, RemoteTeleportRequest remoteRequest) {
        if (!crossServerMode) {
            return;
        }
        StringBuilder sb = new StringBuilder("COMMAND|")
                .append(subChannel).append("|")
                .append(sender.getUniqueId()).append("|")
                .append(sender.getName());
        if (remoteRequest != null) {
            sb.append("|").append(remoteRequest.getRequesterUuid())
                    .append("|").append(remoteRequest.getRequesterName());
        }
        publish(sb.toString());
    }

    private void startRedisSubscriber() {
        pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                Bukkit.getScheduler().runTask(DreamTPA.this, () -> handleRedisMessage(message));
            }
        };
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try (Jedis jedis = RedisManager.getPool().getResource()) {
                jedis.subscribe(pubSub, REDIS_CHANNEL);
            } catch (Exception ex) {
                getLogger().warning("DreamTPA Redis 订阅中断: " + ex.getMessage());
            }
        });
    }

    private void publish(String msg) {
        if (!crossServerMode) {
            return;
        }
        try (Jedis jedis = RedisManager.getPool().getResource()) {
            jedis.publish(REDIS_CHANNEL, msg);
        } catch (Exception ex) {
            getLogger().warning("DreamTPA Redis 消息发送失败: " + ex.getMessage());
        }
    }

    private void markDreamRtpBypass(UUID playerUuid) {
        if (playerUuid == null) {
            return;
        }
        try {
            Class<?> dreamRtpClass = Class.forName("com.liuchangking.dreamrtp.DreamRTP");
            dreamRtpClass.getMethod("skipNextNewPlayerRtp", UUID.class, long.class)
                    .invoke(null, playerUuid, DREAM_RTP_BYPASS_TTL_MS);
        } catch (ClassNotFoundException ignored) {
            // DreamRTP is optional for DreamTPA.
        } catch (ReflectiveOperationException | LinkageError ex) {
            getLogger().warning("无法通知 DreamRTP 跳过本次 TPA 新手随机传送: " + ex.getMessage());
        }
    }

    private void handleRedisMessage(String message) {
        String[] parts = message.split("\\|", 6);
        if (parts.length < 1) {
            return;
        }
        if ("MESSAGE".equalsIgnoreCase(parts[0])) {
            handleRemoteMessage(parts);
        } else if ("COMMAND".equalsIgnoreCase(parts[0])) {
            handleRemoteCommand(parts);
        } else if ("REQUEST".equalsIgnoreCase(parts[0])) {
            handleRemoteRequest(parts);
        }
    }

    private void handleRemoteMessage(String[] parts) {
        if (parts.length >= 4) {
            UUID targetUuid = parseUuid(parts[1]);
            if (targetUuid == null) {
                return;
            }
            Player p = Bukkit.getPlayer(targetUuid);
            if (p != null) {
                MessageUtil.notifyInfo(p, "传送请求", parts[3]);
            }
            return;
        }

        // Legacy message: MESSAGE|targetName|message
        if (parts.length >= 3) {
            Player p = Bukkit.getPlayerExact(parts[1]);
            if (p != null) {
                MessageUtil.notifyInfo(p, "传送请求", parts[2]);
            }
        }
    }

    private void handleRemoteCommand(String[] parts) {
        if (parts.length >= 4) {
            String sub = parts[1];
            UUID targetUuid = parseUuid(parts[2]);
            if (targetUuid == null) {
                return;
            }
            UUID requesterUuid = parts.length >= 5 ? parseUuid(parts[4]) : null;
            TeleportRequest request = requesterUuid == null
                    ? getRequestByTarget(targetUuid)
                    : getRequestByTarget(targetUuid, requesterUuid.toString());
            if (request == null) {
                return;
            }

            if ("TpAccept".equalsIgnoreCase(sub)) {
                String targetServerId = DreamServerAPI.getPlayerServerId(request.getTargetUuid());
                if (targetServerId == null || targetServerId.isEmpty()) {
                    targetServerId = request.getTargetServerId();
                }
                removeRequest(request);
                if (!canAccessStpServer(request.getRequester(), targetServerId)) {
                    MessageUtil.notifyError(request.getRequester(), stpNoPermissionMessage(targetServerId));
                    sendMessageCrossServer(request.getTargetUuid(), request.getTargetName(),
                            request.getRequesterName() + " 没有权限进入你所在服务器，传送已取消");
                    return;
                }
                DreamServerAPI.sendPlayerToServer(request.getRequester(), targetServerId);
                MessageUtil.notifyInfo(request.getRequester(), "正在传送", "正在传送到 " + request.getTargetName());
                sendMessageCrossServer(request.getTargetUuid(), request.getTargetName(),
                        "你接受了 " + request.getRequesterName() + " 的传送请求");
            } else if ("TpDeny".equalsIgnoreCase(sub)) {
                removeRequest(request);
                MessageUtil.notifyError(request.getRequester(), "请求被拒绝", request.getTargetName() + " 拒绝了你的传送请求");
                sendMessageCrossServer(request.getTargetUuid(), request.getTargetName(),
                        "你拒绝了 " + request.getRequesterName() + " 的传送请求");
            }
            return;
        }

        handleLegacyRemoteCommand(parts);
    }

    private void handleLegacyRemoteCommand(String[] parts) {
        if (parts.length < 3) {
            return;
        }
        String sub = parts[1];
        String targetName = parts[2];
        UUID targetUuid = DreamServerAPI.getPlayerUuid(targetName);
        if (targetUuid == null) {
            Player local = Bukkit.getPlayerExact(targetName);
            targetUuid = local == null ? null : local.getUniqueId();
        }
        if (targetUuid == null) {
            return;
        }
        String requesterName = parts.length > 3 ? parts[3] : null;
        TeleportRequest request = requesterName == null
                ? getRequestByTarget(targetUuid)
                : getRequestByTarget(targetUuid, requesterName);
        if (request == null) {
            return;
        }
        if ("TpAccept".equalsIgnoreCase(sub)) {
            String targetServerId = DreamServerAPI.getPlayerServerId(request.getTargetUuid());
            if (targetServerId == null || targetServerId.isEmpty()) {
                targetServerId = request.getTargetServerId();
            }
            removeRequest(request);
            if (!canAccessStpServer(request.getRequester(), targetServerId)) {
                MessageUtil.notifyError(request.getRequester(), stpNoPermissionMessage(targetServerId));
                return;
            }
            DreamServerAPI.sendPlayerToServer(request.getRequester(), targetServerId);
            MessageUtil.notifyInfo(request.getRequester(), "正在传送", "正在传送到 " + request.getTargetName());
        } else if ("TpDeny".equalsIgnoreCase(sub)) {
            removeRequest(request);
            MessageUtil.notifyError(request.getRequester(), "请求被拒绝", request.getTargetName() + " 拒绝了你的传送请求");
        }
    }

    private void handleRemoteRequest(String[] parts) {
        if (parts.length >= 6) {
            String action = parts[1];
            UUID targetUuid = parseUuid(parts[2]);
            UUID requesterUuid = parseUuid(parts[4]);
            if (targetUuid == null || requesterUuid == null) {
                return;
            }
            String requesterName = parts[5];
            if ("ADD".equalsIgnoreCase(action)) {
                if (Bukkit.getPlayer(targetUuid) != null) {
                    addRemoteRequest(targetUuid, requesterUuid, requesterName);
                }
            } else if ("REMOVE".equalsIgnoreCase(action)) {
                removeRemoteRequest(targetUuid, requesterUuid);
            }
            return;
        }

        // Legacy request: REQUEST|ADD|targetName|requesterName
        if (parts.length >= 4) {
            String action = parts[1];
            String targetName = parts[2];
            String requesterName = parts[3];
            Player target = Bukkit.getPlayerExact(targetName);
            UUID requesterUuid = DreamServerAPI.getPlayerUuid(requesterName);
            if (target == null || requesterUuid == null) {
                return;
            }
            if ("ADD".equalsIgnoreCase(action)) {
                addRemoteRequest(target.getUniqueId(), requesterUuid, requesterName);
            } else if ("REMOVE".equalsIgnoreCase(action)) {
                removeRemoteRequest(target.getUniqueId(), requesterUuid);
            }
        }
    }

    private UUID parseUuid(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean canAccessStpServer(Player player, String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return true;
        }
        String currentServerId = DreamServerAPI.getServerId();
        if (currentServerId != null && currentServerId.equalsIgnoreCase(serverId)) {
            return true;
        }
        Plugin stp = Bukkit.getPluginManager().getPlugin("STP");
        if (stp == null || !stp.isEnabled()) {
            return true;
        }
        try {
            Object result = stp.getClass()
                    .getMethod("canPlayerAccessServer", Player.class, String.class)
                    .invoke(stp, player, serverId);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException | LinkageError ex) {
            getLogger().warning("无法调用 STP 校验服务器权限，已阻止本次跨服 TPA: " + ex.getMessage());
            return false;
        }
    }

    private String stpNoPermissionMessage(String serverId) {
        Plugin stp = Bukkit.getPluginManager().getPlugin("STP");
        if (stp == null || !stp.isEnabled()) {
            return "没有权限传送到该服务器.";
        }
        try {
            Object result = stp.getClass()
                    .getMethod("getServerNoPermissionMessage", String.class)
                    .invoke(stp, serverId);
            String message = result == null ? null : result.toString();
            return message == null || message.isEmpty() ? "没有权限传送到该服务器." : message;
        } catch (ReflectiveOperationException | LinkageError ex) {
            return "没有权限传送到该服务器.";
        }
    }

    private record TargetRef(UUID uuid, String name, String serverId) {
    }
}
