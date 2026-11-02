package com.bloomclient.client.ui.screen;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.api.ModCategory;
import com.bloomclient.client.api.ModMetadata;
import com.bloomclient.client.api.ModPage;
import com.bloomclient.client.api.hud.HudWidget;
import com.bloomclient.client.core.ModContainer;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.SettingsView;
import com.bloomclient.client.ui.TextInput;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.client.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** Mods: list with search and filters, and each mod's detail view (pages, settings, info). */
final class ModsPage extends MenuPage {
	static final String TAB_SETTINGS = "settings";
	static final String TAB_INFO = "info";

	private final TextInput filter = new TextInput().maxLength(48);
	private @Nullable ModCategory category;
	private int stateFilter;

	ModsPage(BloomMenuScreen menu) {
		super(menu);
	}

	@Override
	String title() {
		return menu.detailMod == null ? Tr.t("bloom.nav.mods") : menu.detailMod.metadata().displayName();
	}

	@Override
	String subtitle() {
		if (menu.detailMod == null) {
			return Tr.t("bloom.mods.subtitle", BloomClient.mods().enabledCount(), BloomClient.mods().all().size());
		}
		ModMetadata m = menu.detailMod.metadata();
		return "v" + m.version() + " · " + m.author() + " · " + Tr.t(m.category().translationKey);
	}

	@Override
	void render(Ui ui, int x, int y, int w, int h) {
		if (menu.detailMod != null) {
			detail(ui, menu.detailMod, x, y, w, h);
			return;
		}
		menu.activeModPage(null);
		list(ui, x, y, w, h);
	}

	// ---------------------------------------------------------------- list

	/** Category chips only help once there are enough mods to need them. */
	private static final int CATEGORY_CHIPS_FROM = 6;
	private static final int ROW_H = 54, ROW_GAP = 6;

	private void list(Ui ui, int x, int y, int w, int h) {
		// Toolbar: search on the left, state filter on the right.
		String[] states = {Tr.t("bloom.mods.state.all"), Tr.t("bloom.mods.state.on"), Tr.t("bloom.mods.state.off")};
		int segW = 126;
		ui.textField("mods.filter", x, y, Math.min(200, w - segW - 12), 18, filter, Tr.t("bloom.mods.search"), Icon.SEARCH);
		stateFilter = ui.segmented("mods.state", x + w - segW, y + 1, segW, 16, states, stateFilter);
		int listY = y + 28;
		if (BloomClient.mods().all().size() >= CATEGORY_CHIPS_FROM) {
			int chipX = chip(ui, "mods.cat.all", x, listY, Tr.t("bloom.mods.all"), Icon.MODS, category == null, () -> category = null);
			for (ModCategory c : ModCategory.values()) {
				if (BloomClient.mods().inCategory(c).isEmpty()) continue;
				ModCategory cc = c;
				chipX = chip(ui, "mods.cat." + c, chipX, listY, Tr.t(c.translationKey), c.icon, category == c, () -> category = cc);
			}
			listY += 24;
		} else {
			category = null;
		}

		String q = filter.value().trim().toLowerCase(Locale.ROOT);
		List<ModContainer> shown = new ArrayList<>();
		for (ModContainer c : BloomClient.mods().inCategory(category)) {
			if (stateFilter == 1 && !c.isEnabled()) continue;
			if (stateFilter == 2 && c.isEnabled()) continue;
			if (!q.isEmpty()) {
				ModMetadata m = c.metadata();
				String hay = (m.displayName() + " " + m.displayDescription() + " " + m.id()).toLowerCase(Locale.ROOT);
				if (!hay.contains(q)) continue;
			}
			shown.add(c);
		}
		int listH = y + h - listY;
		int off = ui.beginScroll("mods.list", x, listY, w, listH);
		int cy = listY - off;
		int rowW = w - 6;
		for (ModContainer c : shown) {
			row(ui, c, x, cy, rowW, ROW_H);
			cy += ROW_H + ROW_GAP;
		}
		if (shown.isEmpty()) {
			ui.textCentered(Tr.t("bloom.mods.none"), x + w / 2, cy + 24, BloomTheme.TEXT_MUTED);
			cy += 50;
		}
		if (BloomClient.settings().tips.on()) {
			// One quiet line under the list instead of a banner above it.
			cy += 4;
			ui.icon(Icon.INFO, x + 2, cy, BloomTheme.TEXT_MUTED);
			int used = ui.paragraph(Tr.t("bloom.mods.hint"), x + 16, cy + 1, rowW - 40, BloomTheme.TEXT_MUTED);
			if (ui.iconButton("mods.hint.close", x + rowW - 16, cy - 3, 14, Icon.CLOSE, Tr.t("bloom.common.hide_tips"))) {
				BloomClient.settings().tips.toggle();
			}
			cy += used + 4;
		}
		ui.endScroll("mods.list", x, listY, w, listH, cy + off - listY);
	}

