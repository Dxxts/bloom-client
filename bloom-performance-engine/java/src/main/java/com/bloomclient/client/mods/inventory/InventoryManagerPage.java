package com.bloomclient.client.mods.inventory;

import com.bloomclient.client.api.ModPage;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Feedback;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.TextInput;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.client.ui.Ui;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/** The Inventory Manager page: Overview, Analysis, Organization, Insights and Goals. */
final class InventoryManagerPage implements ModPage {
	private static final String[] RULE_KINDS = {"none", "pickaxe", "axe", "shovel", "hoe", "sword", "ranged", "food", "blocks", "item"};

	private final InventoryManagerMod mod;
	private int tab;
	// Analysis
	private final TextInput filter = new TextInput().maxLength(48);
	private int sort; // 0 count, 1 name, 2 slots, 3 category
	private int category; // 0 all
	// Organization
	private int ruleSlot = -1;
	private final TextInput ruleItem = new TextInput().maxLength(48);
	// Insights
	private final TextInput watchSearch = new TextInput().maxLength(48);
	// Goals
	private final TextInput goalName = new TextInput().maxLength(48);
	private final TextInput reqSearch = new TextInput().maxLength(48);
	private final TextInput reqQty = new TextInput("1").maxLength(5).filter(s -> s.matches("\\d*"));
	private final TextInput recipeSearch = new TextInput().maxLength(48);
	private final TextInput recipeQty = new TextInput("1").maxLength(5).filter(s -> s.matches("\\d*"));
	private @Nullable String openGoal;

	InventoryManagerPage(InventoryManagerMod mod) {
		this.mod = mod;
	}

	@Override
	public String id() {
		return "inventory-manager";
	}

	@Override
	public String title() {
		return "bloom.im.name";
	}

	@Override
	public Icon icon() {
		return Icon.BAG;
	}

	@Override
	public void onOpen() {
		mod.markDirty();
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		if (ui.mc.player == null) {
			ui.textCentered(Tr.t("bloom.im.no_world"), x + w / 2, y + 40, BloomTheme.TEXT_MUTED);
			return;
		}
		String[] tabs = {Tr.t("bloom.im.tab.overview"), Tr.t("bloom.im.tab.analysis"), Tr.t("bloom.im.tab.organization"), Tr.t("bloom.im.tab.insights"),
				Tr.t("bloom.im.tab.goals")};
		tab = ui.segmented("im.tab", x, y, Math.min(w, 420), 16, tabs, tab);
		int cy = y + 24, ch = h - 24;
		InventoryAnalysis a = mod.analysis;
		switch (tab) {
			case 0 -> overview(ui, a, x, cy, w, ch);
			case 1 -> analysis(ui, a, x, cy, w, ch);
			case 2 -> organization(ui, a, x, cy, w, ch);
			case 3 -> insights(ui, a, x, cy, w, ch);
			default -> goals(ui, a, x, cy, w, ch);
		}
	}

	private static int header(Ui ui, String text, int x, int y) {
		ui.text(text.toUpperCase(Locale.ROOT), x, y + 2, BloomTheme.TEXT_MUTED);
		return y + 15;
	}

	private static void item(Ui ui, ItemStack stack, int x, int y) {
		if (!stack.isEmpty()) ui.g.item(stack, x, y);
	}

	private static void bar(Ui ui, int x, int y, int w, double fraction, int color) {
		ui.round(x, y, w, 4, 2, BloomTheme.SURFACE_ACTIVE);
		ui.round(x, y, (int) Math.round(w * Math.max(0, Math.min(1, fraction))), 4, 2, color);
	}

	// ---------------------------------------------------------------- overview

