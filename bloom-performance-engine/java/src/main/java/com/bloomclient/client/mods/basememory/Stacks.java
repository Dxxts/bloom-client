package com.bloomclient.client.mods.basememory;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.Locale;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jspecify.annotations.Nullable;

/** Item stacks to and from JSON (with all their components) and the text a stack is searched by. */
final class Stacks {
	private Stacks() {
	}

	/** Registry-aware JSON ops of the current world, or null outside a world. */
	static @Nullable RegistryOps<JsonElement> ops() {
		Minecraft mc = Minecraft.getInstance();
		HolderLookup.Provider registries = mc.level != null ? mc.level.registryAccess()
				: mc.getConnection() != null ? mc.getConnection().registryAccess() : null;
		return registries == null ? null : RegistryOps.create(JsonOps.INSTANCE, registries);
	}

	static @Nullable JsonElement encode(ItemStack stack, RegistryOps<JsonElement> ops) {
		if (stack.isEmpty()) return null;
		return ItemStack.CODEC.encodeStart(ops, stack).result().orElse(null);
	}

	/** The stack, or empty when it can't be read (for example an item of a mod that was removed). */
	static ItemStack decode(@Nullable JsonElement json, RegistryOps<JsonElement> ops) {
		if (json == null || json.isJsonNull()) return ItemStack.EMPTY;
		try {
			return ItemStack.CODEC.parse(ops, json).result().orElse(ItemStack.EMPTY);
		} catch (RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}

	/** Items stored inside {@code stack} (shulker box contents, bundle contents), one level deep. */
	static void forEachInside(ItemStack stack, BiConsumer<ItemStack, ItemStack> action) {
		ItemContainerContents container = stack.get(DataComponents.CONTAINER);
		if (container != null) container.nonEmptyItemCopyStream().forEach(inner -> action.accept(stack, inner));
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null && !bundle.isEmpty()) bundle.itemCopies().forEach(inner -> action.accept(stack, inner));
	}

	/** Lower-case text a stack is found by: its name, its id and its enchantments (also those stored in books). */
	static String searchText(ItemStack stack) {
		StringBuilder sb = new StringBuilder(64);
		sb.append(stack.getHoverName().getString()).append(' ');
		sb.append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().replace('_', ' '));
		appendEnchantments(sb, stack.get(DataComponents.ENCHANTMENTS));
		appendEnchantments(sb, stack.get(DataComponents.STORED_ENCHANTMENTS));
		return sb.toString().toLowerCase(Locale.ROOT);
	}

	/** "Mending, Unbreaking III" for enchanted items and books, empty otherwise. */
	static String enchantments(ItemStack stack) {
		StringBuilder sb = new StringBuilder();
		appendEnchantments(sb, stack.get(DataComponents.ENCHANTMENTS));
		appendEnchantments(sb, stack.get(DataComponents.STORED_ENCHANTMENTS));
		return sb.length() > 2 ? sb.substring(2) : "";
	}

	private static void appendEnchantments(StringBuilder sb, @Nullable ItemEnchantments enchantments) {
		if (enchantments == null || enchantments.isEmpty()) return;
		for (var entry : enchantments.entrySet()) {
			sb.append(", ").append(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
		}
	}

	/** Display name with enchantments: "Enchanted Book (Mending)". */
	static String label(ItemStack stack) {
		String e = enchantments(stack);
		String name = stack.getHoverName().getString();
		return e.isEmpty() ? name : name + " (" + e + ")";
	}
}
