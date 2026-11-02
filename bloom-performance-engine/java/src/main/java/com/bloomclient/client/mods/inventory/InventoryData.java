package com.bloomclient.client.mods.inventory;

import com.bloomclient.client.api.storage.WorldStorage;
import com.bloomclient.client.core.BloomStorage;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/**
 * Inventory Manager data: hotbar rules, watch list and important items
 * (player preferences, global: {@code .bloom/mods/bloom-inventory-manager/rules.json})
 * and goals (per world: {@code .bloom/worlds/<world>/bloom-inventory-manager/goals.json}).
 */
final class InventoryData {
	static final int SCHEMA = 1;

	/** What a hotbar slot should hold. {@code kind}: none, pickaxe, axe, shovel, hoe, sword, ranged, food, blocks, item. */
	static final class HotbarRule {
		String kind = "none";
		/** Item id when {@code kind} is {@code item}. */
		String item = "";

		boolean active() {
			return !"none".equals(kind) && !("item".equals(kind) && item.isBlank());
		}

		boolean matches(ItemStack stack) {
			if (stack.isEmpty() || !active()) return false;
			if ("item".equals(kind)) return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item);
			ItemCategory.Kind k = kind();
			return k != null && ItemCategory.is(stack, k);
		}

		ItemCategory.@Nullable Kind kind() {
			try {
				return ItemCategory.Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT));
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
	}

	/** A resource the player wants to keep an eye on. */
	static final class Watch {
		String item = "";
		/** Warn when the count drops below this (0 = no threshold). */
		int min;
		boolean important;
	}

	/** One requirement of a goal: any of {@code items}, {@code count} in total. */
	static final class Requirement {
		String label = "";
		List<String> items = new ArrayList<>();
		int count = 1;

		int owned(InventoryAnalysis a) {
			int n = 0;
			for (String id : items) {
				Item item = item(id);
				if (item != null) n += a.count(item);
			}
			return n;
		}
	}

	static final class Goal {
		String id = UUID.randomUUID().toString();
		String name = "";
		long created = System.currentTimeMillis();
		List<Requirement> requirements = new ArrayList<>();

		/** 0..1 completion: owned items (capped per requirement) over required items. */
		double progress(InventoryAnalysis a) {
			long need = 0, have = 0;
			for (Requirement r : requirements) {
				need += r.count;
				have += Math.min(r.count, r.owned(a));
			}
			return need == 0 ? 0 : have / (double) need;
		}
	}

	final HotbarRule[] hotbar = new HotbarRule[9];
	final List<Watch> watch = new ArrayList<>();
	final List<Goal> goals = new ArrayList<>();
	@Nullable String activeGoal;
	private final Path rulesFile;
	private @Nullable WorldStorage world;
	private boolean rulesDirty, goalsDirty;

	InventoryData(Path globalDir) {
		this.rulesFile = globalDir.resolve("rules.json");
		for (int i = 0; i < 9; i++) hotbar[i] = new HotbarRule();
	}

	static @Nullable Item item(String id) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) return null;
		Item item = BuiltInRegistries.ITEM.getValue(key);
		return item == Items.AIR ? null : item;
	}

	void loadRules() {
		JsonObject root = BloomStorage.readObject(rulesFile);
		if (root == null) return;
		try {
			if (root.has("hotbar")) {
				HotbarRule[] read = BloomStorage.GSON.fromJson(root.get("hotbar"), HotbarRule[].class);
				for (int i = 0; i < 9 && read != null && i < read.length; i++) {
					if (read[i] != null) {
						if (read[i].kind == null) read[i].kind = "none";
						if (read[i].item == null) read[i].item = "";
						hotbar[i] = read[i];
					}
				}
			}
			if (root.has("watch")) {
				Watch[] read = BloomStorage.GSON.fromJson(root.get("watch"), Watch[].class);
				if (read != null) for (Watch w : read) if (w != null && w.item != null) watch.add(w);
			}
		} catch (RuntimeException e) {
			// Keep defaults for unreadable parts.
		}
	}

	void openWorld(@Nullable WorldStorage storage) {
		goals.clear();
		activeGoal = null;
		world = storage;
		if (storage == null) return;
		JsonObject root = storage.readObject("goals.json");
		if (root == null) return;
		try {
			if (root.has("active") && !root.get("active").isJsonNull()) activeGoal = root.get("active").getAsString();
			Goal[] read = BloomStorage.GSON.fromJson(root.get("goals"), Goal[].class);
			if (read != null) {
				for (Goal g : read) {
					if (g == null) continue;
					if (g.requirements == null) g.requirements = new ArrayList<>();
					g.requirements.removeIf(r -> r == null || r.items == null);
					goals.add(g);
				}
			}
		} catch (RuntimeException ignored) {
			// Unreadable goals: start empty, the file is kept as .broken by the storage layer when it fails to parse.
		}
	}

	void markRules() {
		rulesDirty = true;
	}

	void markGoals() {
		goalsDirty = true;
	}

	void save(boolean force) {
		if (rulesDirty || force) {
			rulesDirty = false;
			JsonObject root = new JsonObject();
			root.addProperty("schema", SCHEMA);
			root.add("hotbar", BloomStorage.GSON.toJsonTree(hotbar));
			root.add("watch", BloomStorage.GSON.toJsonTree(watch));
			BloomStorage.writeJsonAsync(rulesFile, root);
		}
		if ((goalsDirty || force) && world != null) {
			goalsDirty = false;
			JsonObject root = new JsonObject();
			root.addProperty("schema", SCHEMA);
			if (activeGoal != null) root.addProperty("active", activeGoal);
			root.add("goals", BloomStorage.GSON.toJsonTree(goals));
			world.write("goals.json", root);
		}
	}

	boolean hasWorld() {
		return world != null;
	}

	@Nullable Goal active() {
		if (activeGoal == null) return null;
		for (Goal g : goals) if (g.id.equals(activeGoal)) return g;
		return null;
	}

	@Nullable Watch watchFor(String itemId) {
		for (Watch w : watch) if (w.item.equals(itemId)) return w;
		return null;
	}

	Watch watchOrCreate(String itemId) {
		Watch w = watchFor(itemId);
		if (w == null) {
			w = new Watch();
			w.item = itemId;
			watch.add(w);
		}
		return w;
	}
}