	private void overview(Ui ui, InventoryAnalysis a, int x, int y, int w, int h) {
		int off = ui.beginScroll("im.ov", x, y, w, h);
		int cy = y - off, cw = w - 8;
		String[][] stats = {
				{Tr.t("bloom.im.ov.items"), Integer.toString(a.totalItems), Tr.t("bloom.im.ov.kinds", a.items.size())},
				{Tr.t("bloom.im.ov.slots"), a.usedSlots + "", Tr.t("bloom.im.ov.free", a.freeSlots, a.hotbarFree)},
				{Tr.t("bloom.im.ov.armor"), fmt(a.armorPoints), Tr.t("bloom.im.ov.armor_hint")},
				{Tr.t("bloom.im.ov.food"), Integer.toString(a.foodItems), Tr.t("bloom.im.ov.food_hint", a.foodNutrition)},
				{Tr.t("bloom.im.ov.space"), Integer.toString(a.partialCapacity), Tr.t("bloom.im.ov.space_hint", a.mergeableSlots)},
				{Tr.t("bloom.im.ov.ammo"), Integer.toString(a.arrows), Tr.t("bloom.im.ov.blocks", a.blocks)}};
		int cols = cw >= 420 ? 3 : 2, gap = 8, cardW = (cw - gap * (cols - 1)) / cols;
		for (int i = 0; i < stats.length; i++) {
			int cx = x + (i % cols) * (cardW + gap), ccy = cy + (i / cols) * 46;
			ui.round(cx, ccy, cardW, 40, 5, BloomTheme.SURFACE);
			ui.text(stats[i][0], cx + 9, ccy + 7, BloomTheme.TEXT_MUTED);
			ui.textScaled(stats[i][1], cx + 9, ccy + 18, 1.5f, BloomTheme.TEXT);
			ui.textRight(ui.ellipsize(stats[i][2], cardW - 60), cx + cardW - 9, ccy + 22, BloomTheme.TEXT_DIM);
		}
		cy += ((stats.length + cols - 1) / cols) * 46 + 4;
		cy = header(ui, Tr.t("bloom.im.ov.categories"), x, cy);
		int max = 1;
		for (int v : a.categoryCounts.values()) max = Math.max(max, v);
		for (ItemCategory c : ItemCategory.values()) {
			int v = a.categoryCounts.getOrDefault(c, 0);
			ui.icon(c.icon, x, cy, c.color);
			ui.text(Tr.t(c.translationKey()), x + 14, cy + 1, BloomTheme.TEXT_DIM);
			bar(ui, x + 110, cy + 3, cw - 160, v / (double) max, c.color);
			ui.textRight(Integer.toString(v), x + cw, cy + 1, BloomTheme.TEXT);
			cy += 13;
		}
		cy += 6;
		InventoryData.Goal goal = mod.data.active();
		if (goal != null) {
			cy = header(ui, Tr.t("bloom.im.ov.goal"), x, cy);
			double p = goal.progress(a);
			ui.round(x, cy, cw, 26, 4, BloomTheme.SURFACE);
			ui.icon(Icon.TARGET, x + 8, cy + 6, BloomTheme.ACCENT);
			ui.text(ui.ellipsize(goal.name, cw - 80), x + 22, cy + 6, BloomTheme.TEXT);
			ui.textRight(Math.round(p * 100) + "%", x + cw - 8, cy + 6, BloomTheme.ACCENT);
			bar(ui, x + 8, cy + 18, cw - 16, p, BloomTheme.ACCENT);
			cy += 32;
		}
		cy = header(ui, Tr.t("bloom.im.ov.suggestions", mod.suggestions.size()), x, cy);
		int n = 0;
		for (Organizer.Suggestion s : mod.suggestions) {
			if (n++ >= 4) break;
			cy = suggestionRow(ui, s, x, cy, cw);
		}
		if (mod.suggestions.isEmpty()) {
			ui.text(Tr.t("bloom.im.org.none"), x, cy, BloomTheme.TEXT_DIM);
			cy += 12;
		}
		ui.endScroll("im.ov", x, y, w, h, cy + off - y + 4);
	}

	private static String fmt(double v) {
		return v == Math.floor(v) ? Integer.toString((int) v) : String.format(Locale.ROOT, "%.1f", v);
	}

	private int suggestionRow(Ui ui, Organizer.Suggestion s, int x, int y, int w) {
		int color = switch (s.type()) {
			case MOVE, EQUIP -> BloomTheme.ACCENT;
			case MERGE -> BloomTheme.INFO;
			case WARN -> BloomTheme.DANGER;
			case INFO -> BloomTheme.TEXT_MUTED;
		};
		List<String> lines = ui.wrap(s.text(), w - 40);
		int rh = Math.max(22, 8 + lines.size() * 10);
		ui.round(x, y, w, rh, 4, BloomTheme.SURFACE);
		ui.round(x, y + 3, 3, rh - 6, 1, color);
		if (!s.icon().isEmpty()) item(ui, s.icon(), x + 8, y + (rh - 16) / 2);
		else ui.icon(s.type() == Organizer.Type.WARN ? Icon.WARNING : Icon.INFO, x + 12, y + (rh - 9) / 2, color);
		for (int i = 0; i < lines.size(); i++) ui.text(lines.get(i), x + 30, y + 5 + i * 10, BloomTheme.TEXT);
		return y + rh + 3;
	}

	// ---------------------------------------------------------------- analysis

