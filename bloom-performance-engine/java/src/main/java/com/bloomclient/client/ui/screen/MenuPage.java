package com.bloomclient.client.ui.screen;

import com.bloomclient.client.ui.Ui;
import net.minecraft.client.input.KeyEvent;

/** One section of the Bloom Menu. */
abstract class MenuPage {
	protected final BloomMenuScreen menu;

	MenuPage(BloomMenuScreen menu) {
		this.menu = menu;
	}

	abstract String title();

	String subtitle() {
		return "";
	}

	/** Draws the page in the content rectangle. */
	abstract void render(Ui ui, int x, int y, int w, int h);

	void onShow() {
	}

	boolean keyPressed(KeyEvent event) {
		return false;
	}
}
