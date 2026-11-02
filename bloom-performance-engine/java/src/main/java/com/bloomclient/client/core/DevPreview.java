package com.bloomclient.client.core;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.ui.screen.BloomMenuScreen;
import com.bloomclient.client.ui.screen.HudStudioScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Development aid (like the engine's benchmark driver): with
 * {@code -Dbloom.client.preview=menu,mods,wt,im,studio,hud} Bloom opens each
 * screen once a world is loaded and saves {@code screenshots/bloom-preview-<name>.png};
 * {@code -Dbloom.client.preview.quit=true} closes the game afterwards.
 * Does nothing without the property.
 */
public final class DevPreview {
	private static String[] screens = System.getProperty("bloom.client.preview", "").isBlank() ? new String[0]
			: System.getProperty("bloom.client.preview").split(",");
	private static int index = -1;
	private static int wait;
	/** Ticks each screen stays open ({@code -Dbloom.client.preview.hold}, default 50). */
	private static final int HOLD = Integer.getInteger("bloom.client.preview.hold", 50);
	private static Boolean pausedOnFocusLoss;

	private DevPreview() {
	}

	public static void tick(Minecraft mc) {
		if (screens.length == 0 || mc.level == null || mc.player == null) return;
		if (pausedOnFocusLoss == null) {
			pausedOnFocusLoss = mc.options.pauseOnLostFocus;
			mc.options.pauseOnLostFocus = false;
		}
		wait++;
		if (index < 0) {
			if (wait < 120) return;
			index = 0;
			wait = 0;
			open(mc, screens[0].trim());
			return;
		}
		if (wait == HOLD - 10) {
			String name = screens[index].trim();
			Screenshot.grab(mc.gameDirectory, "bloom-preview-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, message -> {
			});
		}
		if (wait >= HOLD) {
			index++;
			wait = 0;
			if (index >= screens.length) {
				screens = new String[0];
				mc.options.pauseOnLostFocus = pausedOnFocusLoss;
				mc.gui.setScreen(null);
				if (Boolean.getBoolean("bloom.client.preview.quit")) mc.stop();
				return;
			}
			open(mc, screens[index].trim());
		}
	}

	private static void open(Minecraft mc, String name) {
		BloomClient.LOGGER.info("Bloom preview: {}", name);
		switch (name) {
			case "studio" -> mc.gui.setScreen(new HudStudioScreen(null));
			case "studio-select" -> mc.gui.setScreen(new HudStudioScreen(null).selectFirst());
			case "studio-library" -> mc.gui.setScreen(new HudStudioScreen(null).openLibrary(null));
			case "hud" -> mc.gui.setScreen(null);
			case "bm-guide" -> {
				mc.gui.setScreen(null);
				com.bloomclient.client.mods.basememory.BaseMemoryMod.previewGuide();
			}
			case "wt" -> BloomMenuScreen.openMod(null, BloomClient.mods().get("bloom-world-tracker"), "world-tracker");
			case "im" -> BloomMenuScreen.openMod(null, BloomClient.mods().get("bloom-inventory-manager"), "inventory-manager");
			case "bm", "bm-containers", "bm-villagers" -> {
				System.setProperty("bloom.client.preview.bm.tab", name.equals("bm") ? "0" : name.equals("bm-containers") ? "1" : "2");
				BloomMenuScreen.openMod(null, BloomClient.mods().get("bloom-base-memory"), "base-memory");
			}
			default -> BloomMenuScreen.openSection(name);
		}
	}
}
