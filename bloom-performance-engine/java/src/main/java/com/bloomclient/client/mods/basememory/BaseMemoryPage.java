package com.bloomclient.client.mods.basememory;

import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.ModPage;
import com.bloomclient.client.mods.basememory.MemoryRecords.Offer;
import com.bloomclient.client.mods.basememory.MemoryRecords.SlotStack;
import com.bloomclient.client.mods.basememory.MemoryRecords.StoredContainer;
import com.bloomclient.client.mods.basememory.MemoryRecords.StoredVillager;
import com.bloomclient.client.mods.basememory.MemorySearch.Group;
import com.bloomclient.client.mods.basememory.MemorySearch.Place;
import com.bloomclient.client.mods.basememory.MemorySearch.Result;
import com.bloomclient.client.mods.basememory.MemorySearch.TradeHit;
import com.bloomclient.client.mods.inventory.InventoryManagerMod;
import com.bloomclient.client.mods.worldtracker.WorldTrackerMod;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Feedback;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.TextInput;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.client.ui.Ui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jspecify.annotations.Nullable;

/** The Base Memory page: Search (with goal help and recent containers), Containers and Villagers. */
final class BaseMemoryPage implements ModPage {
	private static final int PLACES_SHOWN = 3;

	private final BaseMemoryMod mod;
	private int tab;
	private final TextInput query = new TextInput().maxLength(48);
	private final TextInput containerFilter = new TextInput().maxLength(48);
	private boolean focusSearch;
	private @Nullable String lastQuery;
	private int lastVersion = -1;
	private long lastSearch;
	private Result result = Result.EMPTY;
	private final Set<String> expandedGroups = new HashSet<>();
	private @Nullable String expanded;
	private int containerSort; // 0 distance, 1 recent

	BaseMemoryPage(BaseMemoryMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "base-memory";
	}

	@Override
	public String title() {
		return "bloom.bm.name";
	}

	@Override
	public Icon icon() {
		return Icon.CHEST;
	}

	@Override
	public void onOpen() {
		focusSearch = true;
		lastQuery = null;
		// Development preview only (see DevPreview): opens the page with a query already typed.
		String preview = System.getProperty("bloom.client.preview.bm.query");
		if (preview != null && query.isEmpty()) query.set(preview);
		String previewTab = System.getProperty("bloom.client.preview.bm.tab");
		if (previewTab != null) {
			tab = Integer.parseInt(previewTab);
			MemoryData d = mod.data;
			if (d != null && tab == 1 && !d.containers.isEmpty()) expanded = d.containers.keySet().iterator().next();
			if (d != null && tab == 2 && !d.villagers.isEmpty()) expanded = d.villagers.keySet().iterator().next();
		}
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		MemoryData d = mod.data;
		if (d == null) {
			ui.textCentered(Tr.t("bloom.bm.no_world"), x + w / 2, y + 40, BloomTheme.TEXT_MUTED);
			return;
		}
		String[] tabs = {Tr.t("bloom.bm.tab.search"), Tr.t("bloom.bm.tab.containers", d.containers.size()), Tr.t("bloom.bm.tab.villagers", d.villagers.size())};
		int tw = Math.min(w - 120, 380);
		int newTab = ui.segmented("bm.tab", x, y, tw, 16, tabs, tab);
		if (newTab != tab) {
			tab = newTab;
			expanded = null;
			if (tab == 0) focusSearch = true;
		}
		if (mod.guide != null) {
			if (ui.button("bm.stop", x + w - 112, y, 112, 16, Tr.t("bloom.bm.guide.stop"), Icon.CLOSE, Ui.Kind.SECONDARY, true)) mod.stopGuide();
		}
		int cy = y + 24, ch = h - 24;
		switch (tab) {
			case 0 -> search(ui, d, x, cy, w, ch);
			case 1 -> containers(ui, d, x, cy, w, ch);
			default -> villagers(ui, d, x, cy, w, ch);
		}
	}

	// ---------------------------------------------------------------- shared bits

	private static int header(Ui ui, String text, int x, int y) {
		ui.text(text.toUpperCase(Locale.ROOT), x, y + 2, BloomTheme.TEXT_MUTED);
		return y + 15;
	}

