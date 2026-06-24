package com.liuchangking.dreamtpa.request;

import java.util.UUID;

public class RemoteTeleportRequest {
    private final UUID requesterUuid;
    private final String requesterName;

    public RemoteTeleportRequest(UUID requesterUuid, String requesterName) {
        this.requesterUuid = requesterUuid;
        this.requesterName = requesterName;
    }

    public UUID getRequesterUuid() {
        return requesterUuid;
    }

    public String getRequesterName() {
        return requesterName;
    }
}