	private void analysis(Ui ui, InventoryAnalysis a, int x, int y, int w, int h) {
		ui.textField("im.filter", x, y, 130, 16, filter, Tr.t("bloom.im.search"), Icon.SEARCH);
		String[] cats = new String[ItemCategory.values().length + 1];
		cats[0] = Tr.t("bloom.im.all_categories");
		for (ItemCategory c : ItemCategory.values()) cats[c.ordinal() + 1] = Tr.t(c.translationKey());
		String[] sorts = {Tr.t("bloom.im.sort.count"), Tr.t("bloom.im.sort.name"), Tr.t("bloom.im.sort.slots"), Tr.t("bloom.im.sort.category")};
		int sortW = Math.min(200, w - 250);
		if (sortW > 120) sort = ui.segmented("im.sort", x + w - sortW, y, sortW, 16, sorts, sort);
		String q = filter.value().trim().toLowerCase(Locale.ROOT);
		List<InventoryAnalysis.ItemEntry> list = new ArrayList<>();
		for (InventoryAnalysis.ItemEntry e : a.items.values()) {
			if (category > 0 && e.category.ordinal() != category - 1) continue;
			if (!q.isEmpty() && !e.sample.getHoverName().getString().toLowerCase(Locale.ROOT).contains(q) && !e.id().contains(q)) continue;
			list.add(e);
		}
		Comparator<InventoryAnalysis.ItemEntry> cmp = switch (sort) {
			case 1 -> Comparator.comparing(e -> e.sample.getHoverName().getString());
			case 2 -> Comparator.comparingInt((InventoryAnalysis.ItemEntry e) -> e.stacks).reversed();
			case 3 -> Comparator.comparingInt((InventoryAnalysis.ItemEntry e) -> e.category.ordinal()).thenComparing(e -> -e.count);
			default -> Comparator.comparingInt((InventoryAnalysis.ItemEntry e) -> e.count).reversed();
		};
		list.sort(cmp);
		int ly = y + 22, lh = h - 22;
		int off = ui.beginScroll("im.an", x, ly, w, lh);
		int cy = ly - off, cw = w - 8;
		if (!a.lowDurability.isEmpty()) {
			cy = header(ui, Tr.t("bloom.im.an.low", mod.lowDurability.get()), x, cy);
			for (InventoryAnalysis.LowDurability low : a.lowDurability) {
				ui.round(x, cy, cw, 20, 4, BloomTheme.DANGER_SOFT);
				item(ui, low.stack(), x + 4, cy + 2);
				ui.text(ui.ellipsize(low.stack().getHoverName().getString(), cw - 200), x + 24, cy + 6, BloomTheme.TEXT);
				ui.text(Organizer.slotName(low.slot()), x + cw - 170, cy + 6, BloomTheme.TEXT_MUTED);
				ui.textRight(low.remaining() + "/" + low.max(), x + cw - 8, cy + 6, BloomTheme.DANGER);
				cy += 23;
			}
			cy += 4;
		}
		cy = header(ui, Tr.t("bloom.im.an.items", list.size()), x, cy);
		for (InventoryAnalysis.ItemEntry e : list) {
			boolean hov = ui.hovered(x, cy, cw, 20);
			ui.round(x, cy, cw, 20, 4, hov ? BloomTheme.SURFACE_2 : BloomTheme.SURFACE);
			item(ui, e.sample, x + 4, cy + 2);
			ui.text(ui.ellipsize(e.sample.getHoverName().getString(), cw - 250), x + 24, cy + 6, BloomTheme.TEXT);
			int tx = x + cw - 220;
			ui.round(tx, cy + 4, 70, 12, 6, BloomTheme.withAlpha(e.category.color, 0.18f));
			ui.text(ui.ellipsize(Tr.t(e.category.translationKey()), 62), tx + 5, cy + 6, e.category.color);
			if (e.stacks > 1) {
				ui.text(Tr.t(e.mergeableSlots > 0 ? "bloom.im.an.mergeable" : "bloom.im.an.duplicate", e.stacks), tx + 76, cy + 6,
						e.mergeableSlots > 0 ? BloomTheme.WARNING : BloomTheme.TEXT_MUTED);
			}
			ui.textRight("×" + e.count, x + cw - 8, cy + 6, BloomTheme.TEXT);
			if (hov) ui.tooltip(e.id() + " · " + Tr.t("bloom.im.an.slots", e.stacks));
			cy += 22;
		}
		if (list.isEmpty()) {
			ui.text(Tr.t("bloom.im.an.empty"), x, cy, BloomTheme.TEXT_DIM);
			cy += 12;
		}
		ui.endScroll("im.an", x, ly, w, lh, cy + off - ly + 4);
		category = ui.dropdown("im.cat", x + 136, y, 100, 16, cats, category);
	}

