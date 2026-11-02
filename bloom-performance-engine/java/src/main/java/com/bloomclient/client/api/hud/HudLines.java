package com.bloomclient.client.api.hud;

import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Content of one HUD element: lines of "label value" text, each optionally
 * with an item icon. Widgets fill it in {@link HudWidget#update}; line
 * objects are reused, and the renderer re-measures only when something
 * actually changed.
 */
public final class HudLines {
	public static final class Line {
		public String label = "";
		public String value = "";
		public ItemStack icon = ItemStack.EMPTY;
		/** 0 = use the element's text color. */
		public int valueColor;

		/** Internal: cached by the renderer. */
		public @Nullable Object cache;
		public int width;
	}

	private Line[] lines = new Line[4];
	private int count;
	private int writeIndex;
	private boolean changed = true;

	/** Starts a new content pass. */
	public void begin() {
		writeIndex = 0;
	}

	/** Ends the pass; lines not written again are dropped. */
	public void end() {
		if (writeIndex != count) {
			count = writeIndex;
			changed = true;
		}
	}

	public void add(String value) {
		add("", value, ItemStack.EMPTY, 0);
	}

	public void add(String label, String value) {
		add(label, value, ItemStack.EMPTY, 0);
	}

	public void add(String label, String value, int valueColor) {
		add(label, value, ItemStack.EMPTY, valueColor);
	}

	public void add(String label, String value, @Nullable ItemStack icon, int valueColor) {
		if (writeIndex == lines.length) {
			lines = java.util.Arrays.copyOf(lines, lines.length * 2);
		}
		Line line = lines[writeIndex];
		if (line == null) {
			line = lines[writeIndex] = new Line();
			changed = true;
		}
		ItemStack i = icon == null ? ItemStack.EMPTY : icon;
		if (writeIndex >= count || !line.label.equals(label) || !line.value.equals(value) || line.valueColor != valueColor
				|| !ItemStack.isSameItemSameComponents(line.icon, i) || line.icon.getCount() != i.getCount()) {
			line.label = label;
			line.value = value;
			line.valueColor = valueColor;
			line.icon = i;
			line.cache = null;
			changed = true;
		}
		writeIndex++;
	}

	public int size() {
		return count;
	}

	public Line get(int i) {
		return lines[i];
	}

	public boolean hasIcons() {
		for (int i = 0; i < count; i++) if (!lines[i].icon.isEmpty()) return true;
		return false;
	}

	/** Internal: true once after the content changed. */
	public boolean consumeChanged() {
		boolean c = changed;
		changed = false;
		return c;
	}

	public void markChanged() {
		changed = true;
		for (int i = 0; i < count; i++) lines[i].cache = null;
	}
}
