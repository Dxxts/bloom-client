package com.bloomclient.client;

import com.bloomclient.client.ui.screen.BloomMenuScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Bloom's key bindings. They are regular vanilla {@link KeyMapping}s (added to
 * {@code Options.keyMappings} by {@code OptionsMixin}), so players rebind
 * them in Options → Controls like any other key and the binding is saved in
 * {@code options.txt}.
 */
public final class BloomKeys {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("bloom", "main"));
	/** Opens the Bloom Menu. Default: right shift. */
	public static final KeyMapping OPEN_MENU = new KeyMapping("key.bloom.menu", InputConstants.KEY_RSHIFT, CATEGORY);
	/** Opens the HUD Studio directly. Unbound by default. */
	public static final KeyMapping OPEN_STUDIO = new KeyMapping("key.bloom.hud_studio", InputConstants.UNKNOWN.getValue(), CATEGORY);

	private BloomKeys() {
	}

	public static KeyMapping[] all() {
		return new KeyMapping[] {OPEN_MENU, OPEN_STUDIO};
	}

	public static boolean isMenuKey(KeyEvent event) {
		return OPEN_MENU.matches(event);
	}

	/** Gameplay (no screen open): called from the vanilla key handling loop. */
	public static void handleInGame(Minecraft mc) {
		while (OPEN_MENU.consumeClick()) {
			BloomMenuScreen.open(null);
		}
		while (OPEN_STUDIO.consumeClick()) {
			if (mc.level != null) mc.gui.setScreen(new com.bloomclient.client.ui.screen.HudStudioScreen(null));
		}
	}

	/** Whether the menu key may open Bloom on top of {@code screen} (title and pause screens only). */
	public static boolean canOpenOver(@Nullable Screen screen) {
		if (!BloomClient.settings().openFromMenus.on()) return false;
		return screen instanceof TitleScreen || screen instanceof PauseScreen;
	}
}
