package com.bloomclient.client.ui.screen;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.api.ModMetadata;
import com.bloomclient.client.api.ModPage;
import com.bloomclient.client.api.settings.Setting;
import com.bloomclient.client.core.ModContainer;
import com.bloomclient.client.hud.HudManager;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.client.ui.Ui;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Search across mods, their pages and settings, Bloom settings and HUD widgets. */
final class SearchPage extends MenuPage {
	private record Result(String group, Icon icon, String title, String subtitle, Runnable action) {
	}

	private String lastQuery = "";
	private final List<Result> results = new ArrayList<>();

	SearchPage(BloomMenuScreen menu) {
		super(menu);
	}

	@Override
	String title() {
		return Tr.t("bloom.nav.search");
	}

	@Override
	String subtitle() {
		String q = menu.search.value().trim();
		return q.isEmpty() ? Tr.t("bloom.search.subtitle") : Tr.t("bloom.search.count", results.size(), q);
	}

	private static boolean matches(String q, String... fields) {
		for (String f : fields) {
			if (f != null && f.toLowerCase(Locale.ROOT).contains(q)) return true;
		}
		return false;
	}

	private void rebuild(String q) {
		results.clear();
		if (q.isEmpty()) return;
		String gMods = Tr.t("bloom.search.group.mods"), gSettings = Tr.t("bloom.search.group.settings"), gHud = Tr.t("bloom.search.group.hud");
		for (ModContainer c : BloomClient.mods().all()) {
			ModMetadata m = c.metadata();
			if (matches(q, m.displayName(), m.displayDescription(), m.id())) {
				results.add(new Result(gMods, m.icon(), m.displayName(), m.displayDescription(), () -> menu.showMod(c, "", null)));
			}
			for (ModPage p : c.pages()) {
				String title = Tr.handles(p.title()) ? Tr.t(p.title()) : p.title();
				if (matches(q, title)) {
					results.add(new Result(gMods, p.icon(), title, m.displayName(), () -> menu.showMod(c, p.id(), null)));
				}
			}
			for (Setting<?> s : c.settings().all()) {
				if (matches(q, s.label(), s.description(), s.section())) {
					results.add(new Result(gSettings, Icon.SETTINGS, s.label(), m.displayName() + (s.section() == null ? "" : " › " + s.section()),
							() -> menu.showMod(c, ModsPage.TAB_SETTINGS, s.key())));
				}
			}
		}
		for (Setting<?> s : BloomClient.settings().group.all()) {
			if (matches(q, s.label(), s.description(), s.section())) {
				results.add(new Result(gSettings, Icon.SETTINGS, s.label(), "Bloom" + (s.section() == null ? "" : " › " + s.section()),
						() -> menu.showBloomSetting(s.key())));
			}
		}
		for (HudManager.Registered r : BloomClient.hud().widgets()) {
			if (matches(q, r.widget().displayName(), r.widget().displayDescription())) {
				results.add(new Result(gHud, r.widget().icon(), r.widget().displayName(), r.ownerName() + " · " + r.widget().displayDescription(),
						menu::openStudio));
			}
		}
		if (matches(q, Tr.t("bloom.hud.studio"), "hud")) {
			results.add(new Result(gHud, Icon.HUD, Tr.t("bloom.hud.studio"), Tr.t("bloom.hud.subtitle"), menu::openStudio));
		}
	}

	@Override
	void render(Ui ui, int x, int y, int w, int h) {
		String q = menu.search.value().trim().toLowerCase(Locale.ROOT);
		if (!q.equals(lastQuery)) {
			lastQuery = q;
			rebuild(q);
			ui.resetScroll("search");
		}
		if (q.isEmpty()) {
			ui.textCentered(Tr.t("bloom.search.hint"), x + w / 2, y + 40, BloomTheme.TEXT_MUTED);
			return;
		}
		if (results.isEmpty()) {
			ui.textCentered(Tr.t("bloom.search.none", menu.search.value().trim()), x + w / 2, y + 40, BloomTheme.TEXT_MUTED);
			return;
		}
		int off = ui.beginScroll("search", x, y, w, h);
		int cy = y - off;
		String group = null;
		Runnable chosen = null;
		for (int i = 0; i < results.size(); i++) {
			Result r = results.get(i);
			if (!r.group.equals(group)) {
				group = r.group;
				ui.text(group.toUpperCase(Locale.ROOT), x, cy + 4, BloomTheme.TEXT_MUTED);
				cy += 16;
			}
			boolean hov = ui.hovered(x, cy, w - 8, 28);
			float hv = ui.anim("search." + i, hov, 10);
			ui.round(x, cy, w - 8, 28, 4, BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_2, hv));
			ui.icon(r.icon, x + 9, cy + 9, BloomTheme.ACCENT);
			ui.text(ui.ellipsize(r.title, w - 60), x + 26, cy + 5, BloomTheme.TEXT);
			ui.text(ui.ellipsize(r.subtitle, w - 60), x + 26, cy + 16, BloomTheme.TEXT_MUTED);
			ui.icon(Icon.RIGHT, x + w - 24, cy + 9, hov ? BloomTheme.ACCENT : BloomTheme.TEXT_MUTED);
			if (ui.clicked(x, cy, w - 8, 28)) chosen = r.action;
			cy += 31;
		}
		ui.endScroll("search", x, y, w, h, cy + off - y);
		if (chosen != null) chosen.run();
	}
}
