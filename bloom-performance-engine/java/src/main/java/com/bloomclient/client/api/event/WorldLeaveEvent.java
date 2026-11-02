package com.bloomclient.client.api.event;

import com.bloomclient.client.api.storage.WorldIdentity;

/** Posted when the player leaves a world or server. Save per-world data here. */
public record WorldLeaveEvent(WorldIdentity world) {
}
