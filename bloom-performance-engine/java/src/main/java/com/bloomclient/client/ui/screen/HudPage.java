package com.bloomclient.client.ui.screen;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.api.hud.HudElement;
import com.bloomclient.client.hud.HudManager;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Draw;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.client.ui.Ui;
import java.util.List;

/** HUD section of the menu: entry to the HUD Studio, quick visibility of placed elements, available widgets. */
final class HudPage extends MenuPage {
	HudPage(BloomMenuScreen menu) {
		super(menu);
	}

	@Override
	String title() {
		return Tr.t("bloom.nav.hud");
	}

	@Override
	String subtitle() {
		return Tr.t("bloom.hud.subtitle");
	}

	@Override
	void render(Ui ui, int x, int y, int w, int h) {
		HudManager hud = BloomClient.hud();
		boolean inWorld = ui.mc.level != null;
		// Studio card
		int cardH = 58;
		ui.round(x, y, w, cardH, 6, BloomTheme.SURFACE);
		Draw.hGradient(ui.g, x + 1, y + 1, Math.min(w - 2, 200), cardH - 2, ui.c(0x285CE1A8), ui.c(0x005CE1A8), 20);
		ui.outline(x, y, w, cardH, 6, BloomTheme.BORDER);
		ui.round(x + 12, y + 14, 30, 30, 6, BloomTheme.ACCENT_SOFT);
		Icon.HUD.draw(ui.g, x + 18, y + 20, 2, ui.c(BloomTheme.ACCENT));
		ui.text(Tr.t("bloom.hud.studio"), x + 52, y + 15, BloomTheme.TEXT);
		ui.paragraph(Tr.t(inWorld ? "bloom.hud.studio.desc" : "bloom.hud.studio.need_world"), x + 52, y + 27, w - 200, BloomTheme.TEXT_DIM);
		if (ui.button("hud.open", x + w - 128, y + 19, 116, 20, Tr.t("bloom.hud.open_studio"), Icon.EDIT, Ui.Kind.PRIMARY, inWorld)) {
			menu.openStudio();
		}

		int cy = y + cardH + 10;
		// Master switch
		ui.round(x, cy, w, 24, 5, BloomTheme.SURFACE);
		ui.text(Tr.t("bloom.settings.hud_enabled"), x + 9, cy + 8, BloomTheme.TEXT);
		if (ui.toggle("hud.master", x + w - 32, cy + 6, BloomClient.settings().hudEnabled.on())) BloomClient.settings().hudEnabled.toggle();
		cy += 32;

		int listH = y + h - cy;
		int colW = (w - 10) / 2;
		// Placed elements
		ui.text(Tr.t("bloom.hud.placed", hud.elements().size()).toUpperCase(java.util.Locale.ROOT), x, cy, BloomTheme.TEXT_MUTED);
		ui.text(Tr.t("bloom.hud.available").toUpperCase(java.util.Locale.ROOT), x + colW + 10, cy, BloomTheme.TEXT_MUTED);
		int ly = cy + 13, lh = listH - 13;
		int off = ui.beginScroll("hud.placed", x, ly, colW, lh);
		List<HudElement> elements = hud.elements();
		int ry = ly - off;
		for (HudElement e : elements) {
			HudManager.Registered r = hud.widget(e.widget);
			boolean known = r != null;
			boolean hov = ui.hovered(x, ry, colW - 6, 20);
			ui.round(x, ry, colW - 6, 20, 4, hov ? BloomTheme.SURFACE_2 : BloomTheme.SURFACE);
			ui.icon(known ? r.widget().icon() : Icon.WARNING, x + 6, ry + 6, known && r.enabled() ? BloomTheme.ACCENT : BloomTheme.TEXT_MUTED);
			String name = known ? r.widget().displayName() : Tr.t("bloom.hud.missing_widget", e.widget);
			ui.text(ui.ellipsize(name, colW - 60), x + 20, ry + 6, e.visible ? BloomTheme.TEXT : BloomTheme.TEXT_MUTED);
			if (known && !r.enabled() && hov) ui.tooltip(Tr.t("bloom.hud.owner_disabled", r.ownerName()));
			if (ui.iconButton("hud.vis." + e.id, x + colW - 26, ry + 2, 16, e.visible ? Icon.EYE : Icon.EYE_OFF,
					Tr.t(e.visible ? "bloom.hud.hide" : "bloom.hud.show"))) {
				e.visible = !e.visible;
				hud.markDirty();
			}
			ry += 23;
		}
		ui.endScroll("hud.placed", x, ly, colW, lh, elements.size() * 23);

		int ax = x + colW + 10;
		int off2 = ui.beginScroll("hud.available", ax, ly, colW, lh);
		int ay = ly - off2;
		List<HudManager.Registered> widgets = hud.widgets();
		for (HudManager.Registered r : widgets) {
			ui.round(ax, ay, colW - 6, 30, 4, BloomTheme.SURFACE);
			ui.icon(r.widget().icon(), ax + 6, ay + 6, r.enabled() ? BloomTheme.ACCENT : BloomTheme.TEXT_MUTED);
			ui.text(ui.ellipsize(r.widget().displayName(), colW - 90), ax + 20, ay + 6, BloomTheme.TEXT);
			ui.textRight(ui.ellipsize(r.ownerName(), 70), ax + colW - 12, ay + 6, BloomTheme.TEXT_MUTED);
			ui.text(ui.ellipsize(r.widget().displayDescription(), colW - 28), ax + 20, ay + 17, BloomTheme.TEXT_MUTED);
			ay += 33;
		}
		ui.endScroll("hud.available", ax, ly, colW, lh, widgets.size() * 33);
	}
}