	private static void item(Ui ui, ItemStack stack, int x, int y, boolean count) {
		if (stack.isEmpty()) return;
		ui.g.item(stack, x, y);
		if (count && stack.getCount() > 1) ui.g.itemDecorations(ui.font, stack, x, y);
	}

	private String distance(String dim, double x, double y, double z, GameInfo g) {
		double d = mod.distanceTo(dim, x, y, z);
		if (d == Double.MAX_VALUE) return WorldTrackerMod.dimensionName(dim);
		return WorldTrackerMod.arrow(x + 0.5 - g.x(), z + 0.5 - g.z(), g.yaw()) + " " + WorldTrackerMod.formatDistance(d);
	}

	private boolean guideButton(Ui ui, String id, int x, int y, boolean active) {
		return ui.iconButton(id, x, y, 16, Icon.TARGET, Tr.t(active ? "bloom.bm.guide.active" : "bloom.bm.guide.go"), active, true);
	}

	private boolean guiding(StoredContainer c) {
		BaseMemoryMod.Guide g = mod.guide;
		return g != null && c.key.equals(g.containerKey);
	}

	private boolean guiding(StoredVillager v) {
		BaseMemoryMod.Guide g = mod.guide;
		return g != null && v.uuid.equals(g.villager);
	}

	// ---------------------------------------------------------------- search

	private void search(Ui ui, MemoryData d, int x, int y, int w, int h) {
		ui.textField("bm.q", x, y, w, 18, query, Tr.t("bloom.bm.search.placeholder"), Icon.SEARCH);
		if (focusSearch) {
			ui.focus("bm.q", query);
			focusSearch = false;
		}
		String q = query.value().trim();
		// Distances and what the player carries change while the page is open: refresh once a second.
		if (!q.equals(lastQuery) || d.version != lastVersion || ui.now - lastSearch > 1000) {
			if (!q.equals(lastQuery)) expandedGroups.clear();
			lastQuery = q;
			lastVersion = d.version;
			lastSearch = ui.now;
			result = q.isEmpty() ? Result.EMPTY : mod.search.search(d, q);
		}
		int ly = y + 26, lh = h - 26;
		if (q.isEmpty()) {
			home(ui, d, x, ly, w, lh);
			return;
		}
		if (result.isEmpty()) {
			ui.textCentered(Tr.t("bloom.bm.search.none", q), x + w / 2, ly + 24, BloomTheme.TEXT_MUTED);
			ui.textCentered(Tr.t("bloom.bm.search.none_hint"), x + w / 2, ly + 38, BloomTheme.TEXT_MUTED);
			return;
		}
		GameInfo g = mod.ctx().game();
		int off = ui.beginScroll("bm.results", x, ly, w, lh);
		int cy = ly - off, cw = w - 8;
		Runnable action = null;
		for (Group group : result.groups()) {
			Runnable a = groupRows(ui, group, x, cy, cw, g);
			if (a != null) action = a;
			cy += groupHeight(group);
		}
		if (!result.trades().isEmpty()) {
			cy += 4;
			cy = header(ui, Tr.t("bloom.bm.search.trades"), x, cy);
			for (TradeHit t : result.trades()) {
				Runnable a = tradeRow(ui, t.villager(), t.offer(), x, cy, cw, g, true);
				if (a != null) action = a;
				cy += 32;
			}
		}
		ui.endScroll("bm.results", x, ly, w, lh, cy + off - ly);
		if (action != null) action.run();
	}

	private int groupHeight(Group group) {
		int shown = expandedGroups.contains(group.name) ? group.places.size() : Math.min(PLACES_SHOWN, group.places.size());
		int h = 26 + shown * 20;
		if (group.places.size() > PLACES_SHOWN) h += 14;
		return h + 6;
	}

