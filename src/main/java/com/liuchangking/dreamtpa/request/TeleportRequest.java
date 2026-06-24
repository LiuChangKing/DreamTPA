package com.liuchangking.dreamtpa.request;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * 储存待处理传送请求的数据
 */
public class TeleportRequest {
    private final Player requester;
    private final UUID requesterUuid;
    private final String requesterName;
    private final UUID targetUuid;
    private final String targetName;
    private final String targetServerId;
    private final Location origin;
    private final long createdAt;
    private final long expiresAt;

    public TeleportRequest(Player requester, UUID targetUuid, String targetName, String targetServerId, int expireSeconds) {
        this.requester = requester;
        this.requesterUuid = requester.getUniqueId();
        this.requesterName = requester.getName();
        this.targetUuid = targetUuid;
        this.targetName = targetName;
        this.targetServerId = targetServerId;
        this.origin = requester.getLocation().clone();
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = createdAt + expireSeconds * 1000L;
    }

    public Player getRequester() {
        return requester;
    }

    public UUID getRequesterUuid() {
        return requesterUuid;
    }

    public String getRequesterName() {
        return requesterName;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getTargetServerId() {
        return targetServerId;
    }

    public Location getOrigin() {
        return origin;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public long getRemainingSeconds() {
        return Math.max(0L, (expiresAt - System.currentTimeMillis() + 999L) / 1000L);
    }
}
