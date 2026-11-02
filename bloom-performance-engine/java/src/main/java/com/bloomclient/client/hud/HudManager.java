package com.bloomclient.client.hud;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.api.GameInfo;
import com.bloomclient.client.api.hud.HudAnchor;
import com.bloomclient.client.api.hud.HudElement;
import com.bloomclient.client.api.hud.HudWidget;
import com.bloomclient.client.core.BloomStorage;
import com.bloomclient.client.core.ModContainer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jspecify.annotations.Nullable;

/**
 * HUD system: widget registry, the persisted layout, data updates and
 * in-game rendering.
 *
 * <p>Layout file {@code config/bloom/hud/layout.json}:
 * <pre>{"schema": 1, "elements": [{"id", "widget", "visible", "anchor", "x", "y", "style": {...}, "options": {...}}]}</pre>
 * Older schemas are upgraded by {@link #MIGRATIONS} (index {@code n}
 * upgrades schema {@code n} to {@code n + 1}); exports use the same format,
 * so an exported file is also a valid layout.
 */
public final class HudManager {
	public static final int SCHEMA = 1;
	/**
	 * Schema migrations. Schema 0 was the unversioned pre-release format
	 * ({@code "widgets"} instead of {@code "elements"}).
	 */
	private static final List<UnaryOperator<JsonObject>> MIGRATIONS = List.of(root -> {
		if (!root.has("elements") && root.has("widgets")) root.add("elements", root.get("widgets"));
		root.remove("widgets");
		return root;
	});

	public record Registered(HudWidget widget, @Nullable ModContainer owner) {
		public boolean enabled() {
			return owner == null || owner.isEnabled();
		}

		public String ownerName() {
			return owner == null ? "Bloom" : owner.metadata().displayName();
		}
	}

	public record ImportResult(boolean ok, int elements, String message) {
	}

	private final Map<String, Registered> widgets = new LinkedHashMap<>();
	private final List<HudElement> elements = new ArrayList<>();
	private final Path file = BloomStorage.configDir().resolve("hud").resolve("layout.json");
	private final Path exportsDir = BloomStorage.configDir().resolve("hud").resolve("exports");
	private boolean dirty;
	private boolean editing;

	// Diagnostics
	private long frameNanos;
	private double avgFrameMicros;
	private int drawnLastFrame;
	private long updatesTotal;

	// ---------------------------------------------------------------- registry

	public void registerCore(HudWidget widget) {
		register(widget, null);
	}

	public void registerWidget(ModContainer owner, HudWidget widget) {
		register(widget, owner);
	}

	private void register(HudWidget widget, @Nullable ModContainer owner) {
		if (widgets.containsKey(widget.id())) {
			throw new IllegalArgumentException("Duplicate HUD widget id " + widget.id());
		}
		widgets.put(widget.id(), new Registered(widget, owner));
	}

	public @Nullable Registered widget(String id) {
		return widgets.get(id);
	}

	public List<Registered> widgets() {
		return List.copyOf(widgets.values());
	}

	public void onModStateChanged() {
		for (HudElement e : elements) e.nextUpdate = 0;
	}

	// ---------------------------------------------------------------- layout

	public List<HudElement> elements() {
		return Collections.unmodifiableList(elements);
	}

	/** Mutable list for the HUD Studio. Call {@link #markDirty()} after changes. */
	public List<HudElement> editableElements() {
		return elements;
	}

	public void markDirty() {
		dirty = true;
	}

	public void setEditing(boolean editing) {
		this.editing = editing;
	}

	public boolean isEditing() {
		return editing;
	}

	public HudElement create(String widgetId) {
		HudElement e = new HudElement();
		e.widget = widgetId;
		Registered r = widgets.get(widgetId);
		if (r != null) {
			try {
				r.widget().applyDefaults(e);
			} catch (RuntimeException ex) {
				BloomClient.LOGGER.warn("Bloom HUD: defaults of {} failed", widgetId, ex);
			}
		}
		e.initTransient();
		return e;
	}

