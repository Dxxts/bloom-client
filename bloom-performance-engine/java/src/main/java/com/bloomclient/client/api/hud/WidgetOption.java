package com.bloomclient.client.api.hud;

import com.bloomclient.client.ui.Tr;
import java.util.List;

/**
 * Per-element option of a widget, shown in the HUD Studio inspector and
 * stored in the element ({@link HudElement#option}).
 */
public record WidgetOption(String key, String label, Type type, String defaultValue, List<String> choices, List<String> choiceLabels) {
	public enum Type {
		BOOLEAN, CHOICE
	}

	public static WidgetOption bool(String key, String label, boolean def) {
		return new WidgetOption(key, label, Type.BOOLEAN, Boolean.toString(def), List.of(), List.of());
	}

	/** {@code choiceLabels} are translation keys or plain text, one per choice. */
	public static WidgetOption choice(String key, String label, String def, List<String> choices, List<String> choiceLabels) {
		return new WidgetOption(key, label, Type.CHOICE, def, choices, choiceLabels);
	}

	public String displayLabel() {
		return Tr.handles(label) ? Tr.t(label) : label;
	}

	public String choiceLabel(int i) {
		String l = choiceLabels.get(i);
		return Tr.handles(l) ? Tr.t(l) : l;
	}
}
