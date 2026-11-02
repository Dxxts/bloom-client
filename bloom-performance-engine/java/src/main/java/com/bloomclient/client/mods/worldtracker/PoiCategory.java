package com.bloomclient.client.mods.worldtracker;

import com.bloomclient.client.ui.Icon;

/** Categories of points of interest. */
public enum PoiCategory {
	HOME(Icon.HOME, 0xFF5CE1A8),
	BASE(Icon.CUBE, 0xFF6CB4EE),
	VILLAGE(Icon.GLOBE, 0xFFE8B54D),
	STRUCTURE(Icon.LAYERS, 0xFFC58AF0),
	RESOURCE(Icon.STAR, 0xFFF29A5C),
	PORTAL(Icon.TARGET, 0xFF8B8CF6),
	CUSTOM(Icon.PIN, 0xFFE9EEEC);

	public final Icon icon;
	public final int color;

	PoiCategory(Icon icon, int color) {
		this.icon = icon;
		this.color = color;
	}

	public String translationKey() {
		return "bloom.wt.category." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
