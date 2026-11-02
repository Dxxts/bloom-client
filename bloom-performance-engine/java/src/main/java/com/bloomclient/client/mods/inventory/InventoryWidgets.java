package com.bloomclient.client.mods.inventory;

import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.hud.HudElement;
import com.bloomclient.client.api.hud.HudLines;
import com.bloomclient.client.api.hud.HudWidget;
import com.bloomclient.client.api.hud.WidgetOption;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.Tr;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** HUD widgets registered by the Inventory Manager. They read the last analysis; none of them scans the inventory per frame. */
final class InventoryWidgets {
	private InventoryWidgets() {
	}

	static int traffic(double fraction) {
		return fraction >= 0.5 ? 0xFF6EE7A0 : fraction >= 0.2 ? 0xFFF1D35A : 0xFFF07070;
	}

	static final class Summary extends HudWidget {
		private final InventoryManagerMod mod;

		Summary(InventoryManagerMod mod) {
			super("bloom-inventory-manager:summary", "bloom.im.widget.summary", "bloom.im.widget.summary.desc", Icon.BAG);
			this.mod = mod;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("items", "bloom.im.widget.summary.items", true), WidgetOption.bool("free", "bloom.im.widget.summary.free", true),
					WidgetOption.bool("low", "bloom.im.widget.summary.low", true));
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			InventoryAnalysis a = mod.analysis;
			if (e.option("items", true)) out.add(Tr.t("bloom.im.widget.items") + " ", Integer.toString(a.totalItems));
			if (e.option("free", true)) out.add(Tr.t("bloom.im.widget.free") + " ", a.freeSlots + "/" + InventoryAnalysis.MAIN_SIZE, traffic(a.freeSlots / 12.0));
			if (e.option("low", true) && !a.lowDurability.isEmpty()) out.add(Tr.t("bloom.im.widget.low") + " ", Integer.toString(a.lowDurability.size()), 0xFFF07070);
		}
	}

	static final class Selected extends HudWidget {
		private final InventoryManagerMod mod;

		Selected(InventoryManagerMod mod) {
			super("bloom-inventory-manager:selected", "bloom.im.widget.selected", "bloom.im.widget.selected.desc", Icon.STAR);
			this.mod = mod;
		}

		@Override
		public int updateIntervalMs() {
			return 100;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("total", "bloom.im.widget.selected.total", true), WidgetOption.bool("name", "bloom.im.widget.selected.name", true));
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			LocalPlayer p = Minecraft.getInstance().player;
			if (p == null) return;
			ItemStack held = p.getMainHandItem();
			if (held.isEmpty()) return;
			StringBuilder sb = new StringBuilder();
			if (e.option("name", true)) sb.append(held.getHoverName().getString());
			if (held.isDamageableItem()) {
				int max = held.getMaxDamage(), left = max - held.getDamageValue();
				if (!sb.isEmpty()) sb.append(' ');
				sb.append(Math.round(left * 100.0 / Math.max(1, max))).append('%');
			} else if (e.option("total", true)) {
				int total = Math.max(held.getCount(), mod.analysis.count(held.getItem()));
				if (!sb.isEmpty()) sb.append(' ');
				sb.append('×').append(total);
			}
			out.add("", sb.toString(), held, 0);
		}
	}

	static final class Durability extends HudWidget {
		private static final EquipmentSlot[] SLOTS = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
				EquipmentSlot.FEET};
		private final InventoryManagerMod mod;

		Durability(InventoryManagerMod mod) {
			super("bloom-inventory-manager:durability", "bloom.im.widget.durability", "bloom.im.widget.durability.desc", Icon.WARNING);
			this.mod = mod;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("onlyLow", "bloom.im.widget.durability.only_low", false));
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			LocalPlayer p = Minecraft.getInstance().player;
			if (p == null) return;
			boolean onlyLow = e.option("onlyLow", false);
			int threshold = mod.lowDurability.get();
			for (EquipmentSlot slot : SLOTS) {
				ItemStack s = p.getItemBySlot(slot);
				if (s.isEmpty() || !s.isDamageableItem()) continue;
				int max = s.getMaxDamage(), left = max - s.getDamageValue();
				if (onlyLow && left * 100 > max * threshold) continue;
				double f = left / (double) Math.max(1, max);
				out.add("", Math.round(f * 100) + "%", s, traffic(f));
			}
		}
	}

	static final class FreeSlots extends HudWidget {
		private final InventoryManagerMod mod;

		FreeSlots(InventoryManagerMod mod) {
			super("bloom-inventory-manager:free", "bloom.im.widget.free_slots", "bloom.im.widget.free_slots.desc", Icon.GRID);
			this.mod = mod;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("showLabel", "bloom.option.show_label", true));
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			InventoryAnalysis a = mod.analysis;
			out.add(e.option("showLabel", true) ? Tr.t("bloom.im.widget.free") + " " : "", a.freeSlots + "/" + InventoryAnalysis.MAIN_SIZE, traffic(a.freeSlots / 12.0));
		}
	}

	static final class GoalProgress extends HudWidget {
		private final InventoryManagerMod mod;

		GoalProgress(InventoryManagerMod mod) {
			super("bloom-inventory-manager:goal", "bloom.im.widget.goal", "bloom.im.widget.goal.desc", Icon.TARGET);
			this.mod = mod;
		}

		@Override
		public List<WidgetOption> options() {
			return List.of(WidgetOption.bool("next", "bloom.im.widget.goal.next", true));
		}

		@Override
		public boolean isAvailable(GameInfo game) {
			return mod.data.active() != null;
		}

		@Override
		public String unavailableText() {
			return Tr.t("bloom.im.widget.goal.none");
		}

		@Override
		public void update(HudElement e, HudLines out, GameInfo game) {
			InventoryData.Goal g = mod.data.active();
			if (g == null) return;
			InventoryAnalysis a = mod.analysis;
			double p = g.progress(a);
			out.add(g.name + " ", Math.round(p * 100) + "%", p >= 1 ? 0xFF6EE7A0 : 0);
			if (e.option("next", true)) {
				for (InventoryData.Requirement r : g.requirements) {
					int have = r.owned(a);
					if (have < r.count) {
						out.add("", r.label + " " + have + "/" + r.count, 0xFFA3ADA9);
						break;
					}
				}
			}
		}
	}
}
