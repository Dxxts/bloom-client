package com.bloomclient.client.mods.basememory;

import com.bloomclient.client.mods.basememory.MemoryRecords.Offer;
import com.bloomclient.client.mods.basememory.MemoryRecords.SlotStack;
import com.bloomclient.client.mods.basememory.MemoryRecords.StoredContainer;
import com.bloomclient.client.mods.basememory.MemoryRecords.StoredVillager;
import com.bloomclient.client.ui.Tr;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Search over everything Base Memory remembers. The index (one entry per
 * remembered stack, including stacks inside shulker boxes and bundles) is
 * rebuilt only when the data or the game language changes; a query is a
 * linear scan of pre-lowercased text, which stays well under a millisecond
 * for thousands of stacks.
 */
final class MemorySearch {
	/** One remembered stack. {@code holder} is the shulker box or bundle it sits in, if any. */
	record Entry(StoredContainer container, int slot, ItemStack stack, @Nullable ItemStack holder, String text) {
	}

	/** Where an item is: one container, with how many and in which slots. */
	static final class Place {
		final StoredContainer container;
		int count;
		final List<Integer> slots = new ArrayList<>();
		@Nullable ItemStack holder;
		/** Enchantments of the first matching stack ("Mending"), empty if none. */
		@Nullable String detail;

		Place(StoredContainer container) {
			this.container = container;
		}
	}

	/** One kind of item and every place it is in. */
	static final class Group {
		final Item item;
		final ItemStack icon;
		final String name;
		int total;
		int carried;
		final List<Place> places = new ArrayList<>();
		final Map<String, Place> byContainer = new LinkedHashMap<>();
		boolean nameMatch;

		Group(Item item, ItemStack icon) {
			this.item = item;
			this.icon = icon.copyWithCount(1);
			this.name = new ItemStack(item).getHoverName().getString();
		}
	}

	record TradeHit(StoredVillager villager, Offer offer) {
	}

	record Result(List<Group> groups, List<TradeHit> trades) {
		static final Result EMPTY = new Result(List.of(), List.of());

		boolean isEmpty() {
			return groups.isEmpty() && trades.isEmpty();
		}
	}

	private final BaseMemoryMod mod;
	private List<Entry> entries = List.of();
	private int builtVersion = -1;
	private String builtLanguage = "";
	private @Nullable MemoryData builtFor;

	MemorySearch(BaseMemoryMod mod) {
		this.mod = mod;
	}

	private List<Entry> entries(MemoryData data) {
		String lang = Tr.language();
		if (data != builtFor || data.version != builtVersion || !lang.equals(builtLanguage)) {
			List<Entry> list = new ArrayList<>();
			boolean inside = mod.searchInside.on();
			for (StoredContainer c : data.containers.values()) {
				for (SlotStack s : c.slots) {
					list.add(new Entry(c, s.slot(), s.stack(), null, Stacks.searchText(s.stack())));
					if (inside) {
						Stacks.forEachInside(s.stack(), (holder, inner) -> list.add(new Entry(c, s.slot(), inner, holder, Stacks.searchText(inner))));
					}
				}
			}
			entries = list;
			builtFor = data;
			builtVersion = data.version;
			builtLanguage = lang;
		}
		return entries;
	}

	/** Forces a rebuild (after changing whether shulker contents are searched). */
	void invalidate() {
		builtVersion = -1;
	}

	static String[] tokens(String query) {
		String q = query.trim().toLowerCase(Locale.ROOT);
		return q.isEmpty() ? new String[0] : q.split("\\s+");
	}

	static boolean matches(String text, String[] tokens) {
		for (String t : tokens) if (!text.contains(t)) return false;
		return true;
	}

	Result search(MemoryData data, String query) {
		String[] tokens = tokens(query);
		if (tokens.length == 0) return Result.EMPTY;
		return collect(data, e -> matches(e.text, tokens), stack -> matches(Stacks.searchText(stack), tokens), tokens);
	}

	/** Every place holding one of {@code items} (used for goals). */
	Result find(MemoryData data, List<Item> items) {
		if (items.isEmpty()) return Result.EMPTY;
		return collect(data, e -> items.contains(e.stack.getItem()), stack -> items.contains(stack.getItem()), null);
	}

	private Result collect(MemoryData data, Predicate<Entry> entryMatch, Predicate<ItemStack> stackMatch, String @Nullable [] tokens) {
		Map<Item, Group> groups = new LinkedHashMap<>();
		for (Entry e : entries(data)) {
			if (!entryMatch.test(e)) continue;
			Group g = groups.computeIfAbsent(e.stack.getItem(), item -> new Group(item, e.stack));
			// Loose stacks and stacks inside a shulker box of the same container are separate places: they are found differently.
			Place p = g.byContainer.computeIfAbsent(e.holder == null ? e.container.key : e.container.key + "|in", k -> {
				Place place = new Place(e.container);
				g.places.add(place);
				return place;
			});
			p.count += e.stack.getCount();
			if (p.detail == null) p.detail = Stacks.enchantments(e.stack);
			if (e.holder != null) p.holder = e.holder;
			else if (!p.slots.contains(e.slot)) p.slots.add(e.slot);
			g.total += e.stack.getCount();
		}
		// What the player carries right now, including inside shulker boxes and bundles.
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			Inventory inv = player.getInventory();
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty()) continue;
				if (stackMatch.test(s)) groups.computeIfAbsent(s.getItem(), item -> new Group(item, s)).carried += s.getCount();
				if (mod.searchInside.on()) {
					Stacks.forEachInside(s, (holder, inner) -> {
						if (stackMatch.test(inner)) groups.computeIfAbsent(inner.getItem(), item -> new Group(item, inner)).carried += inner.getCount();
					});
				}
			}
		}
		List<Group> list = new ArrayList<>(groups.values());
		for (Group g : list) {
			g.places.sort(Comparator.comparingDouble(p -> mod.distanceTo(p.container.dim, p.container.x, p.container.y, p.container.z)));
			if (tokens != null) {
				String name = g.name.toLowerCase(Locale.ROOT);
				String id = BuiltInRegistries.ITEM.getKey(g.item).getPath().replace('_', ' ');
				g.nameMatch = matches(name, tokens) || matches(id, tokens);
			}
		}
		// Items whose own name matches first (searching "iron" lists iron ingots before iron pickaxes with a matching enchantment), then the most plentiful.
		list.sort(Comparator.comparing((Group g) -> !g.nameMatch).thenComparing(g -> -(g.total + g.carried)).thenComparing(g -> g.name));
		if (list.size() > 40) list = new ArrayList<>(list.subList(0, 40));

		List<TradeHit> trades = new ArrayList<>();
		if (tokens != null) {
			for (StoredVillager v : data.villagers.values()) {
				for (Offer o : v.offers) if (matches(Stacks.searchText(o.result()), tokens)) trades.add(new TradeHit(v, o));
			}
			trades.sort(Comparator.comparingDouble(t -> mod.distanceTo(t.villager.dim, t.villager.x, t.villager.y, t.villager.z)));
			if (trades.size() > 30) trades = new ArrayList<>(trades.subList(0, 30));
		}
		return new Result(list, trades);
	}
}
