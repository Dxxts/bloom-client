package com.bloomclient.client.mods.worldtracker;

import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.ModPage;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.BiomeVisit;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.DimensionStats;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.HistoryPoint;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.Path;
import com.bloomclient.client.mods.worldtracker.TrackerRecords.Suggestion;
import com.bloomclient.client.hud.widgets.CoreWidgets;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Draw;
import com.bloomclient.client.ui.Feedback;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.TextInput;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.client.ui.Ui;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/** The World Tracker page: points, exploration, nearby suggestions and a map of explored chunks. */
final class WorldTrackerPage implements ModPage {
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private final WorldTrackerMod mod;
	private int tab;
	private final TextInput search = new TextInput().maxLength(48);
	private int categoryFilter; // 0 = all, else ordinal + 1
	private int sort; // 0 distance, 1 name, 2 newest
	private boolean otherDimensions = true;

	// Editor
	private @Nullable Poi editing;
	private boolean editingNew;
	private @Nullable Suggestion editingFrom;
	private final TextInput name = new TextInput().maxLength(64);
	private final TextInput description = new TextInput().maxLength(256);
	private final TextInput ex = numeric(), ey = numeric(), ez = numeric();
	private int editCategory;
	private int editDimension;
	private List<String> dimensionChoices = List.of();

	// Map
	private int zoom = 4;
	private double panX, panZ;
	private boolean follow = true;
	private boolean trail = true;

	WorldTrackerPage(WorldTrackerMod mod) {
		this.mod = mod;
	}

	private static TextInput numeric() {
		return new TextInput().maxLength(9).filter(s -> s.matches("-?\\d*"));
	}

	@Override
	public String id() {
		return "world-tracker";
	}

	@Override
	public String title() {
		return "bloom.wt.name";
	}

	@Override
	public Icon icon() {
		return Icon.PIN;
	}

	@Override
	public void onClose() {
		editing = null;
	}

	@Override
	public void render(Ui ui, int x, int y, int w, int h) {
		WorldState s = mod.state;
		if (s == null) {
			ui.textCentered(Tr.t("bloom.wt.no_world"), x + w / 2, y + 40, BloomTheme.TEXT_MUTED);
			return;
		}
		if (editing != null) {
			editor(ui, s, x, y, w, h);
			return;
		}
		String[] tabs = {Tr.t("bloom.wt.tab.points", s.points.size()), Tr.t("bloom.wt.tab.exploration"), Tr.t("bloom.wt.tab.nearby", s.suggestions.size()),
				Tr.t("bloom.wt.tab.map")};
		tab = ui.segmented("wt.tab", x, y, Math.min(w, 360), 16, tabs, tab);
		int cy = y + 24, ch = h - 24;
		switch (tab) {
			case 0 -> points(ui, s, x, cy, w, ch);
			case 1 -> exploration(ui, s, x, cy, w, ch);
			case 2 -> nearby(ui, s, x, cy, w, ch);
			default -> map(ui, s, x, cy, w, ch);
		}
	}

	// ---------------------------------------------------------------- points

