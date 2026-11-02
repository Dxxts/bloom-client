package com.bloomclient.client.mods.worldtracker;

import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.hud.HudElement;
import com.bloomclient.client.api.hud.HudLines;
import com.bloomclient.client.api.hud.HudWidget;
import com.bloomclient.client.api.hud.WidgetOption;
import com.bloomclient.client.hud.widgets.CoreWidgets;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import java.util.List;
import java.util.Locale;
import net.minecraft.resources.Identifier;

/** HUD widgets registered by the World Tracker. */
final class TrackerWidgets {
	private TrackerWidgets() {
	}

	private static String label(HudElement e, String key) {
		return e.option("showLabel", true) ? Tr.t(key) + " " : "";
	}

	/** Current dimension. */
	static final class Dimension extends HudWidget {
		Dimension(WorldTrackerMod mod) {
			super("bloom-world-tracker:dimension", "bloom.wt.widget.dimension", "bloom.wt.widget.dimension.desc", Icon.GLOBE);
		}

		@Override
		public int updateIntervalMs() {
			return 500;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("showLabel", "bloom.option.show_label", true));
		}

		@Override
		public boolean isAvailable(GameInfo game) {
			return game.dimension() != null;
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			Identifier d = game.dimension();
			out.add(label(e, "bloom.wt.widget.dimension.label"), d == null ? "?" : WorldTrackerMod.dimensionName(d.toString()));
		}
	}

	/** Distance and direction to the selected point of interest. */
	static final class Target extends HudWidget {
		private final WorldTrackerMod mod;

		Target(WorldTrackerMod mod) {
			super("bloom-world-tracker:target", "bloom.wt.widget.target", "bloom.wt.widget.target.desc", Icon.TARGET);
			this.mod = mod;
		}

		@Override
		public int updateIntervalMs() {
			return 100;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("name", "bloom.wt.widget.target.show_name", true), WidgetOption.bool("arrow", "bloom.wt.widget.target.show_arrow", true),
					WidgetOption.bool("height", "bloom.wt.widget.target.show_height", false));
		}

		@Override
		public boolean isAvailable(GameInfo game) {
			WorldState s = mod.state;
			Poi p = s == null ? null : s.selected();
			return p != null && mod.delta(p, game) != null;
		}

		@Override
		public String unavailableText() {
			return Tr.t("bloom.wt.widget.target.none");
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			WorldState s = mod.state;
			Poi p = s == null ? null : s.selected();
			if (p == null) return;
			double[] d = mod.delta(p, game);
			if (d == null) return;
			double horizontal = Math.sqrt(d[0] * d[0] + d[2] * d[2]);
			StringBuilder sb = new StringBuilder();
			if (e.option("arrow", true)) sb.append(WorldTrackerMod.arrow(d[0], d[2], game.yaw())).append(' ');
			sb.append(WorldTrackerMod.formatDistance(horizontal));
			if (e.option("height", false)) sb.append(String.format(Locale.ROOT, " (%+d Y)", Math.round(d[1])));
			out.add(e.option("name", true) ? p.name + " " : "", sb.toString(), p.category.color);
		}
	}

	/** Overworld ↔ Nether linked coordinates (x/8, z/8). */
	static final class Linked extends HudWidget {
		Linked() {
			super("bloom-world-tracker:linked", "bloom.wt.widget.linked", "bloom.wt.widget.linked.desc", Icon.TARGET);
		}

		@Override
		public int updateIntervalMs() {
			return 200;
		}

		@Override
		public boolean isAvailable(GameInfo game) {
			Identifier d = game.dimension();
			return d != null && (d.toString().equals(WorldTrackerMod.OVERWORLD) || d.toString().equals(WorldTrackerMod.NETHER));
		}

		@Override
		public String unavailableText() {
			return Tr.t("bloom.wt.widget.linked.unavailable");
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			boolean nether = game.dimension().toString().equals(WorldTrackerMod.NETHER);
			double f = nether ? 8 : 1 / 8.0;
			String other = WorldTrackerMod.dimensionName(nether ? WorldTrackerMod.OVERWORLD : WorldTrackerMod.NETHER);
			out.add(other + " ", (int) Math.floor(game.x() * f) + " " + game.blockY() + " " + (int) Math.floor(game.z() * f));
		}
	}

	/** Combined panel: the player picks the lines. */
	static final class Overview extends HudWidget {
		private final WorldTrackerMod mod;

		Overview(WorldTrackerMod mod) {
			super("bloom-world-tracker:overview", "bloom.wt.widget.overview", "bloom.wt.widget.overview.desc", Icon.LIST);
			this.mod = mod;
		}

		@Override
		public int updateIntervalMs() {
			return 100;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("coords", "bloom.wt.widget.overview.coords", true), WidgetOption.bool("biome", "bloom.wt.widget.overview.biome", true),
					WidgetOption.bool("dimension", "bloom.wt.widget.overview.dimension", true),
					WidgetOption.bool("direction", "bloom.wt.widget.overview.direction", true),
					WidgetOption.bool("target", "bloom.wt.widget.overview.target", true));
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			if (e.option("coords", true)) out.add("XYZ ", game.blockX() + " " + game.blockY() + " " + game.blockZ());
			if (e.option("biome", true) && game.biome() != null) out.add(Tr.t("bloom.widget.biome.label") + " ", CoreWidgets.biomeName(game.biome()));
			if (e.option("dimension", true) && game.dimension() != null) {
				out.add(Tr.t("bloom.wt.widget.dimension.label") + " ", WorldTrackerMod.dimensionName(game.dimension().toString()));
			}
			if (e.option("direction", true)) out.add(Tr.t("bloom.widget.direction.label") + " ", CoreWidgets.directionName(game.facing()));
			if (e.option("target", true)) {
				WorldState s = mod.state;
				Poi p = s == null ? null : s.selected();
				double[] d = p == null ? null : mod.delta(p, game);
				if (d != null) {
					double horizontal = Math.sqrt(d[0] * d[0] + d[2] * d[2]);
					out.add(p.name + " ", WorldTrackerMod.arrow(d[0], d[2], game.yaw()) + " " + WorldTrackerMod.formatDistance(horizontal), p.category.color);
				}
			}
		}
	}
}