	// ---------------------------------------------------------------- organization

	private static String ruleLabel(InventoryData.HotbarRule r) {
		if (!r.active()) return Tr.t("bloom.im.kind.none");
		if ("item".equals(r.kind)) return ItemSearch.name(r.item);
		return Tr.t("bloom.im.kind." + r.kind);
	}

	private void organization(Ui ui, InventoryAnalysis a, int x, int y, int w, int h) {
		int off = ui.beginScroll("im.org", x, y, w, h);
		int cy = y - off, cw = w - 8;
		Runnable after = null;
		cy = header(ui, Tr.t("bloom.im.org.rules"), x, cy);
		ui.text(ui.ellipsize(Tr.t("bloom.im.org.rules_hint"), cw), x, cy, BloomTheme.TEXT_MUTED);
		cy += 14;
		int cell = Math.min(40, (cw - 8 * 4) / 9);
		for (int i = 0; i < 9; i++) {
			int sx = x + i * (cell + 4);
			InventoryData.HotbarRule r = mod.data.hotbar[i];
			boolean sel = ruleSlot == i;
			boolean ok = r.matches(a.main[i]);
			boolean hov = ui.hovered(sx, cy, cell, cell + 12);
			ui.round(sx, cy, cell, cell, 4, sel ? BloomTheme.ACCENT_SOFT : hov ? BloomTheme.SURFACE_2 : BloomTheme.SURFACE);
			if (r.active()) ui.outline(sx, cy, cell, cell, 4, ok ? BloomTheme.ACCENT_DIM : BloomTheme.WARNING);
			item(ui, a.main[i], sx + (cell - 16) / 2, cy + (cell - 16) / 2);
			ui.text(Integer.toString(i + 1), sx + 3, cy + 2, BloomTheme.TEXT_MUTED);
			ui.text(ui.ellipsize(ruleLabel(r), cell + 2), sx, cy + cell + 3, r.active() ? (ok ? BloomTheme.ACCENT : BloomTheme.WARNING) : BloomTheme.TEXT_MUTED);
			if (ui.clicked(sx, cy, cell, cell + 12)) {
				int fi = i;
				after = () -> {
					ruleSlot = ruleSlot == fi ? -1 : fi;
					ruleItem.set("");
				};
			}
		}
		cy += cell + 18;
		int dropY = -1;
		String[] kindNames = new String[RULE_KINDS.length];
		int kindSel = 0;
		if (ruleSlot >= 0) {
			InventoryData.HotbarRule r = mod.data.hotbar[ruleSlot];
			for (int i = 0; i < RULE_KINDS.length; i++) {
				kindNames[i] = Tr.t("bloom.im.kind." + RULE_KINDS[i]);
				if (RULE_KINDS[i].equals(r.kind)) kindSel = i;
			}
			ui.round(x, cy, cw, "item".equals(r.kind) ? 104 : 30, 5, BloomTheme.SURFACE);
			ui.text(Tr.t("bloom.im.org.slot_rule", ruleSlot + 1), x + 9, cy + 10, BloomTheme.TEXT);
			dropY = cy + 7;
			if ("item".equals(r.kind)) {
				ui.textField("im.rule.item", x + 9, cy + 30, 200, 16, ruleItem, Tr.t("bloom.im.org.search_item"), Icon.SEARCH);
				int ry = cy + 50;
				for (ItemSearch.Entry e : ItemSearch.items(ruleItem.value(), 4)) {
					boolean hov = ui.hovered(x + 9, ry, 200, 12);
					if (hov) ui.round(x + 9, ry, 200, 12, 2, BloomTheme.SURFACE_HOVER);
					ui.text(ui.ellipsize(e.name(), 190), x + 13, ry + 2, e.id().equals(r.item) ? BloomTheme.ACCENT : BloomTheme.TEXT);
					if (ui.clicked(x + 9, ry, 200, 12)) {
						r.item = e.id();
						mod.data.markRules();
						mod.markDirty();
					}
					ry += 13;
				}
				if (!r.item.isEmpty()) ui.text(Tr.t("bloom.im.org.current_item", ItemSearch.name(r.item)), x + 220, cy + 34, BloomTheme.TEXT_DIM);
				cy += 110;
			} else {
				cy += 36;
			}
		}
		cy = header(ui, Tr.t("bloom.im.org.suggestions", mod.suggestions.size()), x, cy + 4);
		ui.text(ui.ellipsize(Tr.t("bloom.im.org.never_moves"), cw), x, cy, BloomTheme.TEXT_MUTED);
		cy += 14;
		for (Organizer.Suggestion s : mod.suggestions) cy = suggestionRow(ui, s, x, cy, cw);
		if (mod.suggestions.isEmpty()) {
			ui.text(Tr.t("bloom.im.org.none"), x, cy, BloomTheme.TEXT_DIM);
			cy += 12;
		}
		if (ruleSlot >= 0 && dropY >= 0) {
			InventoryData.HotbarRule r = mod.data.hotbar[ruleSlot];
			int ns = ui.dropdown("im.rule.kind", x + 130, dropY, 150, 16, kindNames, kindSel);
			if (ns != kindSel) {
				r.kind = RULE_KINDS[ns];
				if (!"item".equals(r.kind)) r.item = "";
				mod.data.markRules();
				mod.markDirty();
			}
		}
		ui.endScroll("im.org", x, y, w, h, cy + off - y + 4);
		if (after != null) after.run();
	}

