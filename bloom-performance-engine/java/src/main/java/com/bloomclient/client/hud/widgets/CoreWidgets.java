package com.bloomclient.client.hud.widgets;

import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.hud.HudAnchor;
import com.bloomclient.client.api.hud.HudElement;
import com.bloomclient.client.api.hud.HudLines;
import com.bloomclient.client.api.hud.HudStyle;
import com.bloomclient.client.api.hud.HudWidget;
import com.bloomclient.client.api.hud.WidgetOption;
import com.bloomclient.client.hud.HudManager;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.Direction;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Bloom's built-in HUD widgets. All values come from the client's own state. */
public final class CoreWidgets {
	private CoreWidgets() {
	}

	public static void registerAll(HudManager hud) {
		hud.registerCore(new Fps());
		hud.registerCore(new Coordinates());
		hud.registerCore(new Biome());
		hud.registerCore(new Facing());
		hud.registerCore(new Memory());
		hud.registerCore(new Ping());
		hud.registerCore(new Armor());
	}

	static String label(HudElement e, String key) {
		return e.option("showLabel", true) ? Tr.t(key) + " " : "";
	}

	/** Green above {@code good}, yellow above {@code ok}, red below. */
	static int traffic(double value, double good, double ok, boolean higherIsBetter) {
		boolean g = higherIsBetter ? value >= good : value <= good;
		boolean o = higherIsBetter ? value >= ok : value <= ok;
		return g ? 0xFF6EE7A0 : o ? 0xFFF1D35A : 0xFFF07070;
	}

	public static String biomeName(Identifier id) {
		String key = id.toLanguageKey("biome");
		return Language.getInstance().has(key) ? I18n.get(key) : id.getPath().replace('_', ' ');
	}

	public static String directionName(Direction d) {
		return Tr.t("bloom.direction." + d.getName());
	}

	// ------------------------------------------------------------------

	static final class Fps extends HudWidget {
		Fps() {
			super("bloom:fps", "bloom.widget.fps", "bloom.widget.fps.desc", Icon.CHART);
		}

