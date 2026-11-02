package com.bloomclient.client.ui;

import com.bloomclient.client.api.settings.Setting;
import com.bloomclient.client.api.settings.SettingsGroup;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Renders any {@link SettingsGroup} as Bloom setting rows: label and
 * description on the left, the matching control on the right, a reset button
 * when the value differs from the default. Used by the Settings page and by
 * every mod's configuration page, so all settings look and behave the same.
 */
public final class SettingsView {
	private static final int CONTROL_W = 120;

	private SettingsView() {
	}

	/** Draws the group at (x, y) and returns the height used. {@code highlight} flashes one setting (from search). */
	public static int render(Ui ui, String idPrefix, SettingsGroup group, int x, int y, int w, @Nullable String highlight) {
		int cy = y;
		String lastSection = null;
		for (Setting<?> s : group.all()) {
			if (!s.isVisible()) continue;
			String section = s.section();
			if (section != null && !Objects.equals(section, lastSection)) {
				if (cy > y) cy += 6;
				ui.text(section.toUpperCase(java.util.Locale.ROOT), x, cy + 2, BloomTheme.TEXT_MUTED);
				cy += 14;
				lastSection = section;
			}
			cy += row(ui, idPrefix + "." + s.key(), s, x, cy, w, s.key().equals(highlight)) + 4;
		}
		return cy - y;
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static int row(Ui ui, String id, Setting<?> s, int x, int y, int w, boolean highlight) {
		String desc = s.description();
		int textW = w - CONTROL_W - 40;
		List<String> descLines = desc == null ? List.of() : ui.wrap(desc, textW);
		int h = Math.max(26, 10 + 10 + descLines.size() * 10 + 4);
		boolean hov = ui.hovered(x, y, w, h);
		float hv = ui.anim(id + "#row", hov, 8);
		int bg = BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_2, hv);
		ui.round(x, y, w, h, BloomTheme.RADIUS, bg);
		if (highlight) {
			float pulse = (float) (0.5 + 0.5 * Math.sin(ui.now / 160.0));
			ui.outline(x, y, w, h, BloomTheme.RADIUS, BloomTheme.withAlpha(BloomTheme.ACCENT, 0.4f + 0.6f * pulse));
		}
		ui.text(ui.ellipsize(s.label(), textW), x + 9, y + 7, BloomTheme.TEXT);
		for (int i = 0; i < descLines.size(); i++) ui.text(descLines.get(i), x + 9, y + 18 + i * 10, BloomTheme.TEXT_MUTED);
		int cx = x + w - CONTROL_W - 10;
		int cyMid = y + h / 2;
		switch (s) {
			case Setting.Bool b -> {
				if (ui.toggle(id, x + w - 32, cyMid - 6, b.get())) b.toggle();
			}
			case Setting.Int in -> {
				String val = in.format(in.get());
				int vw = ui.width(val);
				float nv = ui.slider(id, cx, cyMid - 6, CONTROL_W - vw - 8, in.get(), in.min, in.max, in.step);
				in.set(Math.round(nv));
				ui.textRight(val, x + w - 10, cyMid - 4, BloomTheme.TEXT_DIM);
			}
			case Setting.Decimal d -> {
				String val = d.format(d.get());
				int vw = ui.width(val);
				d.set(ui.slider(id, cx, cyMid - 6, CONTROL_W - vw - 8, d.get(), d.min, d.max, d.step));
				ui.textRight(val, x + w - 10, cyMid - 4, BloomTheme.TEXT_DIM);
			}
			case Setting.Choice c -> {
				Enum[] values = c.values();
				String[] names = new String[values.length];
				int sel = 0;
				for (int i = 0; i < values.length; i++) {
					names[i] = c.name(values[i]);
					if (values[i] == c.get()) sel = i;
				}
				int picked = values.length <= 3 ? ui.segmented(id, cx, cyMid - 8, CONTROL_W, 16, names, sel)
						: ui.dropdown(id, cx, cyMid - 8, CONTROL_W, 16, names, sel);
				if (picked != sel) c.set(values[picked]);
			}
			case Setting.Color col -> col.set(ui.colorField(id, cx, cyMid - 8, CONTROL_W, 16, col.get(), col.alpha));
			case Setting.Text t -> {
				TextInput input = ui.state(id + "#input", () -> new TextInput(t.get()).maxLength(t.maxLength));
				if (!ui.isFocused(input) && !input.value().equals(t.get())) input.set(t.get());
				ui.textField(id, cx, cyMid - 8, CONTROL_W, 16, input, "");
				if (input.consumeChanged()) t.set(input.value());
			}
		}
		if (!s.isDefault() && ui.iconButton(id + "#reset", cx - 18, cyMid - 7, 14, Icon.RESET, Tr.t("bloom.common.reset"))) {
			s.reset();
		}
		return h;
	}
}