	// ---------------------------------------------------------------- insights

	private int itemRow(Ui ui, String id, ItemStack icon, String itemId, String right, int rightColor, int x, int y, int w) {
		ui.round(x, y, w, 20, 4, BloomTheme.SURFACE);
		item(ui, icon, x + 4, y + 2);
		ui.text(ui.ellipsize(icon.getHoverName().getString(), w - 210), x + 24, y + 6, BloomTheme.TEXT);
		ui.textRight(right, x + w - 108, y + 6, rightColor);
		InventoryData.Watch watch = mod.data.watchFor(itemId);
		boolean important = watch != null && watch.important;
		if (ui.iconButton(id + ".star", x + w - 100, y + 2, 16, Icon.STAR, Tr.t("bloom.im.in.important"), important, true)) {
			mod.data.watchOrCreate(itemId).important = !important;
			cleanupWatch();
			mod.data.markRules();
		}
		int min = watch == null ? 0 : watch.min;
		if (ui.iconButton(id + ".minus", x + w - 80, y + 2, 16, Icon.MINUS, Tr.t("bloom.im.in.min_down"), false, min > 0)) {
			mod.data.watchOrCreate(itemId).min = Math.max(0, min - 8);
			cleanupWatch();
			mod.data.markRules();
		}
		ui.textCentered(min == 0 ? "—" : "<" + min, x + w - 50, y + 6, min == 0 ? BloomTheme.TEXT_MUTED : BloomTheme.WARNING);
		if (ui.hovered(x + w - 64, y, 28, 20)) ui.tooltip(Tr.t("bloom.im.in.min_tip"));
		if (ui.iconButton(id + ".plus", x + w - 34, y + 2, 16, Icon.PLUS, Tr.t("bloom.im.in.min_up"))) {
			mod.data.watchOrCreate(itemId).min = Math.min(2304, min + 8);
			mod.data.markRules();
		}
		return y + 22;
	}

	private void cleanupWatch() {
		mod.data.watch.removeIf(w -> !w.important && w.min <= 0);
	}

