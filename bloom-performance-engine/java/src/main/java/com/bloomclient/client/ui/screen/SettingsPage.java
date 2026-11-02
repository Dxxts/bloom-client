package com.bloomclient.client.ui.screen;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.BloomKeys;
import com.bloomclient.client.core.BloomStorage;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.SettingsView;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.client.ui.Ui;
import com.bloomclient.engine.BloomEngine;
import java.util.Locale;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import org.jspecify.annotations.Nullable;

/** Bloom's settings, key bindings, rendering engine status and data management. */
final class SettingsPage extends MenuPage {
	@Nullable String highlight;

	SettingsPage(BloomMenuScreen menu) {
		super(menu);
	}

	@Override
	String title() {
		return Tr.t("bloom.nav.settings");
	}

	@Override
	String subtitle() {
		return Tr.t("bloom.settings.subtitle");
	}

	@Override
	void render(Ui ui, int x, int y, int w, int h) {
		int off = ui.beginScroll("settings", x, y, w, h);
		int cy = y - off;
		int cw = w - 8;
		cy += SettingsView.render(ui, "settings", BloomClient.settings().group, x, cy, cw, highlight) + 10;

		cy = header(ui, Tr.t("bloom.settings.section.keys"), x, cy);
		cy = keyRow(ui, BloomKeys.OPEN_MENU, x, cy, cw);
		cy = keyRow(ui, BloomKeys.OPEN_STUDIO, x, cy, cw) + 6;

		cy = header(ui, Tr.t("bloom.settings.section.engine"), x, cy);
		ui.round(x, cy, cw, 34, 4, BloomTheme.SURFACE);
		ui.text(BloomClient.engineStatus(), x + 9, cy + 7, BloomEngine.active() ? BloomTheme.ACCENT : BloomTheme.TEXT);
		ui.text(ui.ellipsize(Tr.t("bloom.settings.engine_hint"), cw - 18), x + 9, cy + 19, BloomTheme.TEXT_MUTED);
		cy += 40;

		cy = header(ui, Tr.t("bloom.settings.section.data"), x, cy);
		cy = pathRow(ui, "settings.cfg", Tr.t("bloom.settings.config_dir"), BloomStorage.configDir().toAbsolutePath().toString(), x, cy, cw);
		cy = pathRow(ui, "settings.data", Tr.t("bloom.settings.data_dir"), BloomStorage.dataDir().toAbsolutePath().toString(), x, cy, cw);
		cy += 4;
		if (ui.button("settings.resethud", x, cy, 140, 18, Tr.t("bloom.settings.reset_hud"), Icon.RESET, Ui.Kind.DANGER, true)) {
			menu.confirm(Tr.t("bloom.settings.reset_hud_confirm"), () -> {
				BloomClient.hud().resetToDefault();
				menu.toast(Tr.t("bloom.settings.reset_hud_done"));
			});
		}
		if (ui.button("settings.resetbloom", x + 148, cy, 140, 18, Tr.t("bloom.settings.reset_bloom"), Icon.RESET, Ui.Kind.DANGER, true)) {
			menu.confirm(Tr.t("bloom.settings.reset_bloom_confirm"), () -> BloomClient.settings().group.resetAll());
		}
		cy += 26;
		ui.endScroll("settings", x, y, w, h, cy + off - y);
	}

	private static int header(Ui ui, String text, int x, int y) {
		ui.text(text.toUpperCase(Locale.ROOT), x, y + 2, BloomTheme.TEXT_MUTED);
		return y + 14;
	}

	private int keyRow(Ui ui, KeyMapping key, int x, int y, int w) {
		ui.round(x, y, w, 24, 4, BloomTheme.SURFACE);
		ui.icon(Icon.KEYBOARD, x + 8, y + 8, BloomTheme.TEXT_MUTED);
		ui.text(Tr.t(key.getName()), x + 22, y + 8, BloomTheme.TEXT);
		String bound = key.isUnbound() ? Tr.t("bloom.settings.unbound") : key.getTranslatedKeyMessage().getString();
		int kw = ui.width(bound) + 12;
		int bx = x + w - 96;
		ui.round(bx - kw - 8, y + 5, kw, 14, 3, BloomTheme.SURFACE_ACTIVE);
		ui.text(bound, bx - kw - 2, y + 8, key.isUnbound() ? BloomTheme.TEXT_MUTED : BloomTheme.ACCENT);
		if (ui.button("settings.key." + key.getName(), bx, y + 4, 88, 16, Tr.t("bloom.settings.change_key"), Ui.Kind.SECONDARY)) {
			ui.mc.gui.setScreen(new KeyBindsScreen(menu, ui.mc.options));
		}
		return y + 28;
	}

	private int pathRow(Ui ui, String id, String label, String path, int x, int y, int w) {
		ui.round(x, y, w, 24, 4, BloomTheme.SURFACE);
		ui.icon(Icon.FOLDER, x + 8, y + 8, BloomTheme.TEXT_MUTED);
		ui.text(label, x + 22, y + 8, BloomTheme.TEXT);
		String shown = ui.ellipsize(path, w - 220);
		ui.text(shown, x + 120, y + 8, BloomTheme.TEXT_MUTED);
		if (!shown.equals(path) && ui.hovered(x + 120, y, w - 220, 24)) ui.tooltip(path);
		if (ui.iconButton(id + ".copy", x + w - 22, y + 4, 16, Icon.COPY, Tr.t("bloom.common.copy_path"))) {
			ui.mc.keyboardHandler.setClipboard(path);
			menu.toast(Tr.t("bloom.common.copied"));
		}
		return y + 28;
	}
}
