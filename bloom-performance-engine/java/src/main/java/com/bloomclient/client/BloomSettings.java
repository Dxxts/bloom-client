package com.bloomclient.client;

import com.bloomclient.client.api.settings.Setting;
import com.bloomclient.client.api.settings.SettingsGroup;
import com.bloomclient.client.core.BloomStorage;

/** Bloom's own settings ({@code config/bloom/client.json}), shown in the Settings page. */
public final class BloomSettings {
	public enum MenuScale {
		SMALL, NORMAL, LARGE
	}

	public final SettingsGroup group = new SettingsGroup(BloomStorage.configDir().resolve("client.json"));

	public final Setting.Bool animations;
	public final Setting.Choice<MenuScale> menuScale;
	public final Setting.Bool blur;
	/** Bloom's TrueType UI font (Inter) instead of Minecraft's pixel font, in Bloom's menus only. */
	public final Setting.Bool smoothFont;
	/** Translucent "glass" panels over the blurred world instead of opaque ones. */
	public final Setting.Bool glass;
	public final Setting.Bool openFromMenus;
	/** Short explanations in the Mods page and the HUD Studio; the "×" on a tip turns this off. */
	public final Setting.Bool tips;
	public final Setting.Bool hudEnabled;
	public final Setting.Bool hideHudWithDebug;
	public final Setting.Int gridSize;
	public final Setting.Bool snap;
	public final Setting.Bool guides;

	public BloomSettings() {
		group.section("bloom.settings.section.menu");
		animations = group.bool("animations", true, "bloom.settings.animations").describe("bloom.settings.animations.desc");
		menuScale = group.choice("menuScale", MenuScale.NORMAL, s -> "bloom.settings.menu_scale." + s.name().toLowerCase(java.util.Locale.ROOT),
				"bloom.settings.menu_scale").describe("bloom.settings.menu_scale.desc");
		blur = group.bool("blur", true, "bloom.settings.blur").describe("bloom.settings.blur.desc");
		glass = group.bool("glass", true, "bloom.settings.glass").describe("bloom.settings.glass.desc");
		smoothFont = group.bool("smoothFont", true, "bloom.settings.smooth_font").describe("bloom.settings.smooth_font.desc");
		openFromMenus = group.bool("openFromMenus", true, "bloom.settings.open_from_menus").describe("bloom.settings.open_from_menus.desc");
		tips = group.bool("tips", true, "bloom.settings.tips").describe("bloom.settings.tips.desc");
		group.section("bloom.settings.section.hud");
		hudEnabled = group.bool("hudEnabled", true, "bloom.settings.hud_enabled").describe("bloom.settings.hud_enabled.desc");
		hideHudWithDebug = group.bool("hideHudWithDebug", true, "bloom.settings.hide_with_debug").describe("bloom.settings.hide_with_debug.desc");
		group.section("bloom.settings.section.studio");
		gridSize = group.integer("gridSize", 4, 2, 32, 1, " px", "bloom.settings.grid").describe("bloom.settings.grid.desc");
		snap = group.bool("snap", true, "bloom.settings.snap").describe("bloom.settings.snap.desc");
		guides = group.bool("guides", true, "bloom.settings.guides").describe("bloom.settings.guides.desc");
	}
}
