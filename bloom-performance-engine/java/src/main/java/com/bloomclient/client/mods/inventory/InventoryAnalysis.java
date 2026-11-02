package com.bloomclient.client.mods.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Snapshot analysis of the local player's inventory, from the item stacks the
 * client holds. Recomputed only when the inventory changes (see
 * {@code InventoryChangedEvent}); widgets and pages read the last result.
 *
 * <p>Slot numbering follows the vanilla inventory: 0–8 hotbar, 9–35 main,
 * then armor (feet, legs, chest, head) and offhand, shown as
 * {@link #ARMOR_BASE}+i and {@link #OFFHAND}.
 */
public final class InventoryAnalysis {
	public static final int MAIN_SIZE = 36, HOTBAR = 9, ARMOR_BASE = 100, OFFHAND = 110;
	public static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	/** All stacks of one item (same item type; components may differ). */
	public static final class ItemEntry {
		public final Item item;
		public final ItemStack sample;
		public final ItemCategory category;
		public int count;
		public int stacks;
		public final List<Integer> slots = new ArrayList<>();
		/** Stacks that could be freed by merging partial stacks with identical components. */
		public int mergeableSlots;

		ItemEntry(ItemStack sample) {
			this.item = sample.getItem();
			this.sample = sample.copyWithCount(1);
			this.category = ItemCategory.of(sample);
		}

		public String id() {
			return BuiltInRegistries.ITEM.getKey(item).toString();
		}
	}

	public record LowDurability(int slot, ItemStack stack, int remaining, int max) {
		public double fraction() {
			return remaining / (double) Math.max(1, max);
		}
	}

	public final ItemStack[] main = new ItemStack[MAIN_SIZE];
	public final ItemStack[] armor = new ItemStack[4];
	public ItemStack offhand = ItemStack.EMPTY;
	public int selectedSlot;
	public final Map<Item, ItemEntry> items = new LinkedHashMap<>();
	public final Map<ItemCategory, Integer> categoryCounts = new EnumMap<>(ItemCategory.class);
	public final List<LowDurability> lowDurability = new ArrayList<>();
	public int totalItems;
	public int usedSlots;
	public int freeSlots;
	public int hotbarFree;
	public int partialCapacity;
	public int mergeableSlots;
	public int foodItems;
	public int foodNutrition;
	public float foodSaturation;
	public int arrows;
	public int blocks;
	public double armorPoints;
	public long computedAt;

	public static InventoryAnalysis empty() {
		InventoryAnalysis a = new InventoryAnalysis();
		java.util.Arrays.fill(a.main, ItemStack.EMPTY);
		java.util.Arrays.fill(a.armor, ItemStack.EMPTY);
		return a;
	}

	public static InventoryAnalysis of(LocalPlayer player, int lowDurabilityPercent) {
		InventoryAnalysis a = new InventoryAnalysis();
		a.computedAt = System.currentTimeMillis();
		NonNullList<ItemStack> inv = player.getInventory().getNonEquipmentItems();
		a.selectedSlot = player.getInventory().getSelectedSlot();
		for (int i = 0; i < MAIN_SIZE; i++) {
			a.main[i] = i < inv.size() ? inv.get(i) : ItemStack.EMPTY;
		}
		for (int i = 0; i < 4; i++) a.armor[i] = player.getItemBySlot(ARMOR_SLOTS[i]);
		a.offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);

		for (int i = 0; i < MAIN_SIZE; i++) a.add(a.main[i], i, lowDurabilityPercent);
		for (int i = 0; i < 4; i++) {
			a.add(a.armor[i], ARMOR_BASE + i, lowDurabilityPercent);
			a.armorPoints += ItemCategory.armorValue(a.armor[i]);
		}
		a.add(a.offhand, OFFHAND, lowDurabilityPercent);
		for (int i = 0; i < MAIN_SIZE; i++) {
			if (a.main[i].isEmpty()) {
				a.freeSlots++;
				if (i < HOTBAR) a.hotbarFree++;
			}
		}
		// Mergeable stacks: per item, group identical components and count slots beyond the minimum needed.
		for (ItemEntry e : a.items.values()) {
			if (e.stacks < 2) continue;
			List<ItemStack> partials = new ArrayList<>();
			for (int slot : e.slots) {
				if (slot >= MAIN_SIZE) continue;
				ItemStack s = a.main[slot];
				if (s.getCount() < ItemCategory.maxStack(s)) partials.add(s);
			}
			boolean[] used = new boolean[partials.size()];
			for (int i = 0; i < partials.size(); i++) {
				if (used[i]) continue;
				int total = partials.get(i).getCount(), n = 1;
				for (int j = i + 1; j < partials.size(); j++) {
					if (!used[j] && ItemStack.isSameItemSameComponents(partials.get(i), partials.get(j))) {
						used[j] = true;
						total += partials.get(j).getCount();
						n++;
					}
				}
				int max = ItemCategory.maxStack(partials.get(i));
				int needed = (total + max - 1) / max;
				e.mergeableSlots += n - needed;
			}
			a.mergeableSlots += e.mergeableSlots;
		}
		a.lowDurability.sort((x, y) -> Double.compare(x.fraction(), y.fraction()));
		return a;
	}

	private void add(ItemStack stack, int slot, int lowPercent) {
		if (stack.isEmpty()) return;
		usedSlots++;
		totalItems += stack.getCount();
		ItemEntry e = items.computeIfAbsent(stack.getItem(), k -> new ItemEntry(stack));
		e.count += stack.getCount();
		e.stacks++;
		e.slots.add(slot);
		categoryCounts.merge(e.category, stack.getCount(), Integer::sum);
		if (slot < MAIN_SIZE) {
			int max = ItemCategory.maxStack(stack);
			if (max > 1) partialCapacity += max - stack.getCount();
		}
		if (stack.isDamageableItem()) {
			int max = stack.getMaxDamage(), left = max - stack.getDamageValue();
			if (left * 100 <= max * lowPercent) lowDurability.add(new LowDurability(slot, stack, left, max));
		}
		FoodProperties food = stack.get(DataComponents.FOOD);
		if (food != null) {
			foodItems += stack.getCount();
			foodNutrition += food.nutrition() * stack.getCount();
			foodSaturation += food.saturation() * stack.getCount();
		}
		if (stack.typeHolder().is(ItemTags.ARROWS)) arrows += stack.getCount();
		if (e.category == ItemCategory.BLOCKS) blocks += stack.getCount();
	}

	public ItemStack stackAt(int slot) {
		if (slot >= 0 && slot < MAIN_SIZE) return main[slot];
		if (slot >= ARMOR_BASE && slot < ARMOR_BASE + 4) return armor[slot - ARMOR_BASE];
		if (slot == OFFHAND) return offhand;
		return ItemStack.EMPTY;
	}

	public int count(Item item) {
		ItemEntry e = items.get(item);
		return e == null ? 0 : e.count;
	}

	/** Items sorted by the number of slots they occupy (largest first). */
	public List<ItemEntry> bySlots() {
		List<ItemEntry> list = new ArrayList<>(items.values());
		list.sort((x, y) -> y.stacks != x.stacks ? Integer.compare(y.stacks, x.stacks) : Integer.compare(y.count, x.count));
		return list;
	}

	public List<ItemEntry> entries() {
		return Collections.unmodifiableList(new ArrayList<>(items.values()));
	}

	/** Human slot name: "Hotbar 3", "Slot 14", "Head", "Offhand". */
	public static String slotKey(int slot) {
		if (slot < HOTBAR) return "hotbar";
		if (slot < MAIN_SIZE) return "main";
		if (slot >= ARMOR_BASE && slot < ARMOR_BASE + 4) return "armor." + ARMOR_SLOTS[slot - ARMOR_BASE].getName();
		return "offhand";
	}
}
