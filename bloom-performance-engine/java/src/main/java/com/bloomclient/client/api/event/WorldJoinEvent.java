package com.bloomclient.client.api.event;

import com.bloomclient.client.api.storage.WorldIdentity;

/** Posted when the player enters a world or server (after the first client level exists). */
public record WorldJoinEvent(WorldIdentity world) {
}