	private List<Poi> filtered(WorldState s, GameInfo g) {
		String q = search.value().trim().toLowerCase(Locale.ROOT);
		String here = g.dimension() == null ? "" : g.dimension().toString();
		List<Poi> list = new ArrayList<>();
		for (Poi p : s.points) {
			if (categoryFilter > 0 && p.category.ordinal() != categoryFilter - 1) continue;
			if (!otherDimensions && !p.dimension.equals(here)) continue;
			if (!q.isEmpty() && !(p.name.toLowerCase(Locale.ROOT).contains(q) || p.description.toLowerCase(Locale.ROOT).contains(q)
					|| (p.x + " " + p.y + " " + p.z).contains(q))) continue;
			list.add(p);
		}
		Comparator<Poi> cmp = switch (sort) {
			case 1 -> Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT));
			case 2 -> Comparator.comparingLong((Poi p) -> p.created).reversed();
			default -> Comparator.comparingDouble(p -> distance(p, g));
		};
		list.sort(cmp);
		return list;
	}

	private double distance(Poi p, GameInfo g) {
		double[] d = mod.delta(p, g);
		return d == null ? Double.MAX_VALUE : Math.sqrt(d[0] * d[0] + d[2] * d[2]);
	}

	private void points(Ui ui, WorldState s, int x, int y, int w, int h) {
		GameInfo g = mod.ctx().game();
		ui.textField("wt.search", x, y, 130, 16, search, Tr.t("bloom.wt.search"), Icon.SEARCH);
		String[] cats = new String[PoiCategory.values().length + 1];
		cats[0] = Tr.t("bloom.wt.all_categories");
		for (PoiCategory c : PoiCategory.values()) cats[c.ordinal() + 1] = Tr.t(c.translationKey());
		categoryFilter = ui.dropdown("wt.cat", x + 136, y, 96, 16, cats, categoryFilter);
		String[] sorts = {Tr.t("bloom.wt.sort.distance"), Tr.t("bloom.wt.sort.name"), Tr.t("bloom.wt.sort.newest")};
		int sortW = Math.min(150, w - 346);
		if (sortW > 90) sort = ui.segmented("wt.sort", x + 238, y, sortW, 16, sorts, sort);
		if (ui.iconButton("wt.otherdims", x + w - 106, y, 16, Icon.GLOBE, Tr.t("bloom.wt.other_dimensions"), otherDimensions, true)) otherDimensions = !otherDimensions;
		if (ui.button("wt.add", x + w - 84, y, 84, 16, Tr.t("bloom.wt.add_here"), Icon.PLUS, Ui.Kind.PRIMARY, g.inWorld())) {
			Poi p = new Poi();
			p.name = Tr.t("bloom.wt.default_name", s.points.size() + 1);
			p.x = g.blockX();
			p.y = g.blockY();
			p.z = g.blockZ();
			p.dimension = g.dimension() == null ? WorldTrackerMod.OVERWORLD : g.dimension().toString();
			edit(p, true, null);
			return;
		}
		List<Poi> list = filtered(s, g);
		int ly = y + 24, lh = h - 24;
		if (list.isEmpty()) {
			ui.textCentered(Tr.t(s.points.isEmpty() ? "bloom.wt.empty" : "bloom.wt.no_match"), x + w / 2, ly + 30, BloomTheme.TEXT_MUTED);
			return;
		}
		int off = ui.beginScroll("wt.points", x, ly, w, lh);
		Poi selected = s.selected();
		Runnable action = null;
		int ry = ly - off;
		for (Poi p : list) {
			Runnable a = pointRow(ui, s, p, p == selected, x, ry, w - 8, g);
			if (a != null) action = a;
			ry += 37;
		}
		ui.endScroll("wt.points", x, ly, w, lh, list.size() * 37);
		if (action != null) action.run();
	}

	private @Nullable Runnable pointRow(Ui ui, WorldState s, Poi p, boolean selected, int x, int y, int w, GameInfo g) {
		String id = "wt.p." + p.id;
		boolean hov = ui.hovered(x, y, w, 34);
		float hv = ui.anim(id, hov, 10);
		ui.round(x, y, w, 34, 4, selected ? BloomTheme.ACCENT_SOFT : BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_2, hv));
		ui.round(x, y + 4, 3, 26, 1, p.category.color);
		ui.icon(p.category.icon, x + 10, y + 6, p.category.color);
		int textW = w - 230;
		ui.text(ui.ellipsize(p.name, textW), x + 24, y + 6, BloomTheme.TEXT);
		String sub = p.description.isEmpty() ? Tr.t(p.category.translationKey()) + " · " + DATE.format(Instant.ofEpochMilli(p.created)) : p.description;
		ui.text(ui.ellipsize(sub, textW + 4), x + 10, y + 20, BloomTheme.TEXT_MUTED);
		int rx = x + w - 214;
		ui.text(p.x + " " + p.y + " " + p.z, rx, y + 6, BloomTheme.TEXT_DIM);
		double[] d = mod.delta(p, g);
		String dist = d == null ? WorldTrackerMod.dimensionName(p.dimension)
				: WorldTrackerMod.arrow(d[0], d[2], g.yaw()) + " " + WorldTrackerMod.formatDistance(Math.sqrt(d[0] * d[0] + d[2] * d[2]));
		boolean converted = d != null && g.dimension() != null && !p.dimension.equals(g.dimension().toString());
		ui.text(ui.ellipsize(dist + (converted ? " · " + WorldTrackerMod.dimensionName(p.dimension) : ""), 110), rx, y + 20,
				d == null ? BloomTheme.TEXT_MUTED : BloomTheme.ACCENT);
		int bx = x + w - 20;
		Runnable action = null;
		if (ui.iconButton(id + ".del", bx, y + 9, 16, Icon.TRASH, Tr.t("bloom.common.delete"))) {
			action = () -> Feedback.confirm(Tr.t("bloom.wt.delete_confirm", p.name), () -> s.remove(p));
		}
		bx -= 18;
		if (ui.iconButton(id + ".copy", bx, y + 9, 16, Icon.COPY, Tr.t("bloom.wt.copy_coords"))) {
			ui.mc.keyboardHandler.setClipboard(p.x + " " + p.y + " " + p.z);
			Feedback.toast(Tr.t("bloom.common.copied"));
		}
		bx -= 18;
		if (ui.iconButton(id + ".edit", bx, y + 9, 16, Icon.EDIT, Tr.t("bloom.common.edit"))) action = () -> edit(p, false, null);
		bx -= 18;
		if (mod.canTeleport()) {
			if (ui.iconButton(id + ".tp", bx, y + 9, 16, Icon.ARROW, Tr.t("bloom.wt.teleport"))) {
				if (mod.teleport(p)) Feedback.toast(Tr.t("bloom.wt.teleported", p.name));
			}
			bx -= 18;
		}
		if (ui.iconButton(id + ".target", bx, y + 9, 16, Icon.TARGET, Tr.t(selected ? "bloom.wt.untarget" : "bloom.wt.target"), selected, true)) {
			s.select(selected ? null : p);
		}
		return action;
	}

	// ---------------------------------------------------------------- editor

	private void edit(Poi p, boolean isNew, @Nullable Suggestion from) {
		editing = p;
		editingNew = isNew;
		editingFrom = from;
		name.set(p.name);
		description.set(p.description);
		ex.set(Integer.toString(p.x));
		ey.set(Integer.toString(p.y));
		ez.set(Integer.toString(p.z));
		editCategory = p.category.ordinal();
		Set<String> dims = new LinkedHashSet<>(List.of(WorldTrackerMod.OVERWORLD, WorldTrackerMod.NETHER, "minecraft:the_end"));
		WorldState s = mod.state;
		if (s != null) dims.addAll(s.dimensions.keySet());
		dims.add(p.dimension);
		dimensionChoices = new ArrayList<>(dims);
		editDimension = dimensionChoices.indexOf(p.dimension);
	}

	private void editor(Ui ui, WorldState s, int x, int y, int w, int h) {
		Poi p = editing;
		if (p == null) return;
		int fw = Math.min(w, 380);
		ui.text(Tr.t(editingNew ? "bloom.wt.new_point" : "bloom.wt.edit_point"), x, y + 2, BloomTheme.TEXT);
		int cy = y + 18;
		ui.text(Tr.t("bloom.wt.field.name"), x, cy + 4, BloomTheme.TEXT_MUTED);
		ui.textField("wt.e.name", x + 80, cy, fw - 80, 16, name, Tr.t("bloom.wt.field.name"));
		cy += 22;
		ui.text(Tr.t("bloom.wt.field.description"), x, cy + 4, BloomTheme.TEXT_MUTED);
		ui.textField("wt.e.desc", x + 80, cy, fw - 80, 16, description, Tr.t("bloom.wt.field.description_hint"));
		cy += 22;
		ui.text(Tr.t("bloom.wt.field.category"), x, cy + 4, BloomTheme.TEXT_MUTED);
		String[] cats = new String[PoiCategory.values().length];
		for (PoiCategory c : PoiCategory.values()) cats[c.ordinal()] = Tr.t(c.translationKey());
		int catY = cy;
		cy += 22;
		ui.text(Tr.t("bloom.wt.field.coords"), x, cy + 4, BloomTheme.TEXT_MUTED);
		int cw3 = (fw - 80 - 12) / 3;
		ui.textField("wt.e.x", x + 80, cy, cw3, 16, ex, "X");
		ui.textField("wt.e.y", x + 86 + cw3, cy, cw3, 16, ey, "Y");
		ui.textField("wt.e.z", x + 92 + cw3 * 2, cy, cw3, 16, ez, "Z");
		cy += 22;
		ui.text(Tr.t("bloom.wt.field.dimension"), x, cy + 4, BloomTheme.TEXT_MUTED);
		String[] dims = new String[dimensionChoices.size()];
		for (int i = 0; i < dims.length; i++) dims[i] = WorldTrackerMod.dimensionName(dimensionChoices.get(i));
		int dimY = cy;
		cy += 28;
		GameInfo g = mod.ctx().game();
		if (ui.button("wt.e.here", x + 80, cy, 120, 16, Tr.t("bloom.wt.use_position"), Icon.PIN, Ui.Kind.SECONDARY, g.inWorld())) {
			ex.set(Integer.toString(g.blockX()));
			ey.set(Integer.toString(g.blockY()));
			ez.set(Integer.toString(g.blockZ()));
			if (g.dimension() != null) {
				int i = dimensionChoices.indexOf(g.dimension().toString());
				if (i >= 0) editDimension = i;
			}
		}
		ui.text(DATE.format(Instant.ofEpochMilli(p.created)), x + 80, cy + 22, BloomTheme.TEXT_MUTED);
		if (ui.button("wt.e.cancel", x + fw - 150, cy, 70, 16, Tr.t("bloom.common.cancel"), Ui.Kind.SECONDARY)) {
			editing = null;
		}
		if (ui.button("wt.e.save", x + fw - 74, cy, 74, 16, Tr.t("bloom.common.save"), Ui.Kind.PRIMARY)) {
			try {
				p.name = name.value().isBlank() ? Tr.t("bloom.wt.default_name", s.points.size() + 1) : name.value().trim();
				p.description = description.value().trim();
				p.category = PoiCategory.values()[editCategory];
				p.x = Integer.parseInt(ex.value());
				p.y = Integer.parseInt(ey.value());
				p.z = Integer.parseInt(ez.value());
				p.dimension = dimensionChoices.get(Math.max(0, editDimension));
				if (editingNew) s.add(p);
				else s.markPointsDirty();
				if (editingFrom != null) s.dismiss(editingFrom);
				editing = null;
				Feedback.toast(Tr.t("bloom.wt.saved", p.name));
			} catch (NumberFormatException e) {
				Feedback.toast(Tr.t("bloom.wt.invalid_coords"), BloomTheme.DANGER);
			}
		}
		// Dropdowns last so their popups are drawn above the other fields.
		editDimension = ui.dropdown("wt.e.dim", x + 80, dimY, fw - 80, 16, dims, Math.max(0, editDimension));
		editCategory = ui.dropdown("wt.e.cat", x + 80, catY, fw - 80, 16, cats, editCategory);
	}

	// ---------------------------------------------------------------- exploration

	private static String duration(long seconds) {
		long h = seconds / 3600, m = (seconds % 3600) / 60;
		return h > 0 ? Tr.t("bloom.time.hm", h, m) : m > 0 ? Tr.t("bloom.time.m", m) : Tr.t("bloom.time.s", seconds);
	}

	static String ago(long millis) {
		long s = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
		if (s < 60) return Tr.t("bloom.time.just_now");
		if (s < 3600) return Tr.t("bloom.time.minutes_ago", s / 60);
		if (s < 86400) return Tr.t("bloom.time.hours_ago", s / 3600);
		return Tr.t("bloom.time.days_ago", s / 86400);
	}

	private void exploration(Ui ui, WorldState s, int x, int y, int w, int h) {
		int off = ui.beginScroll("wt.exp", x, y, w, h);
		int cy = y - off;
		int cw = w - 8;
		Runnable action = null;
		// Dimensions
		List<DimensionStats> dims = new ArrayList<>(s.dimensions.values());
		int cols = Math.max(1, Math.min(3, dims.size())), gap = 8;
		int cardW = (cw - gap * (cols - 1)) / cols;
		for (int i = 0; i < dims.size(); i++) {
			DimensionStats d = dims.get(i);
			int cx = x + (i % cols) * (cardW + gap), ccy = cy + (i / cols) * 70;
			ui.round(cx, ccy, cardW, 62, 5, BloomTheme.SURFACE);
			ui.icon(Icon.GLOBE, cx + 8, ccy + 8, BloomTheme.ACCENT);
			ui.text(ui.ellipsize(WorldTrackerMod.dimensionName(d.dim), cardW - 30), cx + 22, ccy + 9, BloomTheme.TEXT);
			ui.text(Tr.t("bloom.wt.exp.chunks", s.exploredChunks(d.dim)), cx + 8, ccy + 23, BloomTheme.TEXT_DIM);
			ui.text(Tr.t("bloom.wt.exp.time", duration(d.seconds)) + " · " + WorldTrackerMod.formatDistance(d.distance), cx + 8, ccy + 34, BloomTheme.TEXT_DIM);
			ui.text(ui.ellipsize(Tr.t("bloom.wt.exp.last", d.lastX, d.lastY, d.lastZ), cardW - 14), cx + 8, ccy + 45, BloomTheme.TEXT_MUTED);
		}
		cy += ((dims.size() + cols - 1) / cols) * 70 + 4;

		// Biomes
		int totalBiomes = 0;
		try {
			if (ui.mc.level != null) totalBiomes = ui.mc.level.registryAccess().lookupOrThrow(Registries.BIOME).keySet().size();
		} catch (RuntimeException ignored) {
			// Registry not available.
		}
		cy = header(ui, Tr.t("bloom.wt.exp.biomes", s.biomes.size(), totalBiomes), x, cy);
		List<BiomeVisit> biomes = new ArrayList<>(s.biomes.values());
		biomes.sort(Comparator.comparingLong((BiomeVisit b) -> b.first).reversed());
		int bcols = cw >= 360 ? 3 : 2;
		int bw = cw / bcols;
		for (int i = 0; i < biomes.size(); i++) {
			BiomeVisit b = biomes.get(i);
			int bx = x + (i % bcols) * bw, by = cy + (i / bcols) * 12;
			Identifier id = Identifier.tryParse(b.biome);
			String bname = id == null ? b.biome : CoreWidgets.biomeName(id);
			ui.text(ui.ellipsize(bname, bw - 8), bx, by, BloomTheme.TEXT_DIM);
			if (ui.hovered(bx, by - 1, bw - 4, 11)) ui.tooltip(Tr.t("bloom.wt.exp.biome_tip", DATE.format(Instant.ofEpochMilli(b.first)), b.x, b.y, b.z));
		}
		if (biomes.isEmpty()) {
			ui.text(Tr.t("bloom.wt.exp.none"), x, cy, BloomTheme.TEXT_MUTED);
			cy += 12;
		}
		cy += ((biomes.size() + bcols - 1) / bcols) * 12 + 8;

		// Trips
		long gap2 = Math.max(60_000, mod.historyInterval.get() * 4000L);
		List<Path> paths = s.paths(gap2, 8);
		cy = header(ui, Tr.t("bloom.wt.exp.trips"), x, cy);
		if (paths.isEmpty()) {
			ui.text(Tr.t("bloom.wt.exp.none"), x, cy, BloomTheme.TEXT_MUTED);
			cy += 12;
		}
		for (Path p : paths) {
			ui.round(x, cy, cw, 22, 4, BloomTheme.SURFACE);
			ui.text(ago(p.end()), x + 8, cy + 7, BloomTheme.TEXT_MUTED);
			String line = WorldTrackerMod.dimensionName(p.dim()) + " · " + duration((p.end() - p.start()) / 1000) + " · " + WorldTrackerMod.formatDistance(p.distance());
			ui.text(ui.ellipsize(line, cw - 230), x + 86, cy + 7, BloomTheme.TEXT);
			ui.textRight(Tr.t("bloom.wt.exp.from_to", p.fromX(), p.fromZ(), p.toX(), p.toZ()), x + cw - 8, cy + 7, BloomTheme.TEXT_DIM);
			cy += 25;
		}
		cy += 6;

		// Last positions
		cy = header(ui, Tr.t("bloom.wt.exp.recent"), x, cy);
		int shown = 0;
		Iterator<HistoryPoint> it = s.history.descendingIterator();
		while (it.hasNext() && shown < 8) {
			HistoryPoint p = it.next();
			ui.round(x, cy, cw, 20, 4, BloomTheme.SURFACE);
			ui.text(ago(p.t), x + 8, cy + 6, BloomTheme.TEXT_MUTED);
			ui.text(p.x + " " + p.y + " " + p.z + " · " + WorldTrackerMod.dimensionName(p.dim), x + 86, cy + 6, BloomTheme.TEXT);
			if (ui.button("wt.h.save." + shown, x + cw - 70, cy + 2, 64, 16, Tr.t("bloom.common.save"), Icon.PLUS, Ui.Kind.GHOST, true)) {
				Poi poi = new Poi();
				poi.name = Tr.t("bloom.wt.default_name", s.points.size() + 1);
				poi.x = p.x;
				poi.y = p.y;
				poi.z = p.z;
				poi.dimension = p.dim;
				action = () -> edit(poi, true, null);
			}
			cy += 23;
			shown++;
		}
		if (shown == 0) {
			ui.text(Tr.t("bloom.wt.exp.none"), x, cy, BloomTheme.TEXT_MUTED);
			cy += 12;
		}
		ui.endScroll("wt.exp", x, y, w, h, cy + off - y + 4);
		if (action != null) action.run();
	}

	private static int header(Ui ui, String text, int x, int y) {
		ui.text(text.toUpperCase(Locale.ROOT), x, y + 2, BloomTheme.TEXT_MUTED);
		return y + 15;
	}

	// ---------------------------------------------------------------- nearby

	private void nearby(Ui ui, WorldState s, int x, int y, int w, int h) {
		GameInfo g = mod.ctx().game();
		int off = ui.beginScroll("wt.near", x, y, w, h);
		int cy = y - off, cw = w - 8;
		Runnable action = null;
		cy = header(ui, Tr.t("bloom.wt.near.suggestions"), x, cy);
		ui.text(ui.ellipsize(Tr.t("bloom.wt.near.suggestions_hint"), cw), x, cy, BloomTheme.TEXT_MUTED);
		cy += 14;
		if (s.suggestions.isEmpty()) {
			ui.text(Tr.t("bloom.wt.near.no_suggestions"), x, cy, BloomTheme.TEXT_DIM);
			cy += 14;
		}
		for (Suggestion sg : new ArrayList<>(s.suggestions)) {
			ui.round(x, cy, cw, 32, 4, BloomTheme.SURFACE);
			ui.round(x, cy + 4, 3, 24, 1, sg.category().color);
			ui.icon(sg.category().icon, x + 10, cy + 6, sg.category().color);
			ui.text(ui.ellipsize(sg.name(), cw - 220), x + 24, cy + 6, BloomTheme.TEXT);
			ui.text(ui.ellipsize(sg.reason(), cw - 160), x + 10, cy + 19, BloomTheme.TEXT_MUTED);
			Poi probe = new Poi();
			probe.x = sg.x();
			probe.y = sg.y();
			probe.z = sg.z();
			probe.dimension = sg.dimension();
			double[] d = mod.delta(probe, g);
			String where = sg.x() + " " + sg.y() + " " + sg.z() + (d == null ? " · " + WorldTrackerMod.dimensionName(sg.dimension())
					: " · " + WorldTrackerMod.formatDistance(Math.sqrt(d[0] * d[0] + d[2] * d[2])));
			ui.textRight(ui.ellipsize(where, 150), x + cw - 120, cy + 6, BloomTheme.TEXT_DIM);
			if (ui.button("wt.sg.save." + sg.key(), x + cw - 112, cy + 8, 56, 16, Tr.t("bloom.common.save"), Ui.Kind.PRIMARY)) {
				Poi poi = new Poi();
				poi.name = sg.name();
				poi.category = sg.category();
				poi.x = sg.x();
				poi.y = sg.y();
				poi.z = sg.z();
				poi.dimension = sg.dimension();
				action = () -> edit(poi, true, sg);
			}
			if (ui.button("wt.sg.dismiss." + sg.key(), x + cw - 52, cy + 8, 46, 16, Tr.t("bloom.wt.dismiss"), Ui.Kind.GHOST)) {
				action = () -> s.dismiss(sg);
			}
			cy += 35;
		}
		cy += 6;
		cy = header(ui, Tr.t("bloom.wt.near.saved"), x, cy);
		List<Poi> near = new ArrayList<>();
		for (Poi p : s.points) if (distance(p, g) <= 512) near.add(p);
		near.sort(Comparator.comparingDouble(p -> distance(p, g)));
		if (near.isEmpty()) {
			ui.text(Tr.t("bloom.wt.near.none"), x, cy, BloomTheme.TEXT_DIM);
			cy += 14;
		}
		Poi selected = s.selected();
		for (Poi p : near) {
			Runnable a = pointRow(ui, s, p, p == selected, x, cy, cw, g);
			if (a != null) action = a;
			cy += 37;
		}
		ui.endScroll("wt.near", x, y, w, h, cy + off - y + 4);
		if (action != null) action.run();
	}

	// ---------------------------------------------------------------- map

	private void map(Ui ui, WorldState s, int x, int y, int w, int h) {
		GameInfo g = mod.ctx().game();
		if (g.dimension() == null) return;
		String dim = g.dimension().toString();
		// Toolbar
		if (ui.iconButton("wt.m.out", x, y, 16, Icon.MINUS, Tr.t("bloom.wt.map.zoom_out"), false, zoom > 1)) zoom = Math.max(1, zoom / 2);
		if (ui.iconButton("wt.m.in", x + 18, y, 16, Icon.PLUS, Tr.t("bloom.wt.map.zoom_in"), false, zoom < 16)) zoom = Math.min(16, zoom * 2);
		if (ui.iconButton("wt.m.center", x + 36, y, 16, Icon.TARGET, Tr.t("bloom.wt.map.center"), follow, true)) {
			follow = true;
		}
		if (ui.iconButton("wt.m.trail", x + 54, y, 16, Icon.COMPASS, Tr.t("bloom.wt.map.trail"), trail, true)) trail = !trail;
		ui.text(Tr.t("bloom.wt.map.info", WorldTrackerMod.dimensionName(dim), s.exploredChunks(dim)), x + 78, y + 4, BloomTheme.TEXT_MUTED);
		int my = y + 22, mh = h - 22;
		ui.round(x, my, w, mh, 5, 0xFF0A0C0B);
		ui.outline(x, my, w, mh, 5, BloomTheme.BORDER);
		// Pan with drag, zoom with wheel
		String dragId = "wt.m.drag";
		if (ui.clicked(x, my, w, mh)) {
			ui.setActive(dragId);
			ui.state("wt.m.last", () -> new int[2])[0] = ui.mx;
			ui.state("wt.m.last", () -> new int[2])[1] = ui.my;
		}
		int[] last = ui.state("wt.m.last", () -> new int[2]);
		if (ui.isActive(dragId) && ui.isMouseDown()) {
			int dx = ui.mx - last[0], dy = ui.my - last[1];
			if (dx != 0 || dy != 0) {
				follow = false;
				panX -= dx * 16.0 / zoom;
				panZ -= dy * 16.0 / zoom;
			}
			last[0] = ui.mx;
			last[1] = ui.my;
		}
		double wheel = ui.takeScroll(x, my, w, mh);
		if (wheel > 0 && zoom < 16) zoom *= 2;
		if (wheel < 0 && zoom > 1) zoom /= 2;
		if (follow) {
			panX = g.x();
			panZ = g.z();
		}
		// Block coordinates at the map center; one chunk = zoom canvas units.
		double blocksPerUnit = 16.0 / zoom;
		int cx0 = x + w / 2, cz0 = my + mh / 2;
		ui.pushClip(x + 1, my + 1, w - 2, mh - 2);
		int minChunkX = (int) Math.floor((panX - (w / 2.0) * blocksPerUnit) / 16) - 1;
		int maxChunkX = (int) Math.floor((panX + (w / 2.0) * blocksPerUnit) / 16) + 1;
		int minChunkZ = (int) Math.floor((panZ - (mh / 2.0) * blocksPerUnit) / 16) - 1;
		int maxChunkZ = (int) Math.floor((panZ + (mh / 2.0) * blocksPerUnit) / 16) + 1;
		int fill = ui.c(0xFF1E4A3A);
		for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
			int rowY = cz0 + (int) Math.floor((cz * 16 - panZ) / blocksPerUnit);
			int runStart = Integer.MIN_VALUE;
			for (int cx = minChunkX; cx <= maxChunkX + 1; cx++) {
				boolean e = cx <= maxChunkX && s.isExplored(dim, cx, cz);
				if (e && runStart == Integer.MIN_VALUE) runStart = cx;
				if (!e && runStart != Integer.MIN_VALUE) {
					int x0 = cx0 + (int) Math.floor((runStart * 16 - panX) / blocksPerUnit);
					int x1 = cx0 + (int) Math.floor((cx * 16 - panX) / blocksPerUnit);
					ui.g.fill(x0, rowY, Math.max(x0 + 1, x1), rowY + Math.max(1, zoom), fill);
					runStart = Integer.MIN_VALUE;
				}
			}
		}
		// Trail
		if (trail) {
			int n = 0;
			Iterator<HistoryPoint> it = s.history.descendingIterator();
			while (it.hasNext() && n < 600) {
				HistoryPoint p = it.next();
				n++;
				if (!p.dim.equals(dim)) continue;
				int px = cx0 + (int) Math.floor((p.x - panX) / blocksPerUnit), pz = cz0 + (int) Math.floor((p.z - panZ) / blocksPerUnit);
				ui.g.fill(px, pz, px + 1, pz + 1, ui.c(0x905CE1A8));
			}
		}
		// Points
		String hover = null;
		for (Poi p : s.points) {
			if (!p.dimension.equals(dim)) continue;
			int px = cx0 + (int) Math.floor((p.x - panX) / blocksPerUnit), pz = cz0 + (int) Math.floor((p.z - panZ) / blocksPerUnit);
			Draw.circle(ui.g, px, pz, 4, ui.c(0xC0000000));
			Draw.circle(ui.g, px, pz, 3, ui.c(p.category.color));
			if (Math.abs(ui.mx - px) <= 4 && Math.abs(ui.my - pz) <= 4) hover = p.name + " · " + p.x + " " + p.y + " " + p.z;
		}
		// Player
		int ppx = cx0 + (int) Math.floor((g.x() - panX) / blocksPerUnit), ppz = cz0 + (int) Math.floor((g.z() - panZ) / blocksPerUnit);
		Draw.circle(ui.g, ppx, ppz, 4, ui.c(0xFF000000));
		Draw.circle(ui.g, ppx, ppz, 3, ui.c(0xFFFFFFFF));
		double yaw = Math.toRadians(g.yaw());
		int hx = ppx + (int) Math.round(-Math.sin(yaw) * 8), hz = ppz + (int) Math.round(Math.cos(yaw) * 8);
		Draw.circle(ui.g, hx, hz, 2, ui.c(BloomTheme.ACCENT));
		ui.popClip();
		if (hover != null) ui.tooltip(hover);
		ui.text(Tr.t("bloom.wt.map.legend"), x + 8, my + mh - 12, BloomTheme.TEXT_MUTED);
	}
}
