package com.bloomclient.client.mods.basememory;

import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.hud.HudAnchor;
import com.bloomclient.client.api.hud.HudElement;
import com.bloomclient.client.api.hud.HudLines;
import com.bloomclient.client.api.hud.HudWidget;
import com.bloomclient.client.api.hud.WidgetOption;
import com.bloomclient.client.mods.worldtracker.WorldTrackerMod;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.item.ItemStack;

/** HUD line while Base Memory is guiding the player: what, where, which way and how far. */
final class GuideWidget extends HudWidget {
	static final String ID = "bloom-base-memory:guide";
	private final BaseMemoryMod mod;

	GuideWidget(BaseMemoryMod mod) {
		super(ID, "bloom.bm.widget.guide", "bloom.bm.widget.guide.desc", Icon.CHEST);
		this.mod = mod;
	}

	@Override
	public int updateIntervalMs() {
		return 100;
	}

	@Override
	public List<WidgetOption> options() {
		return List.of(WidgetOption.bool("name", "bloom.bm.widget.guide.show_name", true), WidgetOption.bool("height", "bloom.bm.widget.guide.show_height", true));
	}

	@Override
	public void applyDefaults(HudElement element) {
		element.anchor = HudAnchor.TOP_CENTER;
		element.y = 30;
	}

	@Override
	public boolean isAvailable(GameInfo game) {
		return mod.guide != null && game.inWorld();
	}

	@Override
	public String unavailableText() {
		return Tr.t("bloom.bm.widget.guide.none");
	}

	@Override
	public void update(HudElement e, HudLines out, GameInfo game) {
		BaseMemoryMod.Guide g = mod.guide;
		if (g == null) return;
		ItemStack icon = g.item == null ? ItemStack.EMPTY : new ItemStack(g.item);
		String label = e.option("name", true) ? g.label + " " : "";
		if (g.reached) {
			out.add(label, Tr.t("bloom.bm.widget.guide.here"), icon, BloomTheme.ACCENT);
			return;
		}
		String here = game.dimension() == null ? "" : game.dimension().toString();
		if (!here.equals(g.dim)) {
			out.add(label, WorldTrackerMod.dimensionName(g.dim), icon, BloomTheme.WARNING);
			return;
		}
		double dx = g.x - game.x(), dz = g.z - game.z();
		StringBuilder sb = new StringBuilder();
		sb.append(WorldTrackerMod.arrow(dx, dz, game.yaw())).append(' ').append(WorldTrackerMod.formatDistance(Math.sqrt(dx * dx + dz * dz)));
		long dy = Math.round(g.y - 1 - game.y());
		if (e.option("height", true) && Math.abs(dy) >= 3) sb.append(String.format(Locale.ROOT, " (%+d Y)", dy));
		out.add(label, sb.toString(), icon, BloomTheme.ACCENT);
	}
}
