package com.bloomclient.client.mods.inventory;

import com.bloomclient.client.ui.Tr;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Organization suggestions from the analysis and the player's rules. Bloom
 * only advises: it never moves items, never clicks slots and never sends
 * inventory packets. Suggestions with slots are highlighted in the vanilla
 * inventory screen so the player can act on them.
 */
final class Organizer {
	enum Type {
		MOVE, EQUIP, MERGE, WARN, INFO
	}

	/** {@code from}/{@code to} use {@link InventoryAnalysis} slot numbers, -1 when not applicable. */
	record Suggestion(Type type, String text, int from, int to, ItemStack icon) {
	}

	record Options(boolean armor, boolean food, boolean merge, int almostFull) {
	}

	private Organizer() {
	}

	static List<Suggestion> compute(InventoryAnalysis a, InventoryData data, Options o) {
		List<Suggestion> out = new ArrayList<>();
		hotbar(a, data, out);
		if (o.armor()) armor(a, out);
		if (o.food()) food(a, data, out);
		for (InventoryAnalysis.LowDurability low : a.lowDurability) {
			if (low.slot() < InventoryAnalysis.HOTBAR || low.slot() >= InventoryAnalysis.ARMOR_BASE) {
				out.add(new Suggestion(Type.WARN, Tr.t("bloom.im.sg.low", low.stack().getHoverName().getString(), Math.round(low.fraction() * 100)), low.slot(), -1,
						low.stack()));
			}
		}
		if (o.merge()) {
			for (InventoryAnalysis.ItemEntry e : a.items.values()) {
				if (e.mergeableSlots > 0) {
					int first = -1;
					for (int s : e.slots) if (s < InventoryAnalysis.MAIN_SIZE && a.main[s].getCount() < ItemCategory.maxStack(a.main[s])) {
						first = s;
						break;
					}
					out.add(new Suggestion(Type.MERGE, Tr.t("bloom.im.sg.merge", e.sample.getHoverName().getString(), e.mergeableSlots), first, -1, e.sample));
				}
			}
		}
		if (a.freeSlots <= o.almostFull()) {
			List<InventoryAnalysis.ItemEntry> big = a.bySlots();
			String top = big.isEmpty() ? "-" : big.getFirst().sample.getHoverName().getString() + " ×" + big.getFirst().stacks;
			out.add(new Suggestion(Type.WARN, Tr.t("bloom.im.sg.full", a.freeSlots, top), -1, -1, ItemStack.EMPTY));
		}
		return out;
	}

	private static String ruleName(InventoryData.HotbarRule r) {
		if ("item".equals(r.kind)) {
			var item = InventoryData.item(r.item);
			return item == null ? r.item : new ItemStack(item).getHoverName().getString();
		}
		return Tr.t("bloom.im.kind." + r.kind);
	}

	private static void hotbar(InventoryAnalysis a, InventoryData data, List<Suggestion> out) {
		boolean[] claimed = new boolean[InventoryAnalysis.MAIN_SIZE];
		// Slots already satisfying their own rule keep their item.
		for (int i = 0; i < InventoryAnalysis.HOTBAR; i++) {
			if (data.hotbar[i].matches(a.main[i])) claimed[i] = true;
		}
		for (int i = 0; i < InventoryAnalysis.HOTBAR; i++) {
			InventoryData.HotbarRule rule = data.hotbar[i];
			if (!rule.active() || claimed[i]) continue;
			int best = -1;
			double bestScore = -1;
			for (int s = 0; s < InventoryAnalysis.MAIN_SIZE; s++) {
				if (claimed[s] || s == i) continue;
				ItemStack st = a.main[s];
				if (!rule.matches(st)) continue;
				double score = st.isDamageableItem() ? ItemCategory.toolScore(st) : st.getCount();
				FoodProperties food = st.get(DataComponents.FOOD);
				if (food != null) score = food.nutrition() * 100 + st.getCount();
				if (score > bestScore) {
					bestScore = score;
					best = s;
				}
			}
			if (best >= 0) {
				claimed[best] = true;
				out.add(new Suggestion(Type.MOVE, Tr.t("bloom.im.sg.move", a.main[best].getHoverName().getString(), slotName(best), i + 1), best, i, a.main[best]));
			} else {
				out.add(new Suggestion(Type.INFO, Tr.t("bloom.im.sg.missing", ruleName(rule), i + 1), -1, i, ItemStack.EMPTY));
			}
		}
	}

	private static void armor(InventoryAnalysis a, List<Suggestion> out) {
		for (int k = 0; k < 4; k++) {
			double current = ItemCategory.armorValue(a.armor[k]);
			int best = -1;
			double bestValue = current;
			for (int s = 0; s < InventoryAnalysis.MAIN_SIZE; s++) {
				ItemStack st = a.main[s];
				Equippable eq = ItemCategory.armorSlot(st);
				if (eq == null || eq.slot() != InventoryAnalysis.ARMOR_SLOTS[k]) continue;
				double v = ItemCategory.armorValue(st);
				if (v > bestValue + 0.001) {
					bestValue = v;
					best = s;
				}
			}
			if (best >= 0) {
				String slot = Tr.t("bloom.im.slot.armor." + InventoryAnalysis.ARMOR_SLOTS[k].getName());
				out.add(new Suggestion(Type.EQUIP, Tr.t("bloom.im.sg.equip", a.main[best].getHoverName().getString(), slot, fmt(bestValue), fmt(current)), best,
						InventoryAnalysis.ARMOR_BASE + k, a.main[best]));
			}
		}
	}

	private static String fmt(double v) {
		return v == Math.floor(v) ? Integer.toString((int) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
	}

	private static void food(InventoryAnalysis a, InventoryData data, List<Suggestion> out) {
		if (a.foodItems == 0) return;
		for (int i = 0; i < InventoryAnalysis.HOTBAR; i++) if (a.main[i].has(DataComponents.FOOD)) return;
		if (a.offhand.has(DataComponents.FOOD)) return;
		int free = -1;
		for (int i = 0; i < InventoryAnalysis.HOTBAR; i++) {
			if (a.main[i].isEmpty() && !data.hotbar[i].active()) {
				free = i;
				break;
			}
		}
		int best = -1, bestNutrition = -1;
		for (int s = InventoryAnalysis.HOTBAR; s < InventoryAnalysis.MAIN_SIZE; s++) {
			FoodProperties f = a.main[s].get(DataComponents.FOOD);
			if (f != null && f.nutrition() > bestNutrition) {
				bestNutrition = f.nutrition();
				best = s;
			}
		}
		if (best < 0) return;
		if (free >= 0) {
			out.add(new Suggestion(Type.MOVE, Tr.t("bloom.im.sg.food", a.main[best].getHoverName().getString(), free + 1), best, free, a.main[best]));
		} else {
			out.add(new Suggestion(Type.INFO, Tr.t("bloom.im.sg.food_nofree", a.main[best].getHoverName().getString()), best, -1, a.main[best]));
		}
	}

	static String slotName(int slot) {
		if (slot < InventoryAnalysis.HOTBAR) return Tr.t("bloom.im.slot.hotbar", slot + 1);
		if (slot < InventoryAnalysis.MAIN_SIZE) return Tr.t("bloom.im.slot.main", slot - 8);
		if (slot == InventoryAnalysis.OFFHAND) return Tr.t("bloom.im.slot.offhand");
		return Tr.t("bloom.im.slot.armor." + InventoryAnalysis.ARMOR_SLOTS[slot - InventoryAnalysis.ARMOR_BASE].getName());
	}
}
