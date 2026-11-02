package com.bloomclient.client.ui.screen;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.api.storage.WorldIdentity;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Draw;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.client.ui.Ui;
import com.bloomclient.engine.BloomEngine;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

/** Home: who is playing and what is running, from values the client actually has. */
final class HomePage extends MenuPage {
	private record Stat(Icon icon, String label, String value, int dot) {
	}

	private long gpuReadAt;
	private String backend = "?";
	private String gpu = "?";

	HomePage(BloomMenuScreen menu) {
		super(menu);
	}

	@Override
	String title() {
		return Tr.t("bloom.nav.home");
	}

	@Override
	String subtitle() {
		return Tr.t("bloom.home.subtitle");
	}

	private void readDevice() {
		long now = System.currentTimeMillis();
		if (now - gpuReadAt < 5000) return;
		gpuReadAt = now;
		try {
			var info = RenderSystem.getDevice().getDeviceInfo();
			backend = info.backendName();
			gpu = info.name();
		} catch (RuntimeException e) {
			backend = gpu = "?";
		}
	}

	@Override
	void render(Ui ui, int x, int y, int w, int h) {
		Minecraft mc = ui.mc;
		readDevice();
		// Hero
		int heroH = 56;
		ui.round(x, y, w, heroH, 6, BloomTheme.SURFACE);
		Draw.hGradient(ui.g, x + 1, y + 1, Math.min(w - 2, 220), heroH - 2, ui.c(0x305CE1A8), ui.c(0x005CE1A8), 24);
		ui.outline(x, y, w, heroH, 6, BloomTheme.BORDER);
		Icon.BLOOM.draw(ui.g, x + 14, y + 14, 3, ui.c(BloomTheme.ACCENT));
		ui.textScaled(Tr.t("bloom.home.welcome", mc.getUser().getName()), x + 50, y + 14, 1.5f, BloomTheme.TEXT);
		ui.text(Tr.t("bloom.home.hero", BloomClient.version(), SharedConstants.getCurrentVersion().name()), x + 50, y + 32, BloomTheme.TEXT_DIM);

		// Stats
		List<Stat> stats = new ArrayList<>();
		stats.add(new Stat(Icon.CUBE, Tr.t("bloom.home.minecraft"), SharedConstants.getCurrentVersion().name(), 0));
		stats.add(new Stat(Icon.BLOOM, Tr.t("bloom.home.bloom"), BloomClient.version(), 0));
		int engineDot = switch (BloomEngine.status()) {
			case ACTIVE -> BloomTheme.ACCENT;
			case OFF -> BloomTheme.TEXT_MUTED;
			case FAILED -> BloomTheme.DANGER;
		};
		stats.add(new Stat(Icon.CHIP, Tr.t("bloom.home.engine"), BloomEngine.active() ? Tr.t("bloom.home.engine.native") : Tr.t("bloom.home.engine.vanilla"), engineDot));
		stats.add(new Stat(Icon.CHART, Tr.t("bloom.home.graphics"), backend + " · " + gpu, 0));
		String loader = FabricLoader.getInstance().getModContainer("fabricloader").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		stats.add(new Stat(Icon.MODS, Tr.t("bloom.home.loader"), "Fabric " + loader + " · " + FabricLoader.getInstance().getAllMods().size() + " " + Tr.t("bloom.home.fabric_mods"), 0));
		stats.add(new Stat(Icon.INFO, Tr.t("bloom.home.java"), System.getProperty("java.version"), 0));
		Runtime rt = Runtime.getRuntime();
		long used = (rt.totalMemory() - rt.freeMemory()) >> 20, max = rt.maxMemory() >> 20;
		stats.add(new Stat(Icon.CHIP, Tr.t("bloom.home.memory"), used + " / " + max + " MB", 0));
		WorldIdentity world = BloomClient.worlds().current();
		stats.add(new Stat(Icon.GLOBE, Tr.t("bloom.home.world"), world == null ? Tr.t("bloom.home.no_world") : world.displayName(), world == null ? BloomTheme.TEXT_MUTED : BloomTheme.ACCENT));
		stats.add(new Stat(Icon.MODS, Tr.t("bloom.home.bloom_mods"),
				Tr.t("bloom.home.mods_value", BloomClient.mods().enabledCount(), BloomClient.mods().all().size()), 0));

		int cols = w >= 420 ? 3 : 2;
		int gap = 8;
		int cardW = (w - gap * (cols - 1)) / cols, cardH = 38;
		int sy = y + heroH + 10;
		for (int i = 0; i < stats.size(); i++) {
			Stat s = stats.get(i);
			int cx = x + (i % cols) * (cardW + gap), cy = sy + (i / cols) * (cardH + gap);
			boolean hov = ui.hovered(cx, cy, cardW, cardH);
			ui.round(cx, cy, cardW, cardH, 5, hov ? BloomTheme.SURFACE_2 : BloomTheme.SURFACE);
			ui.icon(s.icon, cx + 9, cy + 8, BloomTheme.TEXT_MUTED);
			ui.text(s.label, cx + 23, cy + 9, BloomTheme.TEXT_MUTED);
			int vx = cx + 9;
			if (s.dot != 0) {
				ui.round(vx, cy + 25, 5, 5, 2, s.dot);
				vx += 9;
			}
			String value = ui.ellipsize(s.value, cx + cardW - 8 - vx);
			ui.text(value, vx, cy + 24, BloomTheme.TEXT);
			if (hov && !value.equals(s.value)) ui.tooltip(s.value);
			if (hov && s.icon == Icon.CHIP && s.label.equals(Tr.t("bloom.home.engine"))) ui.tooltip(BloomClient.engineStatus());
		}
		int rows = (stats.size() + cols - 1) / cols;
		int ay = sy + rows * (cardH + gap) + 4;
		if (ay + 20 > y + h) return;
		ui.text(Tr.t("bloom.home.quick").toUpperCase(java.util.Locale.ROOT), x, ay, BloomTheme.TEXT_MUTED);
		ay += 13;
		int bx = x;
		if (ui.button("home.studio", bx, ay, 120, 20, Tr.t("bloom.home.open_studio"), Icon.HUD, Ui.Kind.PRIMARY, mc.level != null)) menu.openStudio();
		if (mc.level == null && ui.hovered(bx, ay, 120, 20)) ui.tooltip(Tr.t("bloom.hud.studio.need_world"));
		bx += 126;
		if (ui.button("home.mods", bx, ay, 110, 20, Tr.t("bloom.home.manage_mods"), Icon.MODS, Ui.Kind.SECONDARY, true)) menu.go(BloomMenuScreen.Section.MODS);
		bx += 116;
		boolean hud = BloomClient.settings().hudEnabled.on();
		if (ui.button("home.hud", bx, ay, 110, 20, Tr.t(hud ? "bloom.home.hide_hud" : "bloom.home.show_hud"), hud ? Icon.EYE_OFF : Icon.EYE, Ui.Kind.SECONDARY, true)) {
			BloomClient.settings().hudEnabled.toggle();
		}
	}
}
