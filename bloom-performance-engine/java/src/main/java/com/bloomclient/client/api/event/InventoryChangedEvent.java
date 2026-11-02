package com.bloomclient.client.api.event;

/**
 * Posted (at most once per tick) when the local player's inventory changed:
 * items added, removed, moved or damaged. Based on the inventory's change
 * counter, so it costs nothing while nothing changes. One instance is reused.
 */
public final class InventoryChangedEvent {
	public static final InventoryChangedEvent INSTANCE = new InventoryChangedEvent();

	private InventoryChangedEvent() {
	}
}
