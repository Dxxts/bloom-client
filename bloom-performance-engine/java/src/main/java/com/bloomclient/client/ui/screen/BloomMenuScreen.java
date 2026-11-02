package com.bloomclient.client.ui.screen;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.BloomKeys;
import com.bloomclient.client.api.ModPage;
import com.bloomclient.client.core.ModContainer;
import com.bloomclient.client.ui.BloomScreen;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Draw;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.TextInput;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.client.ui.Ui;
import com.bloomclient.engine.BloomEngine;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Bloom Menu (right shift): sidebar navigation with Home, Mods, HUD
 * Studio, Settings and Search, and a dynamic content area that also hosts
 * the mods' own pages.
 */
public final class BloomMenuScreen extends BloomScreen {
	enum Section {
		HOME(Icon.HOME, "bloom.nav.home"),
		MODS(Icon.MODS, "bloom.nav.mods"),
		HUD(Icon.HUD, "bloom.nav.hud"),
		SETTINGS(Icon.SETTINGS, "bloom.nav.settings"),
		SEARCH(Icon.SEARCH, "bloom.nav.search");

		final Icon icon;
		final String key;

		Section(Icon icon, String key) {
			this.icon = icon;
			this.key = key;
		}
	}

	private static final int SIDEBAR_W = 138;
	private static Section lastSection = Section.HOME;

	private final Map<Section, MenuPage> pages = new EnumMap<>(Section.class);
	private Section section = lastSection;
	private long sectionChangedAt;
	final TextInput search = new TextInput().maxLength(64);

	// Mod detail navigation (inside the Mods section)
	@Nullable ModContainer detailMod;
	String detailTab = "";
	@Nullable String highlightSetting;
	private @Nullable ModPage openModPage;

	private BloomMenuScreen(@Nullable Screen parent) {
		super(Component.translatable("bloom.menu.title"), parent);
		pages.put(Section.HOME, new HomePage(this));
		pages.put(Section.MODS, new ModsPage(this));
		pages.put(Section.HUD, new HudPage(this));
		pages.put(Section.SETTINGS, new SettingsPage(this));
		pages.put(Section.SEARCH, new SearchPage(this));
		if (section == Section.SEARCH) section = Section.HOME;
	}

	/** Opens the menu over {@code parent} (null = from gameplay). */
	public static void open(@Nullable Screen parent) {
		Minecraft.getInstance().gui.setScreen(new BloomMenuScreen(parent));
	}

	/** Opens the menu on a section by name (home, mods, hud, settings); used by the development preview. */
	public static void openSection(String name) {
		BloomMenuScreen s = new BloomMenuScreen(null);
		for (Section sec : Section.values()) {
			if (sec.name().equalsIgnoreCase(name)) s.section = sec;
		}
		Minecraft.getInstance().gui.setScreen(s);
	}

	/** Opens the menu directly on a mod's page or settings. */
	public static void openMod(@Nullable Screen parent, ModContainer mod, String tab) {
		BloomMenuScreen s = new BloomMenuScreen(parent);
		s.section = Section.MODS;
		s.detailMod = mod;
		s.detailTab = tab;
		Minecraft.getInstance().gui.setScreen(s);
	}

	// ---------------------------------------------------------------- navigation

	void go(Section target) {
		if (target != section) {
			closeModPage();
			section = target;
			sectionChangedAt = System.currentTimeMillis();
			if (target != Section.SEARCH) lastSection = target;
			pages.get(target).onShow();
		}
		if (target != Section.MODS) {
			detailMod = null;
		}
		ui.closePopups();
	}

	void showMod(@Nullable ModContainer mod, String tab, @Nullable String highlight) {
		go(Section.MODS);
		closeModPage();
		detailMod = mod;
		detailTab = tab;
		highlightSetting = highlight;
		sectionChangedAt = System.currentTimeMillis();
	}

	void showBloomSetting(String key) {
		((SettingsPage) pages.get(Section.SETTINGS)).highlight = key;
		go(Section.SETTINGS);
	}

