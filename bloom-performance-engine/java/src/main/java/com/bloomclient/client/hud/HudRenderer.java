package com.bloomclient.client.hud;

import com.bloomclient.client.api.hud.HudElement;
import com.bloomclient.client.api.hud.HudLines;
import com.bloomclient.client.api.hud.HudStyle;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Draw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * Draws HUD elements from their cached content. Measuring and building the
 * text sequences happens only when an element's content or style changed;
 * a frame only issues fills and text draws.
 */
public final class HudRenderer {
	private static final int ICON = 12;
	private static final Style UNIFORM = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.withDefaultNamespace("uniform")));

	/** Cached, measured text of one line. */
	private record LineCache(FormattedCharSequence label, FormattedCharSequence value, int labelWidth, int valueWidth) {
	}

	private HudRenderer() {
	}

	private static FormattedCharSequence sequence(String s, Style style) {
		return Language.getInstance().getVisualOrder(FormattedText.of(s, style));
	}

	private static int lineHeight(Font font, HudElement el) {
		return el.lines.hasIcons() ? Math.max(ICON, font.lineHeight) : font.lineHeight;
	}

	/** Re-measures the element if needed; sets {@link HudElement#width}/{@link HudElement#height}. */
	public static void layout(HudElement el) {
		boolean changed = el.lines.consumeChanged();
		if (!changed && !el.layoutDirty) return;
		el.layoutDirty = false;
		Font font = Minecraft.getInstance().font;
		HudStyle st = el.style;
		Style style = st.font == HudStyle.FontChoice.UNIFORM ? UNIFORM : Style.EMPTY;
		HudLines lines = el.lines;
		int n = lines.size();
		if (n == 0) {
			el.width = el.height = 0;
			return;
		}
		boolean icons = lines.hasIcons();
		int contentW = 0;
		for (int i = 0; i < n; i++) {
			HudLines.Line line = lines.get(i);
			LineCache c = (LineCache) line.cache;
			if (c == null || changed) {
				FormattedCharSequence l = sequence(line.label, style);
				FormattedCharSequence v = sequence(line.value, style);
				c = new LineCache(l, v, line.label.isEmpty() ? 0 : font.width(l), font.width(v));
				line.cache = c;
			}
			line.width = c.labelWidth + c.valueWidth + (icons && !line.icon.isEmpty() ? ICON + 2 : 0);
			contentW = Math.max(contentW, line.width);
		}
		int lh = lineHeight(font, el);
		int contentH = n * lh + (n - 1) * st.lineSpacing;
		el.width = Math.round(contentW * st.textScale) + st.padding * 2;
		el.height = Math.round(contentH * st.textScale) + st.padding * 2;
	}

	/** Top-left corner of the element on a screen of the given size, kept on screen. */
	public static int x(HudElement el, int screenWidth) {
		int x = el.anchor.screenX(screenWidth) + el.x - el.anchor.elementX(el.width);
		return Math.max(0, Math.min(screenWidth - el.width, x));
	}

	public static int y(HudElement el, int screenHeight) {
		int y = el.anchor.screenY(screenHeight) + el.y - el.anchor.elementY(el.height);
		return Math.max(0, Math.min(screenHeight - el.height, y));
	}

	private static int fade(int color, float opacity) {
		return BloomTheme.withAlpha(color, opacity);
	}

	public static void draw(GuiGraphicsExtractor g, HudElement el, int x, int y) {
		draw(g, el, x, y, 1f);
	}

	public static void draw(GuiGraphicsExtractor g, HudElement el, int x, int y, float extraOpacity) {
		HudLines lines = el.lines;
		int n = lines.size();
		if (n == 0 || el.width <= 0) return;
		HudStyle st = el.style;
		float op = st.opacity * extraOpacity;
		if (st.background) {
			int bg = (Math.round(255 * st.backgroundOpacity * op) << 24) | (st.backgroundColor & 0xFFFFFF);
			Draw.roundRect(g, x, y, el.width, el.height, st.cornerRadius, bg);
		}
		if (st.border) {
			Draw.roundRectOutline(g, x, y, el.width, el.height, st.cornerRadius, st.borderWidth, fade(st.borderColor, op));
		}
		Font font = Minecraft.getInstance().font;
		int lh = lineHeight(font, el);
		int contentW = Math.round((el.width - st.padding * 2) / st.textScale);
		int labelColor = fade(st.labelColor, op);
		int textColor = fade(st.textColor, op);
		var pose = g.pose();
		pose.pushMatrix();
		pose.translate(x + st.padding, y + st.padding);
		pose.scale(st.textScale, st.textScale);
		for (int i = 0; i < n; i++) {
			HudLines.Line line = lines.get(i);
			LineCache c = (LineCache) line.cache;
			if (c == null) continue;
			int ly = i * (lh + st.lineSpacing);
			int lx = switch (st.align) {
				case LEFT -> 0;
				case CENTER -> (contentW - line.width) / 2;
				case RIGHT -> contentW - line.width;
			};
			if (!line.icon.isEmpty()) {
				pose.pushMatrix();
				pose.translate(lx, ly + (lh - ICON) / 2f);
				pose.scale(ICON / 16f, ICON / 16f);
				g.item(line.icon, 0, 0);
				pose.popMatrix();
				lx += ICON + 2;
			}
			int ty = ly + (lh - font.lineHeight) / 2 + 1;
			if (c.labelWidth > 0) {
				g.text(font, c.label, lx, ty, labelColor, st.textShadow);
				lx += c.labelWidth;
			}
			g.text(font, c.value, lx, ty, line.valueColor != 0 ? fade(line.valueColor, op) : textColor, st.textShadow);
		}
		pose.popMatrix();
	}
}
