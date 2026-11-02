package com.bloomclient.client.api;

import com.bloomclient.client.api.event.Subscription;
import com.bloomclient.client.api.hud.HudWidget;
import com.bloomclient.client.api.settings.SettingsGroup;
import com.bloomclient.client.api.storage.ModStorage;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Everything a Bloom mod uses to talk to Bloom and to the game. One context
 * per mod; it is passed to every lifecycle method.
 */
public interface ModContext {
	ModMetadata metadata();

	Logger logger();

	/** The mod's persistent settings ({@code config/bloom/mods/<id>.json}). */
	SettingsGroup settings();

	/**
	 * Listens to a Bloom event ({@code ClientTickEvent}, {@code WorldJoinEvent},
	 * {@code WorldLeaveEvent}, {@code DimensionChangeEvent},
	 * {@code InventoryChangedEvent}). The listener runs only while the mod is
	 * enabled; one registered in {@link BloomMod#onEnable} is removed on disable.
	 */
	<E> Subscription on(Class<E> type, Consumer<? super E> listener);

	/** Runs {@code task} every {@code ticks} client ticks while the mod is enabled (same rules as {@link #on}). */
	Subscription every(int ticks, Runnable task);

	/** Adds a widget type to the HUD Studio. Call in {@link BloomMod#onInitialize}. */
	void registerWidget(HudWidget widget);

	/** Adds a page to the Bloom Menu. Call in {@link BloomMod#onInitialize}. */
	void registerPage(ModPage page);

	ModStorage storage();

	/** Shared, read-only game state updated by Bloom once per tick. */
	GameInfo game();

	boolean isEnabled();

	/** Records a non-fatal error (shown in the Mods page). */
	void reportError(String where, Throwable error);

	/** Short message in the action bar. */
	void notify(String message);
}