	void selectTab(String tab) {
		if (!tab.equals(detailTab)) {
			closeModPage();
			detailTab = tab;
			highlightSetting = null;
			sectionChangedAt = System.currentTimeMillis();
		}
	}

	/** Called by the Mods page when it draws a mod page, to deliver open/close callbacks and keys. */
	void activeModPage(@Nullable ModPage page) {
		if (page != openModPage) {
			closeModPage();
			openModPage = page;
			if (page != null && detailMod != null) {
				ModPage p = page;
				detailMod.guard("page " + page.id() + " open", p::onOpen);
			}
		}
	}

	private void closeModPage() {
		if (openModPage != null && detailMod != null) {
			ModPage p = openModPage;
			detailMod.guard("page " + p.id() + " close", p::onClose);
		}
		openModPage = null;
	}

	void openStudio() {
		openStudio(null);
	}

	/** Opens the HUD Studio; with a query, its widget library is open and filtered (e.g. by a mod's name). */
	void openStudio(@Nullable String libraryQuery) {
		if (minecraft.level == null) {
			toast(Tr.t("bloom.hud.studio.need_world"), BloomTheme.WARNING);
			return;
		}
		HudStudioScreen studio = new HudStudioScreen(this);
		if (libraryQuery != null) studio.openLibrary(libraryQuery);
		minecraft.gui.setScreen(studio);
	}

	// ---------------------------------------------------------------- rendering

	@Override
	protected void renderCanvas(Ui ui, float p) {
		int pw = Math.min(cw - 24, 660);
		int ph = Math.min(ch - 24, 410);
		int px = (cw - pw) / 2;
		int py = (ch - ph) / 2 + Math.round((1 - p) * 10);
		Draw.shadow(ui.g, px, py, pw, ph, 8, 6);
		ui.round(px, py, pw, ph, 8, BloomTheme.PANEL);
		Draw.roundRectLeft(ui.g, px, py, SIDEBAR_W, ph, 8, ui.c(BloomTheme.SIDEBAR));
		ui.rect(px + SIDEBAR_W, py, 1, ph, BloomTheme.BORDER);
		ui.outline(px, py, pw, ph, 8, BloomTheme.BORDER);
		sidebar(ui, px, py, ph);

		int cx = px + SIDEBAR_W + 18, cy = py + 16;
		int cwid = pw - SIDEBAR_W - 34, chgt = ph - 30;
		float t = BloomClient.settings().animations.on() ? Math.min(1f, (System.currentTimeMillis() - sectionChangedAt) / 150f) : 1f;
		float eased = 1 - (1 - t) * (1 - t);
		float base = ui.alpha;
		ui.alpha = base * eased;
		int slide = Math.round((1 - eased) * 6);
		MenuPage page = pages.get(section);
		String title = page.title();
		ui.textScaled(title, cx, cy + slide, 1.5f, BloomTheme.TEXT);
		String sub = page.subtitle();
		if (!sub.isEmpty()) ui.text(ui.ellipsize(sub, cwid), cx, cy + 16 + slide, BloomTheme.TEXT_MUTED);
		int contentY = cy + 34;
		try {
			page.render(ui, cx, contentY + slide, cwid, chgt - 34);
		} finally {
			ui.alpha = base;
		}
	}

