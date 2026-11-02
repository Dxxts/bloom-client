package com.bloomclient.client.mods.inventory;

import com.bloomclient.client.ui.Icon;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;
import org.jspecify.annotations.Nullable;

/**
 * Item categories derived from the data components and tags the game
 * registers for each item (never from a hard-coded item list), so items of
 * other mods are categorized the same way.
 */
public enum ItemCategory {
	TOOLS(Icon.CHIP, 0xFF6CB4EE),
	WEAPONS(Icon.TARGET, 0xFFE5636B),
	ARMOR(Icon.BAG, 0xFFC58AF0),
	FOOD(Icon.STAR, 0xFFF29A5C),
	CONSUMABLES(Icon.PLUS, 0xFFF07AB0),
	BLOCKS(Icon.CUBE, 0xFF5CE1A8),
	MATERIALS(Icon.LAYERS, 0xFFE8B54D);

	public final Icon icon;
	public final int color;

	ItemCategory(Icon icon, int color) {
		this.icon = icon;
		this.color = color;
	}

	public String translationKey() {
		return "bloom.im.category." + name().toLowerCase(java.util.Locale.ROOT);
	}

	/** Tool kinds used by hotbar rules. */
	public enum Kind {
		PICKAXE, AXE, SHOVEL, HOE, SWORD, RANGED, FOOD, BLOCKS
	}

	public static @Nullable Equippable armorSlot(ItemStack stack) {
		Equippable eq = stack.get(DataComponents.EQUIPPABLE);
		return eq != null && eq.slot().isArmor() ? eq : null;
	}

	public static ItemCategory of(ItemStack stack) {
		if (armorSlot(stack) != null) return ARMOR;
		var holder = stack.typeHolder();
		if (holder.is(ItemTags.SWORDS) || stack.getItem() instanceof ProjectileWeaponItem || stack.has(DataComponents.BLOCKS_ATTACKS)
				|| (stack.has(DataComponents.WEAPON) && !stack.has(DataComponents.TOOL))) {
			return WEAPONS;
		}
		if (stack.has(DataComponents.TOOL) || (stack.isDamageableItem() && !stack.has(DataComponents.FOOD))) return TOOLS;
		if (stack.has(DataComponents.FOOD)) return FOOD;
		if (stack.has(DataComponents.CONSUMABLE) || stack.has(DataComponents.POTION_CONTENTS)) return CONSUMABLES;
		if (stack.getItem() instanceof BlockItem) return BLOCKS;
		return MATERIALS;
	}

	public static boolean is(ItemStack stack, Kind kind) {
		var holder = stack.typeHolder();
		return switch (kind) {
			case PICKAXE -> holder.is(ItemTags.PICKAXES);
			case AXE -> holder.is(ItemTags.AXES);
			case SHOVEL -> holder.is(ItemTags.SHOVELS);
			case HOE -> holder.is(ItemTags.HOES);
			case SWORD -> holder.is(ItemTags.SWORDS);
			case RANGED -> stack.getItem() instanceof ProjectileWeaponItem;
			case FOOD -> stack.has(DataComponents.FOOD);
			case BLOCKS -> stack.getItem() instanceof BlockItem;
		};
	}

	/** Armor points the piece gives in its slot (from its attribute modifiers). */
	public static double armorValue(ItemStack stack) {
		Equippable eq = armorSlot(stack);
		if (eq == null) return 0;
		ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
		EquipmentSlot slot = eq.slot();
		return mods.compute(Attributes.ARMOR, 0, slot) + mods.compute(Attributes.ARMOR_TOUGHNESS, 0, slot) * 0.01;
	}

	/** Durability tier proxy for comparing tools of the same kind: maximum durability, then remaining durability. */
	public static double toolScore(ItemStack stack) {
		if (!stack.isDamageableItem()) return 0;
		return stack.getMaxDamage() * 10_000.0 + (stack.getMaxDamage() - stack.getDamageValue());
	}

	public static int maxStack(ItemStack stack) {
		return stack.getOrDefault(DataComponents.MAX_STACK_SIZE, 1);
	}
}
