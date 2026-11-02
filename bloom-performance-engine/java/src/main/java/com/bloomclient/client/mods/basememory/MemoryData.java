package com.bloomclient.client.mods.basememory;

import com.bloomclient.client.api.storage.WorldStorage;
import com.bloomclient.client.mods.basememory.MemoryRecords.Offer;
import com.bloomclient.client.mods.basememory.MemoryRecords.SlotStack;
import com.bloomclient.client.mods.basememory.MemoryRecords.StoredContainer;
import com.bloomclient.client.mods.basememory.MemoryRecords.StoredVillager;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Everything Base Memory knows about one world. Files in
 * {@code .bloom/worlds/<world>/bloom-base-memory/}: {@code containers.json}
 * and {@code villagers.json}, each with a {@code schema} field. Stacks are
 * stored with all their components (enchantments, names, shulker contents),
 * encoded once when the container is recorded.
 */
final class MemoryData {
	static final int SCHEMA = 1;

	final WorldStorage storage;
	final Map<String, StoredContainer> containers = new LinkedHashMap<>();
	final Map<String, StoredVillager> villagers = new LinkedHashMap<>();
	/** "dim|packed position" of every block occupied by a remembered container → container key. */
	private final Map<String, String> byPosition = new HashMap<>();
	/** Encoded JSON per container key, so saving never re-encodes stacks. */
	private final Map<String, JsonObject> encoded = new HashMap<>();
	private boolean containersDirty, villagersDirty;
	/** Incremented on every change, so the search index knows when to rebuild. */
	int version;

	MemoryData(WorldStorage storage) {
		this.storage = storage;
	}

	// ---------------------------------------------------------------- containers

	@Nullable StoredContainer containerAt(String dim, BlockPos pos) {
		String key = byPosition.get(dim + "|" + pos.asLong());
		return key == null ? null : containers.get(key);
	}

	/** Stores a container, replacing any remembered container on the same blocks (a single chest that became double, and so on). */
	void put(StoredContainer c, RegistryOps<JsonElement> ops) {
		if (!c.ender()) {
			removeAt(c.dim, new BlockPos(c.x, c.y, c.z), c.key);
			if (c.twin) removeAt(c.dim, new BlockPos(c.tx, c.ty, c.tz), c.key);
		}
		containers.remove(c.key);
		containers.put(c.key, c);
		encoded.put(c.key, encode(c, ops));
		index(c);
		containersDirty = true;
		version++;
	}

	private void removeAt(String dim, BlockPos pos, String keep) {
		String key = byPosition.get(dim + "|" + pos.asLong());
		if (key != null && !key.equals(keep)) removeContainer(key);
	}

	void removeContainer(String key) {
		StoredContainer c = containers.remove(key);
		if (c == null) return;
		encoded.remove(key);
		if (!c.ender()) {
			byPosition.remove(c.dim + "|" + new BlockPos(c.x, c.y, c.z).asLong());
			if (c.twin) byPosition.remove(c.dim + "|" + new BlockPos(c.tx, c.ty, c.tz).asLong());
		}
		containersDirty = true;
		version++;
	}

	private void index(StoredContainer c) {
		if (c.ender()) return;
		byPosition.put(c.dim + "|" + new BlockPos(c.x, c.y, c.z).asLong(), c.key);
		if (c.twin) byPosition.put(c.dim + "|" + new BlockPos(c.tx, c.ty, c.tz).asLong(), c.key);
	}

	// ---------------------------------------------------------------- villagers

	void putVillager(StoredVillager v) {
		villagers.put(v.uuid, v);
		villagersDirty = true;
		version++;
	}

	void removeVillager(String uuid) {
		if (villagers.remove(uuid) != null) {
			villagersDirty = true;
			version++;
		}
	}

	/** A villager moved or changed while in view: saved with the next periodic save, the index is unaffected. */
	void villagerMoved() {
		villagersDirty = true;
	}

	void clearAll() {
		containers.clear();
		villagers.clear();
		byPosition.clear();
		encoded.clear();
		containersDirty = villagersDirty = true;
		version++;
	}

	// ---------------------------------------------------------------- persistence

	private static JsonObject encode(StoredContainer c, RegistryOps<JsonElement> ops) {
		JsonObject o = new JsonObject();
		o.addProperty("key", c.key);
		o.addProperty("dim", c.dim);
		o.addProperty("x", c.x);
		o.addProperty("y", c.y);
		o.addProperty("z", c.z);
		if (c.twin) {
			o.addProperty("tx", c.tx);
			o.addProperty("ty", c.ty);
			o.addProperty("tz", c.tz);
		}
		o.addProperty("block", c.block);
		if (c.customName != null) o.addProperty("name", c.customName);
		o.addProperty("seen", c.seen);
		o.addProperty("size", c.size);
		JsonArray slots = new JsonArray();
		for (SlotStack s : c.slots) {
			JsonElement stack = Stacks.encode(s.stack(), ops);
			if (stack == null) continue;
			JsonObject so = new JsonObject();
			so.addProperty("s", s.slot());
			so.add("i", stack);
			slots.add(so);
		}
		o.add("slots", slots);
		return o;
	}