	private void sidebar(Ui ui, int px, int py, int ph) {
		int x = px + 12;
		Icon.BLOOM.draw(ui.g, x, py + 14, 2, ui.c(BloomTheme.ACCENT));
		ui.textScaled("Bloom", x + 24, py + 15, 1.5f, BloomTheme.TEXT);
		ui.text("Client " + BloomClient.version(), x + 24, py + 29, BloomTheme.TEXT_MUTED);

		int sy = py + 48;
		ui.textField("menu.search", x, sy, SIDEBAR_W - 24, 18, search, Tr.t("bloom.search.placeholder"), Icon.SEARCH);
		if (search.consumeChanged() && !search.isEmpty() && section != Section.SEARCH) {
			go(Section.SEARCH);
		}

		int ny = sy + 28;
		for (Section s : Section.values()) {
			if (s == Section.SEARCH) continue;
			navItem(ui, s, x - 4, ny, SIDEBAR_W - 16);
			ny += 22;
		}
		if (section == Section.SEARCH) {
			navItem(ui, Section.SEARCH, x - 4, ny, SIDEBAR_W - 16);
		}

		// Engine status and hint
		int by = py + ph - 34;
		int dot = switch (BloomEngine.status()) {
			case ACTIVE -> BloomTheme.ACCENT;
			case OFF -> BloomTheme.TEXT_MUTED;
			case FAILED -> BloomTheme.DANGER;
		};
		ui.round(x, by + 3, 5, 5, 2, dot);
		String engine = BloomEngine.active() ? Tr.t("bloom.sidebar.engine_native") : Tr.t("bloom.sidebar.engine_vanilla");
		ui.text(ui.ellipsize(engine, SIDEBAR_W - 34), x + 10, by + 1, BloomTheme.TEXT_DIM);
		if (ui.hovered(x, by - 2, SIDEBAR_W - 24, 12)) ui.tooltip(BloomClient.engineStatus());
		String keyName = BloomKeys.OPEN_MENU.getTranslatedKeyMessage().getString();
		ui.text(ui.ellipsize(keyName + " · Esc", SIDEBAR_W - 24), x, by + 14, BloomTheme.TEXT_MUTED);
	}

	private void navItem(Ui ui, Section s, int x, int y, int w) {
		boolean sel = section == s;
		boolean hov = ui.hovered(x, y, w, 19);
		float hv = ui.anim("nav." + s, hov || sel, 10);
		if (sel) {
			ui.round(x, y, w, 19, BloomTheme.RADIUS, BloomTheme.ACCENT_SOFT);
			ui.round(x, y + 4, 2, 11, 1, BloomTheme.ACCENT);
		} else {
			ui.round(x, y, w, 19, BloomTheme.RADIUS, BloomTheme.withAlpha(BloomTheme.SURFACE_HOVER, hv));
		}
		int color = sel ? BloomTheme.ACCENT : BloomTheme.lerp(BloomTheme.TEXT_DIM, BloomTheme.TEXT, hv);
		ui.icon(s.icon, x + 8, y + 5, color);
		ui.text(Tr.t(s.key), x + 24, y + 6, sel ? BloomTheme.TEXT : color);
		if (s == Section.MODS) {
			int errors = 0;
			for (ModContainer c : BloomClient.mods().all()) if (c.state() == ModContainer.State.FAILED || (c.failure() != null && !c.isEnabled())) errors++;
			if (errors > 0) {
				ui.round(x + w - 16, y + 5, 10, 10, 5, BloomTheme.DANGER);
				ui.textCentered(Integer.toString(errors), x + w - 11, y + 6, 0xFFFFFFFF);
			}
		}
		if (ui.clicked(x, y, w, 19)) {
			if (s == Section.MODS && section == Section.MODS) detailMod = null;
			go(s);
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	protected boolean onKey(KeyEvent event) {
		// Ctrl+F: search
		if (event.hasControlDownWithQuirk() && event.key() == 9) {
			ui.focus("menu.search", search);
			return true;
		}
		if (event.isEscape() && section == Section.MODS && detailMod != null) {
			showMod(null, "", null);
			return true;
		}
		if (openModPage != null && detailMod != null) {
			ModPage p = openModPage;
			boolean[] consumed = {false};
			detailMod.guard("page " + p.id() + " key", () -> consumed[0] = p.keyPressed(event));
			if (consumed[0]) return true;
		}
		return pages.get(section).keyPressed(event);
	}

	@Override
	protected void onClosed() {
		closeModPage();
		BloomClient.settings().group.saveIfDirty();
		BloomClient.mods().saveSettings();
	}
}
