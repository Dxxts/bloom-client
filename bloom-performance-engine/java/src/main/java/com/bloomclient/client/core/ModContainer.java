package com.bloomclient.client.core;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.api.BloomMod;
import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.ModContext;
import com.bloomclient.client.api.ModMetadata;
import com.bloomclient.client.api.ModPage;
import com.bloomclient.client.api.event.ClientTickEvent;
import com.bloomclient.client.api.event.Subscription;
import com.bloomclient.client.api.hud.HudWidget;
import com.bloomclient.client.api.settings.SettingsGroup;
import com.bloomclient.client.api.storage.ModStorage;
import com.bloomclient.client.api.storage.WorldIdentity;
import com.bloomclient.client.api.storage.WorldStorage;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime state of one Bloom mod and its {@link ModContext}: lifecycle state,
 * listeners and tasks it owns, widgets and pages it registered, errors.
 *
 * <p>Error policy: an exception in {@code onInitialize} marks the mod as
 * failed (it cannot be enabled this session); in {@code onEnable} it turns
 * the mod off; in listeners, tasks and widgets it is recorded, and three of
 * them within a minute turn the mod off. The rest of the client keeps
 * running in every case.
 */
public final class ModContainer implements ModContext {
	public enum State {
		DISABLED, ENABLED, FAILED
	}

	public record ModError(String time, String where, String message) {
	}

	private static final int ERROR_LIMIT = 3;
	private static final long ERROR_WINDOW_MS = 60_000;

	private final BloomMod mod;
	private final ModMetadata metadata;
	private final Logger logger;
	private final SettingsGroup settings;
	private final ModManager manager;
	private final ModStorage storage;
	private State state = State.DISABLED;
	private boolean initialized;
	private boolean enabling;
	private final List<Subscription> sessionSubscriptions = new ArrayList<>();
	private final List<HudWidget> widgets = new ArrayList<>();
	private final List<ModPage> pages = new ArrayList<>();
	private final List<ModError> errors = new ArrayList<>();
	private final long[] recentErrors = new long[ERROR_LIMIT];
	private int recentIndex;
	private int totalErrors;
	private @Nullable String failure;

	ModContainer(BloomMod mod, ModManager manager) {
		this.mod = mod;
		this.metadata = mod.metadata();
		this.manager = manager;
		this.logger = LoggerFactory.getLogger("bloom/" + metadata.id());
		this.settings = new SettingsGroup(BloomStorage.configDir().resolve("mods").resolve(metadata.id() + ".json"));
		Path global = BloomStorage.dataDir().resolve("mods").resolve(metadata.id());
		this.storage = new ModStorage() {
			@Override
			public Path globalDir() {
				return global;
			}

			@Override
			public @Nullable WorldStorage world() {
				WorldIdentity w = BloomClient.worlds().current();
				return w == null ? null : new WorldStorage(w, BloomStorage.dataDir().resolve("worlds").resolve(w.key()).resolve(metadata.id()));
			}
		};
	}

	public BloomMod mod() {
		return mod;
	}

	public State state() {
		return state;
	}

	public @Nullable String failure() {
		return failure;
	}

	public List<ModError> errors() {
		return Collections.unmodifiableList(errors);
	}

	public int totalErrors() {
		return totalErrors;
	}

	public List<HudWidget> widgets() {
		return Collections.unmodifiableList(widgets);
	}

	public List<ModPage> pages() {
		return Collections.unmodifiableList(pages);
	}

	EventBus bus() {
		return manager.bus();
	}

	// ---------------------------------------------------------------- lifecycle (called by ModManager)

	void initialize() {
		try {
			mod.onInitialize(this);
			initialized = true;
			settings.load();
		} catch (Throwable t) {
			state = State.FAILED;
			failure = "onInitialize: " + t;
			record("onInitialize", t);
			logger.error("Bloom mod {} failed to initialize; it stays off", metadata.id(), t);
		}
	}

	boolean enable() {
		if (!initialized || state == State.ENABLED) return state == State.ENABLED;
		state = State.ENABLED;
		failure = null;
		enabling = true;
		try {
			mod.onEnable(this);
			return true;
		} catch (Throwable t) {
			record("onEnable", t);
			logger.error("Bloom mod {} failed to enable", metadata.id(), t);
			failure = "onEnable: " + t;
			disable();
			return false;
		} finally {
			enabling = false;
		}
	}

	void disable() {
		if (state != State.ENABLED) return;
		state = State.DISABLED;
		for (Subscription s : sessionSubscriptions) s.cancel();
		sessionSubscriptions.clear();
		try {
			mod.onDisable(this);
		} catch (Throwable t) {
			record("onDisable", t);
			logger.error("Bloom mod {} failed while disabling", metadata.id(), t);
		}
		settings.saveIfDirty();
	}

	void shutdown() {
		disable();
		if (!initialized) return;
		try {
			mod.onShutdown(this);
		} catch (Throwable t) {
			logger.error("Bloom mod {} failed while shutting down", metadata.id(), t);
		}
		settings.saveIfDirty();
	}

	/** Runs {@code r} isolated: an exception is recorded instead of propagating. */
	public void guard(String where, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			reportError(where, t);
		}
	}

	private void record(String where, Throwable t) {
		totalErrors++;
		String msg = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
		errors.add(new ModError(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")), where, msg));
		if (errors.size() > 20) errors.removeFirst();
	}

	// ---------------------------------------------------------------- ModContext

	@Override
	public ModMetadata metadata() {
		return metadata;
	}

	@Override
	public Logger logger() {
		return logger;
	}

	@Override
	public SettingsGroup settings() {
		return settings;
	}

	@Override
	public <E> Subscription on(Class<E> type, Consumer<? super E> listener) {
		Subscription s = manager.bus().add(this, type, listener);
		if (enabling || (initialized && state == State.ENABLED)) sessionSubscriptions.add(s);
		return s;
	}

	@Override
	public Subscription every(int ticks, Runnable task) {
		int interval = Math.max(1, ticks);
		return on(ClientTickEvent.class, e -> {
			if (e.every(interval)) task.run();
		});
	}

	@Override
	public void registerWidget(HudWidget widget) {
		widgets.add(widget);
		BloomClient.hud().registerWidget(this, widget);
	}

	@Override
	public void registerPage(ModPage page) {
		pages.add(page);
	}

	@Override
	public ModStorage storage() {
		return storage;
	}

	@Override
	public GameInfo game() {
		return BloomClient.game();
	}

	@Override
	public boolean isEnabled() {
		return state == State.ENABLED;
	}

	@Override
	public void reportError(String where, Throwable error) {
		record(where, error);
		logger.error("Bloom mod {}: error in {}", metadata.id(), where, error);
		if (state != State.ENABLED) return;
		long now = System.currentTimeMillis();
		recentErrors[recentIndex] = now;
		recentIndex = (recentIndex + 1) % ERROR_LIMIT;
		long oldest = recentErrors[recentIndex];
		if (oldest != 0 && now - oldest <= ERROR_WINDOW_MS) {
			failure = where + ": " + error;
			manager.disableAfterErrors(this);
		}
	}

	@Override
	public void notify(String message) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			mc.gui.hud.setOverlayMessage(Component.literal(message), false);
		}
	}
}
