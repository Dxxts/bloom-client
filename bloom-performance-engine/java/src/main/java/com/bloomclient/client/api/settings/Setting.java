package com.bloomclient.client.api.settings;

import com.bloomclient.client.ui.Tr;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A persistent, typed setting. Settings are created through
 * {@link SettingsGroup} ({@code settings.bool("key", true, "Label")}),
 * saved automatically, rendered automatically on the mod's configuration
 * page and found by the Bloom Menu search.
 *
 * <p>Labels and descriptions may be translation keys ({@code bloom.*}) or
 * plain text.
 */
public abstract sealed class Setting<T> permits Setting.Bool, Setting.Int, Setting.Decimal, Setting.Choice, Setting.Color, Setting.Text {
	private final String key;
	private final String label;
	private @Nullable String description;
	private final T defaultValue;
	private T value;
	private @Nullable String section;
	private BooleanSupplier visible = () -> true;
	private final List<Consumer<T>> listeners = new ArrayList<>();
	@Nullable SettingsGroup group;

	protected Setting(String key, String label, T defaultValue) {
		this.key = key;
		this.label = label;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public String key() {
		return key;
	}

	public String label() {
		return Tr.handles(label) ? Tr.t(label) : label;
	}

	public @Nullable String description() {
		return description == null ? null : Tr.handles(description) ? Tr.t(description) : description;
	}

	public @Nullable String section() {
		return section == null ? null : Tr.handles(section) ? Tr.t(section) : section;
	}

	public T get() {
		return value;
	}

	public T defaultValue() {
		return defaultValue;
	}

	public void set(T newValue) {
		T sanitized = sanitize(newValue);
		if (sanitized == null || sanitized.equals(value)) return;
		value = sanitized;
		for (Consumer<T> l : listeners) l.accept(sanitized);
		if (group != null) group.markDirty();
	}

	public void reset() {
		set(defaultValue);
	}

	public boolean isDefault() {
		return value.equals(defaultValue);
	}

	public boolean isVisible() {
		return visible.getAsBoolean();
	}

	/** Description shown under the label. */
	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S describe(String description) {
		this.description = description;
		return (S) this;
	}

	/** Shown only while {@code condition} holds (e.g. a sub-option of a switch). */
	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S visibleWhen(BooleanSupplier condition) {
		this.visible = condition;
		return (S) this;
	}

	@SuppressWarnings("unchecked")
	public <S extends Setting<T>> S onChange(Consumer<T> listener) {
		listeners.add(listener);
		return (S) this;
	}

	void section(@Nullable String section) {
		this.section = section;
	}

	protected @Nullable T sanitize(@Nullable T v) {
		return v;
	}

	abstract JsonElement toJson();

	abstract void fromJson(JsonElement json);

	void loadValue(T v) {
		T sanitized = sanitize(v);
		if (sanitized != null) value = sanitized;
	}

	public static final class Bool extends Setting<Boolean> {
		Bool(String key, String label, boolean def) {
			super(key, label, def);
		}

		public boolean on() {
			return get();
		}

		public void toggle() {
			set(!get());
		}

		@Override
		JsonElement toJson() {
			return new JsonPrimitive(get());
		}

		@Override
		void fromJson(JsonElement json) {
			loadValue(json.getAsBoolean());
		}
	}

	public static final class Int extends Setting<Integer> {
		public final int min, max, step;
		private final @Nullable String unit;

		Int(String key, String label, int def, int min, int max, int step, @Nullable String unit) {
			super(key, label, def);
			this.min = min;
			this.max = max;
			this.step = Math.max(1, step);
			this.unit = unit;
		}

		public String format(int v) {
			return unit == null ? Integer.toString(v) : v + unit;
		}

		@Override
		protected Integer sanitize(@Nullable Integer v) {
			if (v == null) return null;
			int s = Math.round((v - min) / (float) step) * step + min;
			return Math.max(min, Math.min(max, s));
		}

		@Override
		JsonElement toJson() {
			return new JsonPrimitive(get());
		}

		@Override
		void fromJson(JsonElement json) {
			loadValue(json.getAsInt());
		}
	}

	public static final class Decimal extends Setting<Float> {
		public final float min, max, step;
		private final String format;

		Decimal(String key, String label, float def, float min, float max, float step, String format) {
			super(key, label, def);
			this.min = min;
			this.max = max;
			this.step = step;
			this.format = format;
		}

		public String format(float v) {
			return String.format(java.util.Locale.ROOT, format, v);
		}

		@Override
		protected Float sanitize(@Nullable Float v) {
			if (v == null || v.isNaN()) return null;
			float s = step > 0 ? Math.round(v / step) * step : v;
			return Math.max(min, Math.min(max, s));
		}

		@Override
		JsonElement toJson() {
			return new JsonPrimitive(get());
		}

		@Override
		void fromJson(JsonElement json) {
			loadValue(json.getAsFloat());
		}
	}

	public static final class Choice<E extends Enum<E>> extends Setting<E> {
		private final Class<E> type;
		private final java.util.function.Function<E, String> names;

		Choice(String key, String label, E def, java.util.function.Function<E, String> names) {
			super(key, label, def);
			this.type = def.getDeclaringClass();
			this.names = names;
		}

		public E[] values() {
			return type.getEnumConstants();
		}

		public String name(E value) {
			String n = names.apply(value);
			return Tr.handles(n) ? Tr.t(n) : n;
		}

		@Override
		JsonElement toJson() {
			return new JsonPrimitive(get().name());
		}

		@Override
		void fromJson(JsonElement json) {
			try {
				loadValue(Enum.valueOf(type, json.getAsString()));
			} catch (IllegalArgumentException ignored) {
				// Unknown value from another version: keep the default.
			}
		}
	}

	public static final class Color extends Setting<Integer> {
		public final boolean alpha;

		Color(String key, String label, int def, boolean alpha) {
			super(key, label, def);
			this.alpha = alpha;
		}

		@Override
		protected Integer sanitize(@Nullable Integer v) {
			return v == null ? null : alpha ? v : (v | 0xFF000000);
		}

		@Override
		JsonElement toJson() {
			return new JsonPrimitive(String.format("#%08X", get()));
		}

		@Override
		void fromJson(JsonElement json) {
			String s = json.getAsString().replace("#", "");
			try {
				loadValue((int) Long.parseLong(s.length() == 6 ? "FF" + s : s, 16));
			} catch (NumberFormatException ignored) {
				// Keep the default.
			}
		}
	}

	public static final class Text extends Setting<String> {
		public final int maxLength;

		Text(String key, String label, String def, int maxLength) {
			super(key, label, def);
			this.maxLength = maxLength;
		}

		@Override
		protected String sanitize(@Nullable String v) {
			if (v == null) return null;
			return v.length() > maxLength ? v.substring(0, maxLength) : v;
		}

		@Override
		JsonElement toJson() {
			return new JsonPrimitive(get());
		}

		@Override
		void fromJson(JsonElement json) {
			loadValue(json.getAsString());
		}
	}
}
