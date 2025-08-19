package com.liuchangking.dreamtpa.request;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * 储存待处理传送请求的数据
 */
public class TeleportRequest {
    private final Player requester;
    private final String targetName;
    private final Location origin;

    public TeleportRequest(Player requester, String targetName) {
        this.requester = requester;
        this.targetName = targetName;
        this.origin = requester.getLocation().clone();
    }

    public Player getRequester() {
        return requester;
    }

    public String getTargetName() {
        return targetName;
    }

    public Location getOrigin() {
        return origin;
    }
}
