package com.bloomclient.client.api.settings;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.core.BloomStorage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Ordered, persistent collection of settings saved to one JSON file:
 *
 * <pre>{"schema": 1, "values": {"key": value, ...}}</pre>
 *
 * Unknown keys are kept on disk untouched (a newer or older version may use
 * them); values that do not parse fall back to the default. Changes are
 * written asynchronously, at most once per {@link #saveIfDirty()} call
 * (Bloom calls it once per second and on shutdown).
 */
public final class SettingsGroup {
	public static final int SCHEMA = 1;

	private final Path file;
	private final List<Setting<?>> settings = new ArrayList<>();
	private @Nullable String currentSection;
	private boolean dirty;
	private JsonObject unknown = new JsonObject();

	public SettingsGroup(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	public List<Setting<?>> all() {
		return Collections.unmodifiableList(settings);
	}

	public boolean isEmpty() {
		return settings.isEmpty();
	}

	/** Following settings belong to this section (shown as a header). */
	public SettingsGroup section(String title) {
		currentSection = title;
		return this;
	}

	private <S extends Setting<?>> S add(S setting) {
		for (Setting<?> s : settings) {
			if (s.key().equals(setting.key())) throw new IllegalArgumentException("Duplicate setting key " + setting.key());
		}
		setting.group = this;
		setting.section(currentSection);
		settings.add(setting);
		return setting;
	}

	public Setting.Bool bool(String key, boolean def, String label) {
		return add(new Setting.Bool(key, label, def));
	}

	public Setting.Int integer(String key, int def, int min, int max, int step, String label) {
		return add(new Setting.Int(key, label, def, min, max, step, null));
	}

	public Setting.Int integer(String key, int def, int min, int max, int step, String unit, String label) {
		return add(new Setting.Int(key, label, def, min, max, step, unit));
	}

	public Setting.Decimal decimal(String key, float def, float min, float max, float step, String format, String label) {
		return add(new Setting.Decimal(key, label, def, min, max, step, format));
	}

	public <E extends Enum<E>> Setting.Choice<E> choice(String key, E def, Function<E, String> names, String label) {
		return add(new Setting.Choice<>(key, label, def, names));
	}

	public Setting.Color color(String key, int def, boolean alpha, String label) {
		return add(new Setting.Color(key, label, def, alpha));
	}

	public Setting.Text text(String key, String def, int maxLength, String label) {
		return add(new Setting.Text(key, label, def, maxLength));
	}

	public @Nullable Setting<?> find(String key) {
		for (Setting<?> s : settings) if (s.key().equals(key)) return s;
		return null;
	}

	void markDirty() {
		dirty = true;
	}

	public void load() {
		JsonObject root = BloomStorage.readObject(file);
		if (root == null) {
			dirty = true;
			return;
		}
		JsonObject values = root.has("values") && root.get("values").isJsonObject() ? root.getAsJsonObject("values") : new JsonObject();
		unknown = values.deepCopy();
		for (Setting<?> s : settings) {
			JsonElement v = values.get(s.key());
			unknown.remove(s.key());
			if (v == null || v.isJsonNull()) continue;
			try {
				s.fromJson(v);
			} catch (RuntimeException e) {
				BloomClient.LOGGER.warn("Bloom: setting {} in {} is invalid, using the default", s.key(), file);
			}
		}
	}

	public void saveIfDirty() {
		if (dirty) {
			dirty = false;
			BloomStorage.writeAsync(file, BloomStorage.GSON.toJson(toJson()));
		}
	}

	public JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("schema", SCHEMA);
		JsonObject values = unknown.deepCopy();
		for (Setting<?> s : settings) values.add(s.key(), s.toJson());
		root.add("values", values);
		return root;
	}

	public void resetAll() {
		for (Setting<?> s : settings) s.reset();
	}
}