	public void load() {
		JsonObject root = BloomStorage.readObject(file);
		List<HudElement> loaded = root == null ? null : parse(root);
		elements.clear();
		if (loaded == null) {
			elements.addAll(defaultLayout());
			dirty = true;
		} else {
			elements.addAll(loaded);
		}
	}

	public void resetToDefault() {
		elements.clear();
		elements.addAll(defaultLayout());
		dirty = true;
	}

	private List<HudElement> defaultLayout() {
		List<HudElement> list = new ArrayList<>();
		addDefault(list, "bloom:fps", HudAnchor.TOP_LEFT, 4, 4);
		addDefault(list, "bloom:coordinates", HudAnchor.TOP_LEFT, 4, 24);
		addDefault(list, "bloom:direction", HudAnchor.TOP_LEFT, 4, 44);
		addDefault(list, "bloom:ping", HudAnchor.TOP_RIGHT, -4, 4);
		addDefault(list, "bloom:armor", HudAnchor.MIDDLE_RIGHT, -4, 0);
		// Shown only while Base Memory is guiding the player somewhere.
		addDefault(list, "bloom-base-memory:guide", HudAnchor.TOP_CENTER, 0, 30);
		return list;
	}

	private void addDefault(List<HudElement> list, String widget, HudAnchor anchor, int x, int y) {
		if (!widgets.containsKey(widget)) return;
		HudElement e = create(widget);
		e.anchor = anchor;
		e.x = x;
		e.y = y;
		list.add(e);
	}