	private int chip(Ui ui, String id, int x, int y, String label, Icon icon, boolean selected, Runnable onClick) {
		int w = ui.width(label) + 14 + Icon.SIZE + 4;
		boolean hov = ui.hovered(x, y + 1, w, 16);
		float hv = ui.anim(id, hov, 10);
		ui.round(x, y + 1, w, 16, 8, selected ? BloomTheme.ACCENT_SOFT : BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_HOVER, hv));
		int color = selected ? BloomTheme.ACCENT : BloomTheme.TEXT_DIM;
		ui.icon(icon, x + 7, y + 4, color);
		ui.text(label, x + 7 + Icon.SIZE + 4, y + 5, color);
		if (ui.clicked(x, y + 1, w, 16)) onClick.run();
		return x + w + 5;
	}

	/**
	 * State text and switch, right-aligned at {@code right}: the text says what
	 * the switch means (on, off, error) and is clickable too.
	 */
	private void powerSwitch(Ui ui, String id, ModContainer c, int right, int y) {
		boolean failed = c.state() == ModContainer.State.FAILED;
		boolean error = failed || (!c.isEnabled() && c.failure() != null);
		String label = Tr.t(error ? "bloom.mods.badge.error" : c.isEnabled() ? "bloom.mods.badge.on" : "bloom.mods.badge.off");
		int color = error ? BloomTheme.DANGER : c.isEnabled() ? BloomTheme.ACCENT : BloomTheme.TEXT_MUTED;
		int tx = right - 22 - 6 - ui.width(label);
		ui.text(label, tx, y + 2, color);
		boolean clicked = ui.toggle(id, right - 22, y, c.isEnabled(), !failed);
		if (!failed && ui.clicked(tx - 2, y - 2, right - 22 - tx, 16)) clicked = true;
		if (!failed && ui.hovered(tx - 2, y - 2, right - tx + 2, 16)) {
			ui.tooltip(Tr.t(c.isEnabled() ? "bloom.mods.switch.off" : "bloom.mods.switch.on"));
		}
		if (clicked && !BloomClient.mods().setEnabled(c, !c.isEnabled())) {
			menu.toast(Tr.t("bloom.mods.enable_failed", c.metadata().displayName()), BloomTheme.DANGER);
		}
	}

	/**
	 * One mod per row: icon, name, one line of description, a quiet summary of
	 * what it adds; on the right only the on/off switch and a chevron. The whole
	 * row opens the mod (its page, settings and info live in the detail view).
	 */
	private void row(Ui ui, ModContainer c, int x, int y, int w, int h) {
		ModMetadata m = c.metadata();
		String id = "mod." + m.id();
		boolean on = c.isEnabled();
		boolean hov = ui.hovered(x, y, w, h);
		float hv = ui.anim(id, hov, 9);
		ui.round(x, y, w, h, 6, BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_2, hv));
		ui.outline(x, y, w, h, 6, BloomTheme.lerp(BloomTheme.BORDER, BloomTheme.BORDER_STRONG, hv));
		// Icon
		int iy = y + (h - 30) / 2;
		ui.round(x + 12, iy, 30, 30, 7, on ? BloomTheme.ACCENT_SOFT : 0x14FFFFFF);
		m.icon().draw(ui.g, x + 18, iy + 6, 2, ui.c(on ? BloomTheme.ACCENT : BloomTheme.TEXT_MUTED));
		// Right side: chevron, then the switch
		int right = x + w - 12;
		ui.icon(Icon.RIGHT, right - Icon.SIZE, y + (h - Icon.SIZE) / 2, BloomTheme.lerp(BloomTheme.TEXT_MUTED, BloomTheme.ACCENT, hv));
		int switchRight = right - Icon.SIZE - 14;
		powerSwitch(ui, id + ".toggle", c, switchRight, y + (h - 12) / 2);
		int textW = switchRight - 22 - 6 - ui.width(Tr.t("bloom.mods.badge.off")) - 16 - (x + 54);
		// Text
		int tx = x + 54;
		ui.textBold(ui.ellipsize(m.displayName(), textW), tx, y + 10, on ? BloomTheme.TEXT : BloomTheme.TEXT_DIM);
		ui.text(ui.ellipsize(m.displayDescription(), textW), tx, y + 23, BloomTheme.TEXT_DIM);
		StringBuilder meta = new StringBuilder(Tr.t(m.category().translationKey));
		appendCount(meta, c.pages().size(), "bloom.mods.n.pages");
		appendCount(meta, c.widgets().size(), "bloom.mods.n.widgets");
		appendCount(meta, c.settings().all().size(), "bloom.mods.n.settings");
		ui.text(ui.ellipsize(meta.toString(), textW), tx, y + 36, BloomTheme.TEXT_MUTED);
		if (hov && ui.hovered(tx, y + 20, textW, 12) && ui.width(m.displayDescription()) > textW) ui.tooltip(m.displayDescription());
		if (ui.clicked(x, y, w, h)) {
			menu.showMod(c, c.pages().isEmpty() || !on ? (c.settings().isEmpty() ? TAB_INFO : TAB_SETTINGS) : c.pages().getFirst().id(), null);
		}
	}

	/** Appends " · 4 HUD widgets" (singular or plural form); nothing for zero. */
	private static void appendCount(StringBuilder sb, int n, String key) {
		if (n == 0) return;
		sb.append(" \u00b7 ").append(Tr.t(key + (n == 1 ? ".one" : ".many"), n));
	}

	// ---------------------------------------------------------------- detail

	private void detail(Ui ui, ModContainer c, int x, int y, int w, int h) {
		ModMetadata m = c.metadata();
		// Back + toggle row
		if (ui.button("mods.back", x, y, 64, 16, Tr.t("bloom.common.back"), Icon.LEFT, Ui.Kind.GHOST, true)) {
			menu.showMod(null, "", null);
			return;
		}
		boolean failed = c.state() == ModContainer.State.FAILED;
		powerSwitch(ui, "mods.detail.toggle", c, x + w - 4, y + 2);
		if (!c.widgets().isEmpty()) {
			String add = Tr.t("bloom.mods.add_widgets_short");
			int aw = ui.width(add) + 26;
			int ax = x + w - 4 - 22 - 6 - ui.width(Tr.t("bloom.mods.badge.off")) - 12 - aw;
			if (ui.button("mods.detail.widgets", ax, y, aw, 16, add, Icon.HUD, Ui.Kind.SECONDARY, c.isEnabled())) menu.openStudio(m.displayName());
			if (ui.hovered(ax, y, aw, 16)) ui.tooltip(Tr.t(c.isEnabled() ? "bloom.mods.add_widgets" : "bloom.mods.enable_first"));
		}
		int ty = y + 24;
		if (c.failure() != null && !c.isEnabled()) {
			ui.round(x, ty, w, 20, 4, BloomTheme.DANGER_SOFT);
			ui.icon(Icon.WARNING, x + 7, ty + 6, BloomTheme.DANGER);
			ui.text(ui.ellipsize(Tr.t("bloom.mods.failure", c.failure()), w - 30), x + 22, ty + 6, BloomTheme.DANGER);
			ty += 26;
		}
		// Tabs
		List<String> tabIds = new ArrayList<>();
		List<String> tabNames = new ArrayList<>();
		List<Icon> tabIcons = new ArrayList<>();
		for (ModPage p : c.pages()) {
			tabIds.add(p.id());
			tabNames.add(Tr.handles(p.title()) ? Tr.t(p.title()) : p.title());
			tabIcons.add(p.icon());
		}
		if (!c.settings().isEmpty()) {
			tabIds.add(TAB_SETTINGS);
			tabNames.add(Tr.t("bloom.mods.settings"));
			tabIcons.add(Icon.SETTINGS);
		}
		tabIds.add(TAB_INFO);
		tabNames.add(Tr.t("bloom.mods.info"));
		tabIcons.add(Icon.INFO);
		if (!tabIds.contains(menu.detailTab)) menu.detailTab = tabIds.getFirst();
		int tx = x;
		for (int i = 0; i < tabIds.size(); i++) {
			String tid = tabIds.get(i);
			boolean sel = tid.equals(menu.detailTab);
			int tw = ui.width(tabNames.get(i)) + 26;
			boolean hov = ui.hovered(tx, ty, tw, 18);
			int color = sel ? BloomTheme.ACCENT : hov ? BloomTheme.TEXT : BloomTheme.TEXT_DIM;
			ui.icon(tabIcons.get(i), tx + 4, ty + 4, color);
			ui.text(tabNames.get(i), tx + 18, ty + 5, color);
			if (sel) ui.rect(tx, ty + 17, tw, 2, BloomTheme.ACCENT);
			if (ui.clicked(tx, ty, tw, 18)) menu.selectTab(tid);
			tx += tw + 6;
		}
		ui.rect(x, ty + 19, w, 1, BloomTheme.BORDER);
		int cy = ty + 26, chh = y + h - cy;
		String tab = menu.detailTab;
		if (tab.equals(TAB_SETTINGS)) {
			menu.activeModPage(null);
			int off = ui.beginScroll("mods.settings." + m.id(), x, cy, w, chh);
			int used = SettingsView.render(ui, "mods." + m.id(), c.settings(), x, cy - off, w - 8, menu.highlightSetting);
			used += 8;
			if (ui.button("mods.resetall", x, cy - off + used, 130, 16, Tr.t("bloom.mods.reset_settings"), Icon.RESET, Ui.Kind.GHOST, true)) {
				menu.confirm(Tr.t("bloom.mods.reset_confirm", m.displayName()), () -> c.settings().resetAll());
			}
			ui.endScroll("mods.settings." + m.id(), x, cy, w, chh, used + 24);
		} else if (tab.equals(TAB_INFO)) {
			menu.activeModPage(null);
			info(ui, c, x, cy, w, chh);
		} else {
			ModPage page = null;
			for (ModPage p : c.pages()) if (p.id().equals(tab)) page = p;
			if (page == null) return;
			if (!c.isEnabled()) {
				menu.activeModPage(null);
				ui.textCentered(Tr.t("bloom.mods.enable_first"), x + w / 2, cy + 30, BloomTheme.TEXT_MUTED);
				if (ui.button("mods.enable", x + w / 2 - 50, cy + 46, 100, 18, Tr.t("bloom.mods.enable"), Ui.Kind.PRIMARY) && !failed) {
					BloomClient.mods().setEnabled(c, true);
				}
				return;
			}
			menu.activeModPage(page);
			ModPage fp = page;
			c.guard("page " + page.id(), () -> fp.render(ui, x, cy, w, chh));
		}
	}

	private void info(Ui ui, ModContainer c, int x, int y, int w, int h) {
		ModMetadata m = c.metadata();
		int off = ui.beginScroll("mods.info." + m.id(), x, y, w, h);
		int cy = y - off;
		cy += ui.paragraph(m.displayDescription(), x, cy, w - 10, BloomTheme.TEXT_DIM) + 8;
		cy = kv(ui, x, cy, w, Tr.t("bloom.mods.info.id"), m.id());
		cy = kv(ui, x, cy, w, Tr.t("bloom.mods.info.version"), m.version());
		cy = kv(ui, x, cy, w, Tr.t("bloom.mods.info.author"), m.author());
		cy = kv(ui, x, cy, w, Tr.t("bloom.mods.info.category"), Tr.t(m.category().translationKey));
		cy = kv(ui, x, cy, w, Tr.t("bloom.mods.info.state"), Tr.t("bloom.mods.state." + c.state().name().toLowerCase(Locale.ROOT)));
		cy += 8;
		if (!c.widgets().isEmpty()) {
			ui.text(Tr.t("bloom.mods.info.widgets").toUpperCase(Locale.ROOT), x, cy, BloomTheme.TEXT_MUTED);
			cy += 13;
			for (HudWidget wd : c.widgets()) {
				ui.icon(wd.icon(), x, cy, BloomTheme.ACCENT);
				ui.text(wd.displayName(), x + 14, cy + 1, BloomTheme.TEXT);
				ui.text(ui.ellipsize(wd.displayDescription(), w - 150), x + 140, cy + 1, BloomTheme.TEXT_MUTED);
				cy += 13;
			}
			cy += 6;
		}
		ui.text(Tr.t("bloom.mods.info.errors", c.totalErrors()).toUpperCase(Locale.ROOT), x, cy, BloomTheme.TEXT_MUTED);
		cy += 13;
		if (c.errors().isEmpty()) {
			ui.text(Tr.t("bloom.mods.info.no_errors"), x, cy, BloomTheme.TEXT_DIM);
			cy += 12;
		}
		for (int i = c.errors().size() - 1; i >= 0; i--) {
			ModContainer.ModError e = c.errors().get(i);
			ui.text(e.time(), x, cy, BloomTheme.TEXT_MUTED);
			ui.text(ui.ellipsize(e.where() + ": " + e.message(), w - 60), x + 48, cy, BloomTheme.DANGER);
			if (ui.hovered(x, cy - 1, w, 11)) ui.tooltip(e.where() + "\n" + e.message());
			cy += 12;
		}
		ui.endScroll("mods.info." + m.id(), x, y, w, h, cy + off - y + 6);
	}

	private static int kv(Ui ui, int x, int y, int w, String k, String v) {
		ui.text(k, x, y, BloomTheme.TEXT_MUTED);
		ui.text(ui.ellipsize(v, w - 110), x + 100, y, BloomTheme.TEXT);
		return y + 12;
	}
}