	private @Nullable Runnable groupRows(Ui ui, Group group, int x, int y, int w, GameInfo g) {
		Runnable action = null;
		ui.round(x, y, w, groupHeight(group) - 6, 5, BloomTheme.SURFACE);
		item(ui, group.icon, x + 6, y + 4, false);
		ui.text(ui.ellipsize(group.name, w - 200), x + 28, y + 9, BloomTheme.TEXT);
		int rx = x + w - 8;
		if (group.carried > 0) {
			String chip = Tr.t("bloom.bm.search.carried", group.carried);
			int cwid = ui.width(chip) + 10;
			ui.round(rx - cwid, y + 5, cwid, 14, 7, BloomTheme.ACCENT_SOFT);
			ui.text(chip, rx - cwid + 5, y + 8, BloomTheme.ACCENT);
			rx -= cwid + 6;
		}
		if (group.total > 0) {
			ui.textRight(Tr.t(group.places.size() == 1 ? "bloom.bm.search.total_one" : "bloom.bm.search.total", group.total, group.places.size()), rx, y + 9,
					BloomTheme.TEXT_DIM);
		} else {
			ui.textRight(Tr.t("bloom.bm.search.only_carried"), rx, y + 9, BloomTheme.TEXT_MUTED);
		}
		int py = y + 24;
		boolean all = expandedGroups.contains(group.name);
		int shown = all ? group.places.size() : Math.min(PLACES_SHOWN, group.places.size());
		for (int i = 0; i < shown; i++) {
			Place p = group.places.get(i);
			Runnable a = placeRow(ui, group, p, x + 6, py, w - 12, g);
			if (a != null) action = a;
			py += 20;
		}
		if (group.places.size() > PLACES_SHOWN) {
			String more = all ? Tr.t("bloom.bm.search.fewer") : Tr.t("bloom.bm.search.more", group.places.size() - PLACES_SHOWN);
			int mw = ui.width(more);
			boolean hov = ui.hovered(x + 28, py, mw, 12);
			ui.text(more, x + 28, py + 1, hov ? BloomTheme.ACCENT_HOVER : BloomTheme.ACCENT);
			if (ui.clicked(x + 28, py, mw, 12)) {
				if (all) expandedGroups.remove(group.name);
				else expandedGroups.add(group.name);
			}
		}
		return action;
	}

	private @Nullable Runnable placeRow(Ui ui, Group group, Place p, int x, int y, int w, GameInfo g) {
		StoredContainer c = p.container;
		String id = "bm.pl." + group.name + "." + c.key + (p.holder != null ? ".in" : "");
		boolean hov = ui.hovered(x, y, w, 18);
		if (hov) ui.round(x, y, w, 18, 3, BloomTheme.SURFACE_HOVER);
		ui.g.pose().pushMatrix();
		ui.g.pose().translate(x + 4, y + 1);
		ui.g.pose().scale(0.75f, 0.75f);
		ui.g.item(c.icon(), 0, 0);
		ui.g.pose().popMatrix();
		String hint = BaseMemoryMod.slotHint(c, p.slots, p.holder);
		if (p.detail != null && !p.detail.isEmpty()) hint = p.detail + (hint.isEmpty() ? "" : " · " + hint);
		String name = mod.placeName(c);
		int textW = w - 210;
		ui.text(ui.ellipsize(name, textW), x + 22, y + 5, BloomTheme.TEXT);
		int nw = Math.min(ui.width(name), textW);
		ui.text(ui.ellipsize("× " + p.count + (hint.isEmpty() ? "" : " · " + hint), Math.max(0, textW - nw + 60)), x + 28 + nw, y + 5,
				p.holder != null ? BloomTheme.WARNING : BloomTheme.TEXT_DIM);
		ui.textRight(BaseMemoryMod.ago(c.seen), x + w - 118, y + 5, BloomTheme.TEXT_MUTED);
		ui.text(ui.ellipsize(c.ender() ? Tr.t("bloom.bm.ender_anywhere") : distance(c.dim, c.x, c.y, c.z, g), 74), x + w - 108, y + 5,
				BloomTheme.ACCENT);
		if (!c.ender() && guideButton(ui, id + ".go", x + w - 18, y + 1, guiding(c))) {
			return guiding(c) ? mod::stopGuide : () -> mod.guideTo(c, group.item);
		}
		if (hov && p.holder != null) ui.tooltip(Tr.t("bloom.bm.inside_hint"));
		return null;
	}