	private void insights(Ui ui, InventoryAnalysis a, int x, int y, int w, int h) {
		int off = ui.beginScroll("im.in", x, y, w, h);
		int cy = y - off, cw = w - 8;
		// Running out
		cy = header(ui, Tr.t("bloom.im.in.running_out"), x, cy);
		int n = 0;
		for (InventoryData.Watch wt : new ArrayList<>(mod.data.watch)) {
			if (wt.min <= 0) continue;
			Item item = InventoryData.item(wt.item);
			if (item == null) continue;
			int have = a.count(item);
			if (have >= wt.min) continue;
			cy = itemRow(ui, "im.in.ro." + wt.item, new ItemStack(item), wt.item, have + " / " + wt.min, BloomTheme.DANGER, x, cy, cw);
			n++;
		}
		for (InventoryAnalysis.LowDurability low : a.lowDurability) {
			ui.round(x, cy, cw, 20, 4, BloomTheme.SURFACE);
			item(ui, low.stack(), x + 4, cy + 2);
			ui.text(ui.ellipsize(Tr.t("bloom.im.in.wearing_out", low.stack().getHoverName().getString()), cw - 120), x + 24, cy + 6, BloomTheme.TEXT);
			ui.textRight(Math.round(low.fraction() * 100) + "%", x + cw - 8, cy + 6, BloomTheme.DANGER);
			cy += 22;
			n++;
		}
		if (n == 0) {
			ui.text(Tr.t("bloom.im.in.nothing_low"), x, cy, BloomTheme.TEXT_DIM);
			cy += 14;
		}
		cy += 4;
		// Important
		cy = header(ui, Tr.t("bloom.im.in.important_items"), x, cy);
		n = 0;
		for (InventoryData.Watch wt : new ArrayList<>(mod.data.watch)) {
			if (!wt.important) continue;
			Item item = InventoryData.item(wt.item);
			if (item == null) continue;
			int have = a.count(item);
			cy = itemRow(ui, "im.in.imp." + wt.item, new ItemStack(item), wt.item, "×" + have, have == 0 ? BloomTheme.DANGER : BloomTheme.TEXT, x, cy, cw);
			n++;
		}
		if (n == 0) {
			ui.text(Tr.t("bloom.im.in.no_important"), x, cy, BloomTheme.TEXT_DIM);
			cy += 14;
		}
		ui.textField("im.in.add", x, cy, 180, 16, watchSearch, Tr.t("bloom.im.in.add_watch"), Icon.PLUS);
		cy += 20;
		for (ItemSearch.Entry e : ItemSearch.items(watchSearch.value(), 4)) {
			boolean hov = ui.hovered(x, cy, 180, 12);
			if (hov) ui.round(x, cy, 180, 12, 2, BloomTheme.SURFACE_HOVER);
			ui.text(ui.ellipsize(e.name(), 170), x + 4, cy + 2, BloomTheme.TEXT);
			if (ui.clicked(x, cy, 180, 12)) {
				InventoryData.Watch wt = mod.data.watchOrCreate(e.id());
				wt.important = true;
				mod.data.markRules();
				watchSearch.set("");
			}
			cy += 13;
		}
		cy += 6;
		// Space usage
		cy = header(ui, Tr.t("bloom.im.in.space"), x, cy);
		n = 0;
		for (InventoryAnalysis.ItemEntry e : a.bySlots()) {
			if (n++ >= 6 || e.stacks < 1) break;
			cy = itemRow(ui, "im.in.sp." + e.id(), e.sample, e.id(), Tr.t("bloom.im.an.slots", e.stacks), BloomTheme.TEXT_DIM, x, cy, cw);
		}
		cy += 4;
		// Materials
		cy = header(ui, Tr.t("bloom.im.in.materials"), x, cy);
		List<InventoryAnalysis.ItemEntry> mats = new ArrayList<>();
		for (InventoryAnalysis.ItemEntry e : a.items.values()) {
			if (e.category == ItemCategory.MATERIALS || e.category == ItemCategory.BLOCKS) mats.add(e);
		}
		mats.sort(Comparator.comparingInt((InventoryAnalysis.ItemEntry e) -> e.count).reversed());
		for (InventoryAnalysis.ItemEntry e : mats) {
			cy = itemRow(ui, "im.in.mat." + e.id(), e.sample, e.id(), "×" + e.count, BloomTheme.TEXT, x, cy, cw);
		}
		if (mats.isEmpty()) {
			ui.text(Tr.t("bloom.im.an.empty"), x, cy, BloomTheme.TEXT_DIM);
			cy += 12;
		}
		ui.endScroll("im.in", x, y, w, h, cy + off - y + 4);
	}

	// ---------------------------------------------------------------- goals

