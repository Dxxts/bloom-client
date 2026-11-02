package com.bloomclient.client.api;

import com.bloomclient.client.ui.Icon;

/** Category shown in the Mods list and used by its filters. */
public enum ModCategory {
	HUD("bloom.category.hud", Icon.HUD),
	WORLD("bloom.category.world", Icon.GLOBE),
	INVENTORY("bloom.category.inventory", Icon.BAG),
	UTILITY("bloom.category.utility", Icon.WRENCH),
	VISUAL("bloom.category.visual", Icon.EYE);

	public final String translationKey;
	public final Icon icon;

	ModCategory(String translationKey, Icon icon) {
		this.translationKey = translationKey;
		this.icon = icon;
	}
}
