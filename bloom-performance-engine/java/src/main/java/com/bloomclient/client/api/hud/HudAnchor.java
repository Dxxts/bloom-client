package com.bloomclient.client.api.hud;

/**
 * Screen point an element is attached to. The element's matching corner (or
 * edge midpoint) sits at the anchor plus the element's offset, so elements
 * keep their place at any resolution and GUI scale, and grow away from the
 * edge they are attached to.
 */
public enum HudAnchor {
	TOP_LEFT(0, 0), TOP_CENTER(1, 0), TOP_RIGHT(2, 0),
	MIDDLE_LEFT(0, 1), CENTER(1, 1), MIDDLE_RIGHT(2, 1),
	BOTTOM_LEFT(0, 2), BOTTOM_CENTER(1, 2), BOTTOM_RIGHT(2, 2);

	/** 0 = left/top, 1 = center, 2 = right/bottom. */
	public final int hx, vy;

	HudAnchor(int hx, int vy) {
		this.hx = hx;
		this.vy = vy;
	}

	public int screenX(int screenWidth) {
		return hx * screenWidth / 2;
	}

	public int screenY(int screenHeight) {
		return vy * screenHeight / 2;
	}

	/** Offset of the element's own anchor point from its top-left corner. */
	public int elementX(int width) {
		return hx * width / 2;
	}

	public int elementY(int height) {
		return vy * height / 2;
	}

	/** Anchor closest to a point (used after dragging). */
	public static HudAnchor nearest(int cx, int cy, int screenWidth, int screenHeight) {
		int h = cx < screenWidth / 3 ? 0 : cx > screenWidth * 2 / 3 ? 2 : 1;
		int v = cy < screenHeight / 3 ? 0 : cy > screenHeight * 2 / 3 ? 2 : 1;
		for (HudAnchor a : values()) {
			if (a.hx == h && a.vy == v) return a;
		}
		return TOP_LEFT;
	}
}