	private @Nullable Runnable tradeRow(Ui ui, StoredVillager v, Offer o, int x, int y, int w, GameInfo g, boolean showVillager) {
		String id = "bm.tr." + v.uuid + "." + v.offers.indexOf(o);
		ui.round(x, y, w, 28, 5, BloomTheme.SURFACE);
		item(ui, o.costA(), x + 6, y + 6, true);
		int cx = x + 24;
		if (!o.costB().isEmpty()) {
			ui.text("+", cx, y + 10, BloomTheme.TEXT_MUTED);
			item(ui, o.costB(), cx + 8, y + 6, true);
			cx += 26;
		}
		ui.icon(Icon.RIGHT, cx + 1, y + 10, BloomTheme.TEXT_MUTED);
		item(ui, o.result(), cx + 12, y + 6, true);
		int tx = cx + 34, textW = w - (tx - x) - 130;
		ui.text(ui.ellipsize(Stacks.label(o.result()), textW), tx, y + 5, o.outOfStock() ? BloomTheme.TEXT_DIM : BloomTheme.TEXT);
		String price = o.costA().getCount() + " " + o.costA().getHoverName().getString()
				+ (o.costB().isEmpty() ? "" : " + " + o.costB().getCount() + " " + o.costB().getHoverName().getString());
		String sub = showVillager ? v.displayName() + " · " + v.levelName() : price;
		ui.text(ui.ellipsize(sub, textW), tx, y + 17, BloomTheme.TEXT_MUTED);
		int rx = x + w - 120;
		if (o.outOfStock()) {
			ui.text(Tr.t("bloom.bm.trade.out"), rx, y + 5, BloomTheme.WARNING);
		} else if (showVillager) {
			ui.text(ui.ellipsize(price, 96), rx, y + 5, BloomTheme.TEXT_DIM);
		}
		if (showVillager) {
			ui.text(ui.ellipsize(distance(v.dim, v.x, v.y, v.z, g), 96), rx, y + 17, BloomTheme.ACCENT);
			if (guideButton(ui, id + ".go", x + w - 22, y + 6, guiding(v))) {
				return guiding(v) ? mod::stopGuide : () -> mod.guideTo(v, o.result().getItem());
			}
		}
		return null;
	}