	private static JsonObject encode(StoredVillager v, RegistryOps<JsonElement> ops) {
		JsonObject o = new JsonObject();
		o.addProperty("uuid", v.uuid);
		o.addProperty("dim", v.dim);
		o.addProperty("x", v.x);
		o.addProperty("y", v.y);
		o.addProperty("z", v.z);
		o.addProperty("profession", v.profession);
		o.addProperty("level", v.level);
		if (v.name != null) o.addProperty("name", v.name);
		o.addProperty("seen", v.seen);
		o.addProperty("tradesSeen", v.tradesSeen);
		JsonArray offers = new JsonArray();
		for (Offer offer : v.offers) {
			JsonElement a = Stacks.encode(offer.costA(), ops), r = Stacks.encode(offer.result(), ops);
			if (a == null || r == null) continue;
			JsonObject oo = new JsonObject();
			oo.add("a", a);
			JsonElement b = Stacks.encode(offer.costB(), ops);
			if (b != null) oo.add("b", b);
			oo.add("r", r);
			oo.addProperty("uses", offer.uses());
			oo.addProperty("max", offer.maxUses());
			offers.add(oo);
		}
		o.add("offers", offers);
		return o;
	}

	void load(RegistryOps<JsonElement> ops) {
		JsonObject root = storage.readObject("containers.json");
		if (root != null && root.has("containers") && root.get("containers").isJsonArray()) {
			for (JsonElement e : root.getAsJsonArray("containers")) {
				try {
					JsonObject o = e.getAsJsonObject();
					StoredContainer c = new StoredContainer(o.get("key").getAsString());
					c.dim = o.get("dim").getAsString();
					c.x = o.get("x").getAsInt();
					c.y = o.get("y").getAsInt();
					c.z = o.get("z").getAsInt();
					if (o.has("tx")) {
						c.twin = true;
						c.tx = o.get("tx").getAsInt();
						c.ty = o.get("ty").getAsInt();
						c.tz = o.get("tz").getAsInt();
					}
					c.block = o.has("block") ? o.get("block").getAsString() : "minecraft:chest";
					c.customName = o.has("name") ? o.get("name").getAsString() : null;
					c.seen = o.has("seen") ? o.get("seen").getAsLong() : 0;
					c.size = o.has("size") ? o.get("size").getAsInt() : 27;
					if (o.has("slots")) {
						for (JsonElement se : o.getAsJsonArray("slots")) {
							JsonObject so = se.getAsJsonObject();
							ItemStack stack = Stacks.decode(so.get("i"), ops);
							if (!stack.isEmpty()) c.slots.add(new SlotStack(so.get("s").getAsInt(), stack));
						}
					}
					containers.put(c.key, c);
					// Keep the original JSON: items that can't be read now (a mod removed for a session) are not lost on save.
					encoded.put(c.key, o);
					index(c);
				} catch (RuntimeException ignored) {
					// Skip a malformed entry.
				}
			}
		}
		JsonObject vr = storage.readObject("villagers.json");
		if (vr != null && vr.has("villagers") && vr.get("villagers").isJsonArray()) {
			for (JsonElement e : vr.getAsJsonArray("villagers")) {
				try {
					JsonObject o = e.getAsJsonObject();
					StoredVillager v = new StoredVillager(o.get("uuid").getAsString());
					v.dim = o.get("dim").getAsString();
					v.x = o.get("x").getAsInt();
					v.y = o.get("y").getAsInt();
					v.z = o.get("z").getAsInt();
					v.profession = o.has("profession") ? o.get("profession").getAsString() : "minecraft:none";
					v.level = o.has("level") ? o.get("level").getAsInt() : 1;
					v.name = o.has("name") ? o.get("name").getAsString() : null;
					v.seen = o.has("seen") ? o.get("seen").getAsLong() : 0;
					v.tradesSeen = o.has("tradesSeen") ? o.get("tradesSeen").getAsLong() : v.seen;
					if (o.has("offers")) {
						for (JsonElement oe : o.getAsJsonArray("offers")) {
							JsonObject oo = oe.getAsJsonObject();
							ItemStack a = Stacks.decode(oo.get("a"), ops), r = Stacks.decode(oo.get("r"), ops);
							if (a.isEmpty() || r.isEmpty()) continue;
							v.offers.add(new Offer(a, Stacks.decode(oo.get("b"), ops), r, oo.has("uses") ? oo.get("uses").getAsInt() : 0,
									oo.has("max") ? oo.get("max").getAsInt() : 1));
						}
					}
					villagers.put(v.uuid, v);
				} catch (RuntimeException ignored) {
					// Skip a malformed entry.
				}
			}
		}
		version++;
	}

	void save(boolean force) {
		if (containersDirty || force) {
			containersDirty = false;
			JsonObject root = new JsonObject();
			root.addProperty("schema", SCHEMA);
			JsonArray arr = new JsonArray();
			for (String key : containers.keySet()) {
				JsonObject o = encoded.get(key);
				if (o != null) arr.add(o);
			}
			root.add("containers", arr);
			storage.write("containers.json", root);
		}
		if (villagersDirty || force) {
			villagersDirty = false;
			RegistryOps<JsonElement> ops = Stacks.ops();
			if (ops == null) {
				villagersDirty = true;
				return;
			}
			JsonObject root = new JsonObject();
			root.addProperty("schema", SCHEMA);
			JsonArray arr = new JsonArray();
			for (StoredVillager v : villagers.values()) arr.add(encode(v, ops));
			root.add("villagers", arr);
			storage.write("villagers.json", root);
		}
	}

	/** Copy of the remembered containers, for lists that sort. */
	List<StoredContainer> containerList() {
		return new ArrayList<>(containers.values());
	}
}