	private void goals(Ui ui, InventoryAnalysis a, int x, int y, int w, int h) {
		if (!mod.data.hasWorld()) {
			ui.textCentered(Tr.t("bloom.im.no_world"), x + w / 2, y + 40, BloomTheme.TEXT_MUTED);
			return;
		}
		int leftW = Math.min(200, w * 2 / 5);
		// New goal
		ui.textField("im.g.name", x, y, leftW - 56, 16, goalName, Tr.t("bloom.im.goal.new_hint"));
		if (ui.button("im.g.create", x + leftW - 52, y, 52, 16, Tr.t("bloom.im.goal.create"), Ui.Kind.PRIMARY) || goalName.consumeSubmitted()) {
			if (!goalName.value().isBlank()) {
				InventoryData.Goal g = new InventoryData.Goal();
				g.name = goalName.value().trim();
				mod.data.goals.add(g);
				if (mod.data.activeGoal == null) mod.data.activeGoal = g.id;
				openGoal = g.id;
				goalName.set("");
				mod.data.markGoals();
			}
		}
		int ly = y + 22, lh = h - 22;
		int off = ui.beginScroll("im.g.list", x, ly, leftW, lh);
		int cy = ly - off;
		InventoryData.Goal toDelete = null;
		for (InventoryData.Goal g : mod.data.goals) {
			boolean open = g.id.equals(openGoal);
			boolean activeGoal = g.id.equals(mod.data.activeGoal);
			boolean hov = ui.hovered(x, cy, leftW - 8, 34);
			ui.round(x, cy, leftW - 8, 34, 4, open ? BloomTheme.ACCENT_SOFT : hov ? BloomTheme.SURFACE_2 : BloomTheme.SURFACE);
			ui.text(ui.ellipsize(g.name, leftW - 60), x + 8, cy + 6, BloomTheme.TEXT);
			double p = g.progress(a);
			bar(ui, x + 8, cy + 22, leftW - 60, p, p >= 1 ? BloomTheme.ACCENT : BloomTheme.INFO);
			ui.text(Math.round(p * 100) + "%", x + leftW - 48, cy + 19, BloomTheme.TEXT_DIM);
			if (ui.iconButton("im.g.act." + g.id, x + leftW - 46, cy + 3, 16, Icon.TARGET, Tr.t(activeGoal ? "bloom.im.goal.active" : "bloom.im.goal.set_active"),
					activeGoal, true)) {
				mod.data.activeGoal = activeGoal ? null : g.id;
				mod.data.markGoals();
			}
			if (ui.iconButton("im.g.del." + g.id, x + leftW - 28, cy + 3, 16, Icon.TRASH, Tr.t("bloom.common.delete"))) toDelete = g;
			if (ui.clicked(x, cy, leftW - 8, 34)) openGoal = g.id;
			cy += 37;
		}
		if (mod.data.goals.isEmpty()) ui.paragraph(Tr.t("bloom.im.goal.empty"), x, cy + 4, leftW - 10, BloomTheme.TEXT_MUTED);
		ui.endScroll("im.g.list", x, ly, leftW, lh, mod.data.goals.size() * 37);
		if (toDelete != null) {
			InventoryData.Goal del = toDelete;
			Feedback.confirm(Tr.t("bloom.im.goal.delete_confirm", del.name), () -> {
				mod.data.goals.remove(del);
				if (del.id.equals(mod.data.activeGoal)) mod.data.activeGoal = null;
				mod.data.markGoals();
			});
		}
		InventoryData.Goal goal = null;
		for (InventoryData.Goal g : mod.data.goals) if (g.id.equals(openGoal)) goal = g;
		int rx = x + leftW + 8, rw = w - leftW - 8;
		if (goal == null) {
			ui.paragraph(Tr.t("bloom.im.goal.select_hint"), rx, y + 4, rw, BloomTheme.TEXT_MUTED);
			return;
		}
		goalDetail(ui, a, goal, rx, y, rw, h);
	}