	/** Search page with no query: what Base Memory knows, help for the active goal, the latest containers. */
	private void home(Ui ui, MemoryData d, int x, int y, int w, int h) {
		GameInfo g = mod.ctx().game();
		int off = ui.beginScroll("bm.home", x, y, w, h);
		int cy = y - off, cw = w - 8;
		int items = 0, trades = 0;
		for (StoredContainer c : d.containers.values()) items += c.itemCount();
		for (StoredVillager v : d.villagers.values()) trades += v.offers.size();
		String[][] stats = {{Tr.t("bloom.bm.stat.containers"), Integer.toString(d.containers.size())}, {Tr.t("bloom.bm.stat.items"), Integer.toString(items)},
				{Tr.t("bloom.bm.stat.trades"), Integer.toString(trades)}};
		int gap = 8, cardW = (cw - gap * 2) / 3;
		for (int i = 0; i < 3; i++) {
			int cx = x + i * (cardW + gap);
			ui.round(cx, cy, cardW, 40, 5, BloomTheme.SURFACE);
			ui.text(stats[i][0], cx + 9, cy + 7, BloomTheme.TEXT_MUTED);
			ui.textScaled(stats[i][1], cx + 9, cy + 18, 1.5f, BloomTheme.TEXT);
		}
		cy += 50;
		Runnable action = null;

		String goal = InventoryManagerMod.activeGoalName();
		List<InventoryManagerMod.GoalNeed> needs = goal == null ? List.of() : InventoryManagerMod.activeGoalNeeds();
		if (!needs.isEmpty()) {
			cy = header(ui, Tr.t("bloom.bm.goal.title", goal), x, cy);
			for (InventoryManagerMod.GoalNeed need : needs.subList(0, Math.min(6, needs.size()))) {
				Result r = mod.search.find(d, need.items());
				Group best = null;
				for (Group gr : r.groups()) if (!gr.places.isEmpty() && (best == null || gr.total > best.total)) best = gr;
				ui.round(x, cy, cw, 24, 4, BloomTheme.SURFACE);
				item(ui, new ItemStack(need.items().get(0)), x + 5, cy + 4, false);
				ui.text(ui.ellipsize(need.label(), cw / 2 - 40), x + 26, cy + 8, BloomTheme.TEXT);
				ui.text(Tr.t("bloom.bm.goal.missing", need.missing()), x + cw / 2 - 10, cy + 8, BloomTheme.WARNING);
				if (best == null) {
					ui.textRight(Tr.t("bloom.bm.goal.not_seen"), x + cw - 8, cy + 8, BloomTheme.TEXT_MUTED);
				} else {
					Place p = best.places.get(0);
					String where = Tr.t("bloom.bm.goal.found", best.total, mod.placeName(p.container));
					ui.textRight(ui.ellipsize(where, cw / 2 - 70), x + cw - 30, cy + 8, best.total >= need.missing() ? BloomTheme.ACCENT : BloomTheme.TEXT_DIM);
					StoredContainer c = p.container;
					Group target = best;
					if (!c.ender() && guideButton(ui, "bm.goal." + need.label(), x + cw - 22, cy + 4, guiding(c))) {
						action = guiding(c) ? mod::stopGuide : () -> mod.guideTo(c, target.item);
					}
				}
				cy += 28;
			}
			cy += 6;
		}

		if (d.containers.isEmpty() && d.villagers.isEmpty()) {
			cy += 10;
			ui.icon(Icon.CHEST, x + cw / 2 - 4, cy, BloomTheme.ACCENT);
			cy += 18;
			ui.textCentered(Tr.t("bloom.bm.empty.title"), x + cw / 2, cy, BloomTheme.TEXT);
			cy += 16;
			for (String line : ui.wrap(Tr.t("bloom.bm.empty.body"), Math.min(cw, 360))) {
				ui.textCentered(line, x + cw / 2, cy, BloomTheme.TEXT_MUTED);
				cy += 11;
			}
		} else if (!d.containers.isEmpty()) {
			cy = header(ui, Tr.t("bloom.bm.recent"), x, cy);
			List<StoredContainer> recent = d.containerList();
			recent.sort(Comparator.comparingLong((StoredContainer c) -> c.seen).reversed());
			for (StoredContainer c : recent.subList(0, Math.min(6, recent.size()))) {
				Runnable a = containerRow(ui, c, x, cy, cw, g);
				if (a != null) action = a;
				cy += containerHeight(c);
			}
		}
		ui.endScroll("bm.home", x, y, w, h, cy + off - y);
		if (action != null) action.run();
	}

	// ---------------------------------------------------------------- containers

	private int containerHeight(StoredContainer c) {
		if (!c.key.equals(expanded)) return 38;
		int rows = (Math.max(c.size, 1) + c.rowWidth() - 1) / c.rowWidth();
		return 38 + rows * 18 + 10;
	}

