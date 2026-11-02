package com.bloomclient.client.api.hud;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One placed instance of a widget in the HUD layout. Several elements may
 * use the same widget (duplicates with different styles). Public fields are
 * the persisted state; transient fields are runtime caches.
 */
public final class HudElement {
	public String id = UUID.randomUUID().toString();
	public String widget = "";
	public boolean visible = true;
	public HudAnchor anchor = HudAnchor.TOP_LEFT;
	/** Offset from the anchor in GUI pixels. */
	public int x, y;
	public HudStyle style = new HudStyle();
	public Map<String, String> options = new LinkedHashMap<>();

	public transient HudLines lines = new HudLines();
	public transient long nextUpdate;
	public transient int width, height;
	public transient int errors;
	public transient boolean layoutDirty = true;
	/** Whether the widget had data at the last update (false: hidden in game, placeholder in the Studio). */
	public transient boolean available;

	public boolean option(String key, boolean def) {
		String v = options.get(key);
		return v == null ? def : Boolean.parseBoolean(v);
	}

	public String option(String key, String def) {
		String v = options.get(key);
		return v == null ? def : v;
	}

	public void setOption(String key, String value) {
		options.put(key, value);
		nextUpdate = 0;
	}

	/** Copy with a new id and fresh caches. */
	public HudElement duplicate() {
		HudElement e = new HudElement();
		e.widget = widget;
		e.visible = visible;
		e.anchor = anchor;
		e.x = x;
		e.y = y;
		e.style = style.copy();
		e.options = new LinkedHashMap<>(options);
		return e;
	}

	/** Restores runtime fields after deserialization. */
	public void initTransient() {
		if (lines == null) lines = new HudLines();
		if (options == null) options = new LinkedHashMap<>();
		if (style == null) style = new HudStyle();
		if (anchor == null) anchor = HudAnchor.TOP_LEFT;
		if (widget == null) widget = "";
		if (id == null || id.isBlank()) id = UUID.randomUUID().toString();
		style.sanitize();
		nextUpdate = 0;
		layoutDirty = true;
	}
}
