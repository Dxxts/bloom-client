package com.bloomclient.client.api;

import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Ui;
import net.minecraft.client.input.KeyEvent;

/**
 * A screen of a mod, drawn inside the Bloom Menu's content area with the
 * shared {@link Ui} toolkit, so every mod looks and behaves like Bloom.
 * Registered with {@link ModContext#registerPage}; reachable from the Mods
 * page and from search.
 */
public interface ModPage {
	/** Unique within the mod. */
	String id();

	/** Title (translation key or plain text). */
	String title();

	Icon icon();

	/** Draws the page in the rectangle (x, y, w, h). */
	void render(Ui ui, int x, int y, int w, int h);

	default void onOpen() {
	}

	default void onClose() {
	}

	/** Keys not consumed by a focused text field. Return true to consume. */
	default boolean keyPressed(KeyEvent event) {
		return false;
	}
}