		@Override
		public int updateIntervalMs() {
			return 500;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("showLabel", "bloom.option.show_label", true), WidgetOption.bool("colorize", "bloom.option.colorize", false));
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			int fps = game.fps();
			out.add(e.option("showLabel", true) ? "FPS " : "", Integer.toString(fps), e.option("colorize", false) ? traffic(fps, 60, 30, true) : 0);
		}
	}

	static final class Coordinates extends HudWidget {
		Coordinates() {
			super("bloom:coordinates", "bloom.widget.coordinates", "bloom.widget.coordinates.desc", Icon.PIN);
		}

		@Override
		public int updateIntervalMs() {
			return 100;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(
					WidgetOption.choice("layout", "bloom.option.layout", "line", List.of("line", "lines"), List.of("bloom.option.layout.line", "bloom.option.layout.lines")),
					WidgetOption.bool("decimals", "bloom.option.decimals", false),
					WidgetOption.bool("showLabel", "bloom.option.show_label", true));
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			boolean dec = e.option("decimals", false);
			String x = dec ? String.format(Locale.ROOT, "%.1f", game.x()) : Integer.toString(game.blockX());
			String y = dec ? String.format(Locale.ROOT, "%.1f", game.y()) : Integer.toString(game.blockY());
			String z = dec ? String.format(Locale.ROOT, "%.1f", game.z()) : Integer.toString(game.blockZ());
			boolean labels = e.option("showLabel", true);
			if (e.option("layout", "line").equals("lines")) {
				out.add(labels ? "X " : "", x);
				out.add(labels ? "Y " : "", y);
				out.add(labels ? "Z " : "", z);
			} else {
				out.add(labels ? "XYZ " : "", x + " " + y + " " + z);
			}
		}
	}

	static final class Biome extends HudWidget {
		Biome() {
			super("bloom:biome", "bloom.widget.biome", "bloom.widget.biome.desc", Icon.GLOBE);
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
			return game.biome() != null;
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			Identifier b = game.biome();
			out.add(label(e, "bloom.widget.biome.label"), b == null ? "?" : biomeName(b));
		}
	}

	static final class Facing extends HudWidget {
		Facing() {
			super("bloom:direction", "bloom.widget.direction", "bloom.widget.direction.desc", Icon.COMPASS);
		}

		@Override
		public int updateIntervalMs() {
			return 100;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("axis", "bloom.option.axis", true), WidgetOption.bool("yaw", "bloom.option.yaw", false),
					WidgetOption.bool("showLabel", "bloom.option.show_label", false));
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			Direction d = game.facing();
			StringBuilder sb = new StringBuilder(directionName(d));
			if (e.option("axis", true)) {
				String axis = switch (d) {
					case NORTH -> "-Z";
					case SOUTH -> "+Z";
					case EAST -> "+X";
					case WEST -> "-X";
					default -> "";
				};
				if (!axis.isEmpty()) sb.append(" (").append(axis).append(')');
			}
			if (e.option("yaw", false)) {
				float yaw = ((game.yaw() % 360) + 540) % 360 - 180;
				sb.append(String.format(Locale.ROOT, " %.1f°", yaw));
			}
			out.add(label(e, "bloom.widget.direction.label"), sb.toString());
		}
	}

	static final class Memory extends HudWidget {
		Memory() {
			super("bloom:memory", "bloom.widget.memory", "bloom.widget.memory.desc", Icon.CHIP);
		}

		@Override
		public int updateIntervalMs() {
			return 1000;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(
					WidgetOption.choice("format", "bloom.option.format", "both", List.of("percent", "absolute", "both"),
							List.of("bloom.option.format.percent", "bloom.option.format.absolute", "bloom.option.format.both")),
					WidgetOption.bool("showLabel", "bloom.option.show_label", true));
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			Runtime rt = Runtime.getRuntime();
			long max = rt.maxMemory(), used = rt.totalMemory() - rt.freeMemory();
			int pct = (int) (used * 100 / Math.max(1, max));
			String abs = (used >> 20) + "/" + (max >> 20) + " MB";
			String v = switch (e.option("format", "both")) {
				case "percent" -> pct + "%";
				case "absolute" -> abs;
				default -> pct + "% " + abs;
			};
			out.add(label(e, "bloom.widget.memory.label"), v, traffic(pct, 70, 85, false));
		}
	}

	static final class Ping extends HudWidget {
		Ping() {
			super("bloom:ping", "bloom.widget.ping", "bloom.widget.ping.desc", Icon.GLOBE);
		}

		@Override
		public int updateIntervalMs() {
			return 1000;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("showLabel", "bloom.option.show_label", true), WidgetOption.bool("colorize", "bloom.option.colorize", true));
		}

		@Override
		public void applyDefaults(HudElement element) {
			element.anchor = HudAnchor.TOP_RIGHT;
			element.style.align = HudStyle.Align.RIGHT;
		}

		@Override
		public boolean isAvailable(GameInfo game) {
			return game.ping() >= 0;
		}

		@Override
		public String unavailableText() {
			return Tr.t("bloom.widget.ping.unavailable");
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			int ping = game.ping();
			out.add(label(e, "bloom.widget.ping.label"), ping + " ms", e.option("colorize", true) ? traffic(ping, 80, 160, false) : 0);
		}
	}

	static final class Armor extends HudWidget {
		private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

		Armor() {
			super("bloom:armor", "bloom.widget.armor", "bloom.widget.armor.desc", Icon.BAG);
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(
					WidgetOption.choice("value", "bloom.option.value", "durability", List.of("durability", "percent", "none"),
							List.of("bloom.option.value.durability", "bloom.option.value.percent", "bloom.option.value.none")),
					WidgetOption.bool("hands", "bloom.option.hands", false));
		}

		@Override
		public void applyDefaults(HudElement element) {
			element.anchor = HudAnchor.MIDDLE_RIGHT;
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			LocalPlayer p = Minecraft.getInstance().player;
			if (p == null) return;
			String mode = e.option("value", "durability");
			for (EquipmentSlot slot : ARMOR) add(out, p.getItemBySlot(slot), mode);
			if (e.option("hands", false)) {
				add(out, p.getItemBySlot(EquipmentSlot.MAINHAND), mode);
				add(out, p.getItemBySlot(EquipmentSlot.OFFHAND), mode);
			}
		}

		private static void add(HudLines out, ItemStack stack, String mode) {
			if (stack.isEmpty()) return;
			if (!stack.isDamageableItem() || mode.equals("none")) {
				out.add("", mode.equals("none") || stack.getCount() <= 1 ? "" : Integer.toString(stack.getCount()), stack, 0);
				return;
			}
			int max = stack.getMaxDamage(), left = max - stack.getDamageValue();
			double frac = left / (double) Math.max(1, max);
			String text = mode.equals("percent") ? Math.round(frac * 100) + "%" : left + "/" + max;
			out.add("", text, stack, traffic(frac, 0.5, 0.2, true));
		}
	}
}