	private void containers(Ui ui, MemoryData d, int x, int y, int w, int h) {
		GameInfo g = mod.ctx().game();
		ui.textField("bm.cf", x, y, 150, 16, containerFilter, Tr.t("bloom.bm.containers.filter"), Icon.FILTER);
		containerSort = ui.segmented("bm.csort", x + 156, y, 150, 16, new String[] {Tr.t("bloom.bm.sort.distance"), Tr.t("bloom.bm.sort.recent")}, containerSort);
		if (ui.button("bm.forget", x + w - 110, y, 110, 16, Tr.t("bloom.bm.forget_all"), Icon.TRASH, Ui.Kind.DANGER, !d.containers.isEmpty() || !d.villagers.isEmpty())) {
			Feedback.confirm(Tr.t("bloom.bm.forget_all.confirm"), mod::forgetAll);
		}
		String f = containerFilter.value().trim().toLowerCase(Locale.ROOT);
		List<StoredContainer> list = new ArrayList<>();
		for (StoredContainer c : d.containers.values()) {
			if (!f.isEmpty() && !mod.placeName(c).toLowerCase(Locale.ROOT).contains(f)) continue;
			list.add(c);
		}
		if (containerSort == 1) list.sort(Comparator.comparingLong((StoredContainer c) -> c.seen).reversed());
		else list.sort(Comparator.comparingDouble(c -> c.ender() ? -1 : mod.distanceTo(c.dim, c.x, c.y, c.z)));
		int ly = y + 24, lh = h - 24;
		if (list.isEmpty()) {
			ui.textCentered(Tr.t(d.containers.isEmpty() ? "bloom.bm.containers.empty" : "bloom.bm.no_match"), x + w / 2, ly + 30, BloomTheme.TEXT_MUTED);
			return;
		}
		int off = ui.beginScroll("bm.containers", x, ly, w, lh);
		int cy = ly - off;
		Runnable action = null;
		for (StoredContainer c : list) {
			int ch = containerHeight(c);
			if (cy + ch >= ly && cy <= ly + lh) {
				Runnable a = containerRow(ui, c, x, cy, w - 8, g);
				if (a != null) action = a;
			}
			cy += ch;
		}
		ui.endScroll("bm.containers", x, ly, w, lh, cy + off - ly);
		if (action != null) action.run();
	}