	private void goalDetail(Ui ui, InventoryAnalysis a, InventoryData.Goal goal, int x, int y, int w, int h) {
		int off = ui.beginScroll("im.g.detail", x, y, w, h);
		int cy = y - off, cw = w - 8;
		ui.text(ui.ellipsize(goal.name, cw - 60), x, cy + 2, BloomTheme.TEXT);
		ui.textRight(Math.round(goal.progress(a) * 100) + "%", x + cw, cy + 2, BloomTheme.ACCENT);
		cy += 16;
		InventoryData.Requirement remove = null;
		for (InventoryData.Requirement r : goal.requirements) {
			int have = r.owned(a);
			boolean done = have >= r.count;
			ui.round(x, cy, cw, 20, 4, BloomTheme.SURFACE);
			Item first = r.items.isEmpty() ? null : InventoryData.item(r.items.getFirst());
			if (first != null) item(ui, new ItemStack(first), x + 4, cy + 2);
			ui.text(ui.ellipsize(r.label, cw - 120), x + 24, cy + 6, BloomTheme.TEXT);
			if (r.items.size() > 1 && ui.hovered(x, cy, cw - 100, 20)) {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < Math.min(12, r.items.size()); i++) sb.append(i == 0 ? "" : ", ").append(ItemSearch.name(r.items.get(i)));
				ui.tooltip(sb.toString());
			}
			ui.textRight(have + " / " + r.count, x + cw - 26, cy + 6, done ? BloomTheme.ACCENT : BloomTheme.TEXT_DIM);
			if (done) ui.icon(Icon.CHECK, x + cw - 90, cy + 6, BloomTheme.ACCENT);
			if (ui.iconButton("im.g.rm." + goal.requirements.indexOf(r), x + cw - 20, cy + 2, 16, Icon.CLOSE, Tr.t("bloom.common.delete"))) remove = r;
			cy += 22;
		}
		if (goal.requirements.isEmpty()) {
			ui.text(Tr.t("bloom.im.goal.no_requirements"), x, cy, BloomTheme.TEXT_DIM);
			cy += 14;
		}
		if (remove != null) {
			goal.requirements.remove(remove);
			mod.data.markGoals();
		}
		cy += 6;
		// Add an item
		cy = header(ui, Tr.t("bloom.im.goal.add_item"), x, cy);
		ui.textField("im.g.req", x, cy, cw - 60, 16, reqSearch, Tr.t("bloom.im.org.search_item"), Icon.SEARCH);
		ui.textField("im.g.qty", x + cw - 54, cy, 54, 16, reqQty, Tr.t("bloom.im.goal.qty"));
		cy += 20;
		for (ItemSearch.Entry e : ItemSearch.items(reqSearch.value(), 5)) {
			boolean hov = ui.hovered(x, cy, cw, 13);
			if (hov) ui.round(x, cy, cw, 13, 2, BloomTheme.SURFACE_HOVER);
			ui.text(ui.ellipsize(e.name() + "  ·  " + e.id(), cw - 8), x + 4, cy + 2, BloomTheme.TEXT);
			if (ui.clicked(x, cy, cw, 13)) {
				InventoryData.Requirement r = new InventoryData.Requirement();
				r.items = new ArrayList<>(List.of(e.id()));
				r.label = e.name();
				r.count = Math.max(1, parse(reqQty.value(), 1));
				merge(goal, r);
				reqSearch.set("");
			}
			cy += 14;
		}
		cy += 6;
		// From a known recipe
		cy = header(ui, Tr.t("bloom.im.goal.from_recipe"), x, cy);
		ui.text(ui.ellipsize(Tr.t("bloom.im.goal.recipe_hint"), cw), x, cy, BloomTheme.TEXT_MUTED);
		cy += 13;
		ui.textField("im.g.recipe", x, cy, cw - 60, 16, recipeSearch, Tr.t("bloom.im.goal.search_recipe"), Icon.SEARCH);
		ui.textField("im.g.rqty", x + cw - 54, cy, 54, 16, recipeQty, Tr.t("bloom.im.goal.qty"));
		cy += 20;
		if (!recipeSearch.value().isBlank()) {
			List<ItemSearch.RecipeOption> recipes = ItemSearch.recipes(recipeSearch.value(), 5);
			if (recipes.isEmpty()) {
				ui.text(Tr.t("bloom.im.goal.no_recipe"), x, cy, BloomTheme.TEXT_DIM);
				cy += 14;
			}
			for (ItemSearch.RecipeOption opt : recipes) {
				boolean hov = ui.hovered(x, cy, cw, 22);
				ui.round(x, cy, cw, 22, 3, hov ? BloomTheme.SURFACE_HOVER : BloomTheme.SURFACE);
				item(ui, opt.result(), x + 3, cy + 3);
				StringBuilder ing = new StringBuilder();
				for (InventoryData.Requirement r : opt.perCraft()) ing.append(ing.isEmpty() ? "" : ", ").append(r.count).append("× ").append(r.label);
				ui.text(ui.ellipsize(opt.name() + " ×" + opt.result().getCount(), cw - 30), x + 22, cy + 3, BloomTheme.TEXT);
				ui.text(ui.ellipsize(ing.toString(), cw - 30), x + 22, cy + 12, BloomTheme.TEXT_MUTED);
				if (ui.clicked(x, cy, cw, 22)) {
					int wanted = Math.max(1, parse(recipeQty.value(), 1));
					int crafts = (wanted + opt.result().getCount() - 1) / opt.result().getCount();
					for (InventoryData.Requirement r : opt.perCraft()) {
						InventoryData.Requirement copy = new InventoryData.Requirement();
						copy.items = new ArrayList<>(r.items);
						copy.label = r.label;
						copy.count = r.count * crafts;
						merge(goal, copy);
					}
					Feedback.toast(Tr.t("bloom.im.goal.recipe_added", crafts, opt.name()));
					recipeSearch.set("");
				}
				cy += 24;
			}
		}
		ui.endScroll("im.g.detail", x, y, w, h, cy + off - y + 4);
	}

	private void merge(InventoryData.Goal goal, InventoryData.Requirement r) {
		for (InventoryData.Requirement existing : goal.requirements) {
			if (existing.items.equals(r.items)) {
				existing.count += r.count;
				mod.data.markGoals();
				return;
			}
		}
		goal.requirements.add(r);
		mod.data.markGoals();
	}

	private static int parse(String s, int def) {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			return def;
		}
	}
}
