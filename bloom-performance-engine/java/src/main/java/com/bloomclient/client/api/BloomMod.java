package com.bloomclient.client.api;

/**
 * A Bloom mod: an official, in-process feature module managed by Bloom.
 *
 * <p>Lifecycle (all calls on the client thread, each one isolated: an
 * exception is logged, shown in the Mods page and never crashes the game):
 * <ol>
 *   <li>{@link #onInitialize} once at startup, enabled or not: create
 *       settings, register HUD widgets and pages;</li>
 *   <li>{@link #onEnable} when the mod is turned on (at startup if it was on):
 *       register listeners and tasks, which Bloom cancels automatically on
 *       disable;</li>
 *   <li>{@link #onDisable} when the mod is turned off or fails: stop your
 *       own work and save;</li>
 *   <li>{@link #onShutdown} once when the game closes (after
 *       {@link #onDisable} if the mod was on).</li>
 * </ol>
 *
 * Bloom mods are compiled into Bloom; Bloom never downloads or runs external
 * code.
 */
public interface BloomMod {
	ModMetadata metadata();

	void onInitialize(ModContext context);

	default void onEnable(ModContext context) {
	}

	default void onDisable(ModContext context) {
	}

	default void onShutdown(ModContext context) {
	}

	/** Whether the mod starts enabled on a fresh installation. */
	default boolean enabledByDefault() {
		return true;
	}
}
