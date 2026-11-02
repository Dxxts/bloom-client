package com.bloomclient.client.api.event;

/**
 * Posted at the end of every client tick (20 per second while the game runs,
 * also on the title screen). One instance is reused: do not keep it.
 */
public final class ClientTickEvent {
	private long tick;

	public long tick() {
		return tick;
	}

	/** Internal: advanced by Bloom before posting. */
	public void advance() {
		tick++;
	}

	public boolean every(int ticks) {
		return ticks <= 1 || tick % ticks == 0;
	}
}
