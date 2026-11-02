package com.bloomclient.client.api.hud;

/** Visual properties of one HUD element. Serialized as-is in the layout file. */
public final class HudStyle {
	public enum Align {
		LEFT, CENTER, RIGHT
	}

	public enum FontChoice {
		DEFAULT("minecraft:default", "bloom.hud.font.default"),
		UNIFORM("minecraft:uniform", "bloom.hud.font.uniform");

		public final String id;
		public final String translationKey;

		FontChoice(String id, String translationKey) {
			this.id = id;
			this.translationKey = translationKey;
		}
	}

	public int textColor = 0xFFFFFFFF;
	public int labelColor = 0xFF5CE1A8;
	/** RGB of the background; its alpha comes from {@link #backgroundOpacity}. */
	public int backgroundColor = 0xFF0B0D0C;
	public float backgroundOpacity = 0.55f;
	public boolean background = true;
	public boolean border = false;
	public int borderColor = 0xFF5CE1A8;
	public int borderWidth = 1;
	public int cornerRadius = 3;
	public int padding = 4;
	public float textScale = 1.0f;
	public FontChoice font = FontChoice.DEFAULT;
	public boolean textShadow = true;
	public int lineSpacing = 2;
	/** Opacity of the whole element. */
	public float opacity = 1.0f;
	public Align align = Align.LEFT;

	public HudStyle copy() {
		HudStyle s = new HudStyle();
		s.textColor = textColor;
		s.labelColor = labelColor;
		s.backgroundColor = backgroundColor;
		s.backgroundOpacity = backgroundOpacity;
		s.background = background;
		s.border = border;
		s.borderColor = borderColor;
		s.borderWidth = borderWidth;
		s.cornerRadius = cornerRadius;
		s.padding = padding;
		s.textScale = textScale;
		s.font = font;
		s.textShadow = textShadow;
		s.lineSpacing = lineSpacing;
		s.opacity = opacity;
		s.align = align;
		return s;
	}

	/** Clamps every value to its valid range (after import or migration). */
	public void sanitize() {
		backgroundOpacity = clamp(backgroundOpacity, 0, 1);
		borderWidth = Math.max(1, Math.min(3, borderWidth));
		cornerRadius = Math.max(0, Math.min(8, cornerRadius));
		padding = Math.max(0, Math.min(12, padding));
		textScale = clamp(textScale, 0.5f, 3f);
		lineSpacing = Math.max(0, Math.min(8, lineSpacing));
		opacity = clamp(opacity, 0.1f, 1f);
		if (font == null) font = FontChoice.DEFAULT;
		if (align == null) align = Align.LEFT;
	}

	private static float clamp(float v, float min, float max) {
		return Float.isNaN(v) ? min : Math.max(min, Math.min(max, v));
	}
}
