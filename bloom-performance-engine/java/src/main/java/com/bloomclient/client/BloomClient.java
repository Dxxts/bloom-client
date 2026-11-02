package com.bloomclient.client;

import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.event.ClientTickEvent;
import com.bloomclient.client.api.event.InventoryChangedEvent;
import com.bloomclient.client.core.BloomStorage;
import com.bloomclient.client.core.DevPreview;
import com.bloomclient.client.core.ModManager;
import com.bloomclient.client.core.WorldSession;
import com.bloomclient.client.hud.HudManager;
import com.bloomclient.client.hud.widgets.CoreWidgets;
import com.bloomclient.client.mods.basememory.BaseMemoryMod;
import com.bloomclient.client.mods.inventory.InventoryManagerMod;
import com.bloomclient.client.mods.worldtracker.WorldTrackerMod;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.engine.BloomEngine;
import com.bloomclient.engine.mixin.DebugScreenEntriesInvoker;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugEntryCategory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bloom Client in game: Bloom Menu, HUD Studio and Bloom mods.
 *
 * <p>Independent from the rendering engine ({@link BloomEngine}): it works the
 * same with the native mesher on, off or failed. Everything runs on the client
 * thread from four hooks: client tick, vanilla key handling, the vanilla HUD
 * pass and shutdown (see {@code com.bloomclient.client.mixin}).
 */
public final class BloomClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("bloom-client");
	public static final Identifier DEBUG_ENTRY = Identifier.fromNamespaceAndPath("bloom", "client");

	private static BloomSettings settings;
	private static HudManager hud;
	private static ModManager mods;
	private static final WorldSession WORLDS = new WorldSession();
	private static final GameInfo GAME = new GameInfo();
	private static final ClientTickEvent TICK = new ClientTickEvent();
	private static boolean ready;
	private static boolean shutDown;
	private static int lastInventoryChange = Integer.MIN_VALUE;
	private static @Nullable LocalPlayer lastPlayer;

	@Override
	public void onInitializeClient() {
		long t0 = System.nanoTime();
		settings = new BloomSettings();
		settings.group.load();
		hud = new HudManager();
		CoreWidgets.registerAll(hud);
		mods = new ModManager();
		mods.register(new WorldTrackerMod());
		mods.register(new InventoryManagerMod());
		mods.register(new BaseMemoryMod());
		mods.initialize();
		hud.load();
		registerDiagnostics();
		ready = true;
		LOGGER.info("Bloom Client: {} mods ({} enabled), {} HUD widgets, ready in {} ms", mods.all().size(), mods.enabledCount(),
				hud.widgets().size(), (System.nanoTime() - t0) / 1_000_000);
	}

	public static boolean ready() {
		return ready;
	}

	public static BloomSettings settings() {
		return settings;
	}

	public static HudManager hud() {
		return hud;
	}

	public static ModManager mods() {
		return mods;
	}

	public static WorldSession worlds() {
		return WORLDS;
	}

	public static GameInfo game() {
		return GAME;
	}

	public static String version() {
		return FabricLoader.getInstance().getModContainer("bloom-engine").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
	}

	/** End of every client tick. */
	public static void tick(Minecraft mc) {
		if (!ready || shutDown) return;
		WORLDS.tick(mc, mods.bus());
		GAME.update(mc, WORLDS.current());
		LocalPlayer player = mc.player;
		if (player != null) {
			int changes = player.getInventory().getTimesChanged();
			if (player != lastPlayer || changes != lastInventoryChange) {
				lastPlayer = player;
				lastInventoryChange = changes;
				mods.bus().post(InventoryChangedEvent.INSTANCE);
			}
		} else {
			lastPlayer = null;
		}
		TICK.advance();
		mods.bus().post(TICK);
		DevPreview.tick(mc);
		if (TICK.every(20)) {
			settings.group.saveIfDirty();
			mods.saveSettings();
			hud.saveIfDirty();
		}
	}

	/** Game closing: disable and shut down mods, then wait for pending saves. */
	public static void shutdown() {
		if (!ready || shutDown) return;
		shutDown = true;
		try {
			WORLDS.close(mods.bus());
			mods.shutdown();
			settings.group.saveIfDirty();
			hud.saveIfDirty();
		} catch (Throwable t) {
			LOGGER.error("Bloom: error during shutdown", t);
		}
		BloomStorage.flush();
	}

	private static void registerDiagnostics() {
		DebugScreenEntriesInvoker.bloom$register(DEBUG_ENTRY, new DebugScreenEntry() {
			@Override
			public void display(DebugScreenDisplayer displayer, @Nullable Level level, @Nullable LevelChunk clientChunk, @Nullable LevelChunk serverChunk) {
				List<String> out = new ArrayList<>();
				out.add(String.format(Locale.ROOT, "Bloom HUD: %d/%d elements · %.1f µs/frame · %d updates", hud.drawnLastFrame(), hud.elements().size(),
						hud.averageFrameMicros(), hud.updatesTotal()));
				int errors = 0;
				for (var c : mods.all()) errors += c.totalErrors();
				out.add(String.format(Locale.ROOT, "Bloom mods: %d/%d enabled · %d errors · world %s", mods.enabledCount(), mods.all().size(), errors,
						WORLDS.current() == null ? "-" : WORLDS.current().key()));
				displayer.addToGroup(DEBUG_ENTRY, out);
			}

			@Override
			public boolean isAllowed(boolean reducedDebugInfo) {
				return true;
			}

			@Override
			public DebugEntryCategory category() {
				return DebugEntryCategory.RENDERER;
			}
		});
	}

	/** Short engine status for the Home page. */
	public static String engineStatus() {
		return switch (BloomEngine.status()) {
			case ACTIVE -> Tr.t("bloom.home.engine.active");
			case OFF -> Tr.t("bloom.home.engine.off", BloomEngine.reason());
			case FAILED -> Tr.t("bloom.home.engine.failed", BloomEngine.reason());
		};
	}
}
