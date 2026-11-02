package com.bloomclient.client.mods.inventory;

import com.bloomclient.client.ui.Tr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;

/**
 * Item and recipe lookup for the Inventory Manager: items come from the game's
 * item registry (vanilla and mods), recipes from the player's own recipe book
 * as the server sent it, so only recipes the game actually knows for this
 * player are offered and no requirement is invented.
 */
final class ItemSearch {
	record Entry(Item item, String id, String name, String lower) {
	}

	/** A recipe the player knows, with its ingredients per craft. */
	record RecipeOption(String name, ItemStack result, List<InventoryData.Requirement> perCraft) {
	}

	private static List<Entry> cache = List.of();
	private static String cacheLanguage = "";

	private ItemSearch() {
	}

	private static List<Entry> all() {
		String lang = Tr.language();
		if (cache.isEmpty() || !lang.equals(cacheLanguage)) {
			List<Entry> list = new ArrayList<>();
			BuiltInRegistries.ITEM.stream().forEach(item -> {
				if (item == Items.AIR) return;
				String name = new ItemStack(item).getHoverName().getString();
				list.add(new Entry(item, BuiltInRegistries.ITEM.getKey(item).toString(), name, name.toLowerCase(Locale.ROOT)));
			});
			cache = list;
			cacheLanguage = lang;
		}
		return cache;
	}

	static List<Entry> items(String query, int limit) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		List<Entry> out = new ArrayList<>();
		if (q.isEmpty()) return out;
		for (Entry e : all()) {
			if (e.lower.startsWith(q)) out.add(e);
		}
		for (Entry e : all()) {
			if (out.size() >= limit * 3) break;
			if (!e.lower.startsWith(q) && (e.lower.contains(q) || e.id.contains(q))) out.add(e);
		}
		return out.size() > limit ? out.subList(0, limit) : out;
	}

	static String name(String itemId) {
		Item item = InventoryData.item(itemId);
		return item == null ? itemId : new ItemStack(item).getHoverName().getString();
	}

	static List<RecipeOption> recipes(String query, int limit) {
		Minecraft mc = Minecraft.getInstance();
		List<RecipeOption> out = new ArrayList<>();
		if (mc.player == null || mc.level == null) return out;
		String q = query.trim().toLowerCase(Locale.ROOT);
		ClientRecipeBook book = mc.player.getRecipeBook();
		var context = SlotDisplayContext.fromLevel(mc.level);
		for (RecipeCollection collection : book.getCollections()) {
			for (RecipeDisplayEntry entry : collection.getRecipes()) {
				Optional<List<Ingredient>> requirements = entry.craftingRequirements();
				if (requirements.isEmpty() || requirements.get().isEmpty()) continue;
				List<ItemStack> results = entry.resultItems(context);
				if (results.isEmpty() || results.getFirst().isEmpty()) continue;
				ItemStack result = results.getFirst();
				String name = result.getHoverName().getString();
				if (!q.isEmpty() && !name.toLowerCase(Locale.ROOT).contains(q)) continue;
				out.add(new RecipeOption(name, result, requirements(requirements.get())));
				if (out.size() >= limit) return out;
			}
		}
		return out;
	}

	/** Groups identical ingredients: each ingredient slot needs one item per craft. */
	private static List<InventoryData.Requirement> requirements(List<Ingredient> ingredients) {
		Map<List<String>, InventoryData.Requirement> grouped = new LinkedHashMap<>();
		for (Ingredient ing : ingredients) {
			List<String> ids = ing.items().map(Holder::value).map(i -> BuiltInRegistries.ITEM.getKey(i).toString()).toList();
			if (ids.isEmpty()) continue;
			InventoryData.Requirement r = grouped.computeIfAbsent(ids, k -> {
				InventoryData.Requirement n = new InventoryData.Requirement();
				n.items = new ArrayList<>(k);
				n.count = 0;
				String first = name(k.getFirst());
				n.label = k.size() == 1 ? first : Tr.t("bloom.im.goal.any_of", first, k.size());
				return n;
			});
			r.count++;
		}
		return new ArrayList<>(grouped.values());
	}
}