	private @Nullable Runnable containerRow(Ui ui, StoredContainer c, int x, int y, int w, GameInfo g) {
		String id = "bm.c." + c.key;
		boolean open = c.key.equals(expanded);
		int h = containerHeight(c) - 4;
		boolean hov = ui.hovered(x, y, w, 34);
		float hv = ui.anim(id, hov, 10);
		ui.round(x, y, w, h, 4, open ? BloomTheme.SURFACE_2 : BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_2, hv));
		item(ui, c.icon(), x + 8, y + 9, false);
		int textW = w - 220;
		ui.text(ui.ellipsize(mod.placeName(c), textW), x + 30, y + 7, BloomTheme.TEXT);
		String sub = Tr.t("bloom.bm.containers.summary", c.itemCount(), c.slots.size(), c.size) + " · " + Tr.t("bloom.bm.seen", BaseMemoryMod.ago(c.seen));
		ui.text(ui.ellipsize(sub, textW), x + 30, y + 20, BloomTheme.TEXT_MUTED);
		int rx = x + w - 184;
		if (c.ender()) {
			ui.text(ui.ellipsize(Tr.t("bloom.bm.ender_anywhere"), 120), rx, y + 13, BloomTheme.TEXT_DIM);
		} else {
			ui.text(c.x + " " + c.y + " " + c.z, rx, y + 7, BloomTheme.TEXT_DIM);
			ui.text(ui.ellipsize(distance(c.dim, c.x, c.y, c.z, g), 120), rx, y + 20, BloomTheme.ACCENT);
		}
		Runnable action = null;
		int bx = x + w - 22;
		if (ui.iconButton(id + ".del", bx, y + 9, 16, Icon.TRASH, Tr.t("bloom.bm.forget"))) {
			action = () -> Feedback.confirm(Tr.t("bloom.bm.forget.confirm", mod.placeName(c)), () -> {
				MemoryData d = mod.data;
				if (d != null) d.removeContainer(c.key);
			});
		}
		bx -= 18;
		if (!c.ender() && guideButton(ui, id + ".go", bx, y + 9, guiding(c))) action = guiding(c) ? mod::stopGuide : () -> mod.guideTo(c, null);
		if (action == null && ui.clicked(x, y, w - 44, 34)) expanded = open ? null : c.key;
		if (open) contentsGrid(ui, c, x + 30, y + 38);
		return action;
	}

	private void contentsGrid(Ui ui, StoredContainer c, int x, int y) {
		int rw = c.rowWidth();
		for (int i = 0; i < Math.max(c.size, 1); i++) {
			int sx = x + (i % rw) * 18, sy = y + (i / rw) * 18;
			ui.round(sx, sy, 17, 17, 2, BloomTheme.SURFACE_ACTIVE);
		}
		for (SlotStack s : c.slots) {
			int sx = x + (s.slot() % rw) * 18, sy = y + (s.slot() / rw) * 18;
			item(ui, s.stack(), sx, sy, true);
			if (ui.hovered(sx, sy, 17, 17)) {
				ui.tooltip(s.stack().getCount() + " × " + Stacks.label(s.stack()));
			}
		}
	}

	// ---------------------------------------------------------------- villagers

	private int villagerHeight(StoredVillager v) {
		return v.uuid.equals(expanded) ? 38 + v.offers.size() * 32 + 6 : 38;
	}

	private void villagers(Ui ui, MemoryData d, int x, int y, int w, int h) {
		GameInfo g = mod.ctx().game();
		if (d.villagers.isEmpty()) {
			ui.textCentered(Tr.t("bloom.bm.villagers.empty"), x + w / 2, y + 30, BloomTheme.TEXT_MUTED);
			return;
		}
		List<StoredVillager> list = new ArrayList<>(d.villagers.values());
		list.sort(Comparator.comparingDouble(v -> mod.distanceTo(v.dim, v.x, v.y, v.z)));
		int off = ui.beginScroll("bm.villagers", x, y, w, h);
		int cy = y - off;
		Runnable action = null;
		for (StoredVillager v : list) {
			int vh = villagerHeight(v);
			if (cy + vh >= y && cy <= y + h) {
				Runnable a = villagerRow(ui, v, x, cy, w - 8, g);
				if (a != null) action = a;
			}
			cy += vh;
		}
		ui.endScroll("bm.villagers", x, y, w, h, cy + off - y);
		if (action != null) action.run();
	}

	private @Nullable Runnable villagerRow(Ui ui, StoredVillager v, int x, int y, int w, GameInfo g) {
		String id = "bm.v." + v.uuid;
		boolean open = v.uuid.equals(expanded);
		boolean hov = ui.hovered(x, y, w, 34);
		float hv = ui.anim(id, hov, 10);
		ui.round(x, y, w, villagerHeight(v) - 4, 4, open ? BloomTheme.SURFACE_2 : BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_2, hv));
		item(ui, new ItemStack(Items.EMERALD), x + 8, y + 9, false);
		int textW = w - 220;
		ui.text(ui.ellipsize(v.displayName() + " · " + v.levelName(), textW), x + 30, y + 7, BloomTheme.TEXT);
		String best = "";
		for (Offer o : v.offers) {
			String e = Stacks.enchantments(o.result());
			if (!e.isEmpty()) {
				best = e;
				break;
			}
		}
		String sub = Tr.t("bloom.bm.villagers.summary", v.offers.size()) + (best.isEmpty() ? "" : " · " + best) + " · "
				+ Tr.t("bloom.bm.seen", BaseMemoryMod.ago(v.seen));
		ui.text(ui.ellipsize(sub, textW), x + 30, y + 20, BloomTheme.TEXT_MUTED);
		int rx = x + w - 184;
		ui.text(v.x + " " + v.y + " " + v.z, rx, y + 7, BloomTheme.TEXT_DIM);
		ui.text(ui.ellipsize(distance(v.dim, v.x, v.y, v.z, g), 120), rx, y + 20, BloomTheme.ACCENT);
		Runnable action = null;
		int bx = x + w - 22;
		if (ui.iconButton(id + ".del", bx, y + 9, 16, Icon.TRASH, Tr.t("bloom.bm.forget"))) {
			action = () -> Feedback.confirm(Tr.t("bloom.bm.forget.confirm", v.displayName()), () -> {
				MemoryData d = mod.data;
				if (d != null) d.removeVillager(v.uuid);
			});
		}
		bx -= 18;
		if (guideButton(ui, id + ".go", bx, y + 9, guiding(v))) action = guiding(v) ? mod::stopGuide : () -> mod.guideTo(v, null);
		if (action == null && ui.clicked(x, y, w - 44, 34)) expanded = open ? null : v.uuid;
		if (open) {
			int oy = y + 38;
			for (Offer o : v.offers) {
				tradeRow(ui, v, o, x + 8, oy, w - 16, g, false);
				oy += 32;
			}
		}
		return action;
	}
}
