package com.bloomclient.client.ui;

import net.minecraft.client.Minecraft;

/**
 * Feedback from mod pages to the player, shown by the Bloom screen that is
 * currently open (toast at the bottom, confirmation dialog for destructive
 * actions).
 */
public final class Feedback {
	private Feedback() {
	}

	public static void toast(String message) {
		toast(message, BloomTheme.ACCENT);
	}

	public static void toast(String message, int color) {
		if (Minecraft.getInstance().gui.screen() instanceof BloomScreen screen) {
			screen.toast(message, color);
		}
	}

	/** Asks for confirmation; {@code action} runs only if the player confirms. */
	public static void confirm(String message, Runnable action) {
		if (Minecraft.getInstance().gui.screen() instanceof BloomScreen screen) {
			screen.confirm(message, action);
		}
	}
}