	/** Parses a layout or an export, migrating older schemas. Returns null if unusable. */
	private @Nullable List<HudElement> parse(JsonObject root) {
		int schema = root.has("schema") ? root.get("schema").getAsInt() : 0;
		if (schema > SCHEMA) {
			BloomClient.LOGGER.warn("Bloom HUD: layout schema {} is newer than this Bloom ({}); reading the known fields", schema, SCHEMA);
		}
		for (int v = Math.max(0, schema); v < SCHEMA && v < MIGRATIONS.size(); v++) {
			root = MIGRATIONS.get(v).apply(root);
		}
		JsonElement arr = root.get("elements");
		if (arr == null || !arr.isJsonArray()) return null;
		List<HudElement> out = new ArrayList<>();
		for (JsonElement je : arr.getAsJsonArray()) {
			try {
				HudElement e = BloomStorage.GSON.fromJson(je, HudElement.class);
				if (e == null || e.widget == null || e.widget.isBlank()) continue;
				e.initTransient();
				out.add(e);
			} catch (RuntimeException ex) {
				BloomClient.LOGGER.warn("Bloom HUD: skipping an invalid element", ex);
			}
		}
		return out;
	}

	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("schema", SCHEMA);
		JsonArray arr = new JsonArray();
		for (HudElement e : elements) arr.add(BloomStorage.GSON.toJsonTree(e));
		root.add("elements", arr);
		return root;
	}

	public void saveIfDirty() {
		if (dirty) {
			dirty = false;
			BloomStorage.writeAsync(file, BloomStorage.GSON.toJson(toJson()));
		}
	}

	public Path exportsDir() {
		return exportsDir;
	}

	/** Writes the layout to {@code config/bloom/hud/exports/} and returns the file. */
	public Path exportToFile() throws IOException {
		String name = "hud-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".json";
		Path out = exportsDir.resolve(name);
		BloomStorage.writeNow(out, BloomStorage.GSON.toJson(toJson()));
		return out;
	}

	public String exportString() {
		return BloomStorage.GSON.toJson(toJson());
	}

	public List<Path> listExports() {
		if (!Files.isDirectory(exportsDir)) return List.of();
		try (Stream<Path> s = Files.list(exportsDir)) {
			return s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted(Collections.reverseOrder()).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	public ImportResult importFrom(String json) {
		JsonObject root;
		try {
			JsonElement e = JsonParser.parseString(json);
			if (!e.isJsonObject()) return new ImportResult(false, 0, "bloom.hud.import.invalid");
			root = e.getAsJsonObject();
		} catch (RuntimeException e) {
			return new ImportResult(false, 0, "bloom.hud.import.invalid");
		}
		List<HudElement> parsed = parse(root);
		if (parsed == null) return new ImportResult(false, 0, "bloom.hud.import.invalid");
		elements.clear();
		elements.addAll(parsed);
		dirty = true;
		return new ImportResult(true, parsed.size(), "bloom.hud.import.done");
	}

	public ImportResult importFrom(Path path) {
		try {
			return importFrom(Files.readString(path));
		} catch (IOException e) {
			return new ImportResult(false, 0, "bloom.hud.import.unreadable");
		}
	}

	// ---------------------------------------------------------------- update and render

	/** Refreshes elements whose interval elapsed. Called once per frame before drawing. */
	public void update(GameInfo game, boolean forStudio) {
		long now = System.currentTimeMillis();
		for (int i = 0, n = elements.size(); i < n; i++) {
			HudElement e = elements.get(i);
			if (!e.visible && !forStudio) continue;
			if (now < e.nextUpdate) {
				HudRenderer.layout(e);
				continue;
			}
			Registered r = widgets.get(e.widget);
			if (r == null || !r.enabled()) {
				e.available = false;
				e.nextUpdate = now + 1000;
				continue;
			}
			HudWidget w = r.widget();
			e.nextUpdate = now + Math.max(16, w.updateIntervalMs());
			e.lines.begin();
			try {
				e.available = game.inWorld() && w.isAvailable(game);
				if (e.available) {
					w.update(e, e.lines, game);
				} else if (forStudio) {
					e.lines.add(w.unavailableText());
				}
				e.errors = 0;
			} catch (Throwable t) {
				e.available = false;
				e.errors++;
				e.nextUpdate = now + 2000L * Math.min(10, e.errors);
				if (r.owner() != null) r.owner().reportError("HUD widget " + w.id(), t);
				else BloomClient.LOGGER.error("Bloom HUD: widget {} failed", w.id(), t);
			}
			e.lines.end();
			updatesTotal++;
			HudRenderer.layout(e);
		}
	}

	/** In-game HUD pass, called from the vanilla HUD after the hotbar and before chat. */
	public void render(GuiGraphicsExtractor g) {
		if (editing || !BloomClient.settings().hudEnabled.on()) return;
		Minecraft mc = Minecraft.getInstance();
		if (BloomClient.settings().hideHudWithDebug.on() && mc.getDebugOverlay().showDebugScreen()) return;
		long t0 = System.nanoTime();
		update(BloomClient.game(), false);
		int sw = g.guiWidth(), sh = g.guiHeight();
		int drawn = 0;
		for (int i = 0, n = elements.size(); i < n; i++) {
			HudElement e = elements.get(i);
			if (!e.visible || !e.available || e.width <= 0) continue;
			Registered r = widgets.get(e.widget);
			if (r == null || !r.enabled()) continue;
			try {
				HudRenderer.draw(g, e, HudRenderer.x(e, sw), HudRenderer.y(e, sh));
				drawn++;
			} catch (Throwable t) {
				e.available = false;
				if (r.owner() != null) r.owner().reportError("HUD render " + e.widget, t);
				else BloomClient.LOGGER.error("Bloom HUD: drawing {} failed", e.widget, t);
			}
		}
		frameNanos = System.nanoTime() - t0;
		avgFrameMicros = avgFrameMicros * 0.95 + (frameNanos / 1000.0) * 0.05;
		drawnLastFrame = drawn;
	}

	public double averageFrameMicros() {
		return avgFrameMicros;
	}

	public int drawnLastFrame() {
		return drawnLastFrame;
	}

	public long updatesTotal() {
		return updatesTotal;
	}
}
