package com.bloomclient.client.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import com.bloomclient.client.BloomClient;

/**
 * Bloom's immediate-mode UI toolkit, shared by the Bloom Menu, the HUD Studio
 * and the mod pages.
 *
 * <p>Screens forward their input events here; widgets are drawn and
 * hit-tested in the same call during rendering, so every screen stays a
 * single readable render function. A click is delivered to the first widget
 * that claims it in the frame after it happened. Popups (dropdowns, color
 * pickers) are drawn last, in a higher stratum, and own the clicks inside
 * their rectangle.
 *
 * <p>All coordinates are in the screen's canvas units (see
 * {@link BloomScreen}); {@link #alpha} fades everything drawn through it.
 */
public final class Ui {
	public final Minecraft mc = Minecraft.getInstance();
	public final Font font = mc.font;
	public GuiGraphicsExtractor g;
	public int mx = -10000, my = -10000;
	public int width, height;
	public float alpha = 1f;
	public long now;
	/** Seconds since the previous frame, clamped. */
	public float dt;
	private long lastFrame;

	// Input queued by the screen between frames.
	private boolean qClick, qRightClick, qDouble;
	private int qClickX, qClickY;
	private double qScroll;
	private boolean mouseDown;

	// Input of the current frame.
	private boolean click, rightClick, doubleClick;
	private int clickX, clickY;
	private double scroll;

	private @Nullable String active;
	private @Nullable String focusId;
	private @Nullable TextInput focus;
	private boolean focusTouched;
	private boolean frameClick;

	private final Map<String, float[]> anims = new HashMap<>();
	private final Map<String, ScrollState> scrolls = new HashMap<>();
	private final Map<String, Object> state = new HashMap<>();
	private final ArrayDeque<int[]> clips = new ArrayDeque<>();
	private final List<Runnable> deferred = new ArrayList<>();
	private @Nullable String openPopup;
	private int @Nullable [] popupRect;
	private int @Nullable [] nextPopupRect;
	private int @Nullable [] popupAnchor;
	private boolean drawingPopup;
	private boolean modal;
	private boolean drawingModal;
	private @Nullable String tooltip;
	private int tooltipX, tooltipY;

	// ---------------------------------------------------------------- frame

	public void begin(GuiGraphicsExtractor g, int mouseX, int mouseY, int width, int height) {
		this.g = g;
		this.mx = mouseX;
		this.my = mouseY;
		this.width = width;
		this.height = height;
		now = System.currentTimeMillis();
		dt = lastFrame == 0 ? 0.016f : Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		click = qClick;
		rightClick = qRightClick;
		doubleClick = qDouble;
		clickX = qClickX;
		clickY = qClickY;
		scroll = qScroll;
		qClick = qRightClick = qDouble = false;
		qScroll = 0;
		focusTouched = false;
		frameClick = click;
		popupRect = nextPopupRect;
		nextPopupRect = null;
		tooltip = null;
		// A click outside an open popup closes it and is swallowed.
		if (openPopup != null && (click || rightClick) && popupRect != null && !inside(popupRect, clickX, clickY)
				&& (popupAnchor == null || !inside(popupAnchor, clickX, clickY))) {
			openPopup = null;
			popupRect = null;
			click = rightClick = false;
		}
		if (openPopup == null) {
			popupRect = null;
		}
	}

	public void end() {
		if (!deferred.isEmpty()) {
			g.nextStratum();
			drawingPopup = true;
			for (Runnable r : deferred) r.run();
			drawingPopup = false;
			deferred.clear();
		}
		if (tooltip != null) {
			drawTooltip();
		}
		if (frameClick && focus != null && !focusTouched) {
			clearFocus();
		}
		if (!mouseDown) {
			active = null;
		}
		click = rightClick = doubleClick = false;
		scroll = 0;
	}

	// ---------------------------------------------------------------- input from the screen

	public void onMouseDown(double x, double y, int button, boolean dbl) {
		qClickX = (int) Math.floor(x);
		qClickY = (int) Math.floor(y);
		if (button == 1) {
			qClick = true;
			qDouble = dbl;
			mouseDown = true;
		} else if (button == 3) {
			qRightClick = true;
		}
	}

	public void onMouseUp(int button) {
		if (button == 1) mouseDown = false;
	}

	public void onScroll(double amount) {
		qScroll += amount;
	}

	/** Returns true when a focused text field consumed the key. */
	public boolean onKey(KeyEvent e) {
		if (focus == null) return false;
		if (e.isEscape()) {
			clearFocus();
			return true;
		}
		return focus.keyPressed(e) || !e.hasControlDown();
	}

	public boolean onChar(CharacterEvent e) {
		return focus != null && focus.charTyped(e);
	}

	public boolean hasFocus() {
		return focus != null;
	}

	public boolean isFocused(TextInput input) {
		return focus == input;
	}

	public void focus(String id, TextInput input) {
		if (focus == input) return;
		clearFocus();
		focusId = id;
		focus = input;
		mc.textInputManager().startTextInput(input);
	}

	public void clearFocus() {
		if (focus != null) {
			mc.textInputManager().stopTextInput(focus);
		}
		focus = null;
		focusId = null;
	}

	public boolean isMouseDown() {
		return mouseDown;
	}

	public boolean isActive(String id) {
		return id.equals(active);
	}

	public void setActive(@Nullable String id) {
		active = id;
	}

	public @Nullable String active() {
		return active;
	}

	/** While on, only widgets drawn between {@link #drawingModal(boolean) drawingModal(true/false)} receive input. */
	public void modal(boolean on) {
		modal = on;
	}

	public void drawingModal(boolean on) {
		drawingModal = on;
	}

	public boolean popupOpen() {
		return openPopup != null;
	}

	public void closePopups() {
		openPopup = null;
	}

	// ---------------------------------------------------------------- hit testing

	private static boolean inside(int[] r, int x, int y) {
		return x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3];
	}

	private boolean inClip(int x, int y) {
		int[] c = clips.peek();
		return c == null || inside(c, x, y);
	}

	private boolean allowed(int x, int y) {
		if (!inClip(x, y)) return false;
		if (modal && !drawingModal) return drawingPopup;
		return drawingPopup || popupRect == null || !inside(popupRect, x, y);
	}

	public boolean hovered(int x, int y, int w, int h) {
		return mx >= x && my >= y && mx < x + w && my < y + h && allowed(mx, my);
	}

	/** Left click inside the rectangle this frame; the click is consumed. */
	public boolean clicked(int x, int y, int w, int h) {
		if (click && clickX >= x && clickY >= y && clickX < x + w && clickY < y + h && allowed(clickX, clickY)) {
			click = false;
			return true;
		}
		return false;
	}

	public boolean doubleClicked() {
		return doubleClick;
	}

	public boolean rightClicked(int x, int y, int w, int h) {
		if (rightClick && clickX >= x && clickY >= y && clickX < x + w && clickY < y + h && allowed(clickX, clickY)) {
			rightClick = false;
			return true;
		}
		return false;
	}

	/** True if a left click happened this frame and nothing consumed it yet. */
	public boolean pendingClick() {
		return click;
	}

	public int clickX() {
		return clickX;
	}

	public int clickY() {
		return clickY;
	}

	public void consumeClick() {
		click = false;
	}

	public double takeScroll(int x, int y, int w, int h) {
		if (scroll != 0 && hovered(x, y, w, h)) {
			double s = scroll;
			scroll = 0;
			return s;
		}
		return 0;
	}

	// ---------------------------------------------------------------- state and animation

	@SuppressWarnings("unchecked")
	public <T> T state(String id, java.util.function.Supplier<T> init) {
		return (T) state.computeIfAbsent(id, k -> init.get());
	}

	public void forget(String id) {
		state.remove(id);
	}

	/** Eased 0..1 value moving towards {@code on ? 1 : 0}. */
	public float anim(String id, boolean on, float speed) {
		float[] v = anims.computeIfAbsent(id, k -> new float[] {on ? 1 : 0});
		float target = on ? 1 : 0;
		float step = speed * dt;
		v[0] = v[0] < target ? Math.min(target, v[0] + step) : Math.max(target, v[0] - step);
		return v[0] * v[0] * (3 - 2 * v[0]);
	}

	public int c(int color) {
		return alpha >= 0.999f ? color : BloomTheme.withAlpha(color, alpha);
	}

	public void defer(Runnable r) {
		deferred.add(r);
	}

	public void tooltip(String text) {
		tooltip = text;
		tooltipX = mx;
		tooltipY = my;
	}

	// ---------------------------------------------------------------- clipping and scrolling

	public void pushClip(int x, int y, int w, int h) {
		int[] parent = clips.peek();
		int[] r = {x, y, w, h};
		if (parent != null) {
			int x0 = Math.max(x, parent[0]), y0 = Math.max(y, parent[1]);
			int x1 = Math.min(x + w, parent[0] + parent[2]), y1 = Math.min(y + h, parent[1] + parent[3]);
			r = new int[] {x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)};
		}
		clips.push(r);
		g.enableScissor(x, y, x + w, y + h);
	}

	public void popClip() {
		clips.pop();
		g.disableScissor();
	}

	private static final class ScrollState {
		float offset, target;
		boolean dragging;
		int dragStartY;
		float dragStartOffset;
	}

	/** Starts a vertical scroll area. Returns the current offset to subtract from content y. */
	public int beginScroll(String id, int x, int y, int w, int h) {
		ScrollState s = scrolls.computeIfAbsent(id, k -> new ScrollState());
		pushClip(x, y, w, h);
		return Math.round(s.offset);
	}

	public void endScroll(String id, int x, int y, int w, int h, int contentHeight) {
		popClip();
		ScrollState s = scrolls.get(id);
		if (s == null) return;
		float max = Math.max(0, contentHeight - h);
		double wheel = takeScroll(x, y, w, h);
		if (wheel != 0) s.target -= (float) wheel * 28f;
		int barX = x + w - 4;
		if (max > 0) {
			int thumbH = Math.max(16, h * h / Math.max(1, contentHeight));
			int thumbY = y + Math.round((h - thumbH) * (s.target / max));
			if (clicked(barX - 2, y, 7, h)) {
				s.dragging = true;
				s.dragStartY = my;
				s.dragStartOffset = s.target;
				if (my < thumbY || my > thumbY + thumbH) {
					s.target = (my - y - thumbH / 2f) / (h - thumbH) * max;
					s.dragStartOffset = s.target;
				}
			}
			if (s.dragging) {
				if (!mouseDown) s.dragging = false;
				else s.target = s.dragStartOffset + (my - s.dragStartY) * max / Math.max(1, h - thumbH);
			}
			s.target = Math.max(0, Math.min(max, s.target));
			s.offset += (s.target - s.offset) * Math.min(1f, dt * 18f);
			if (Math.abs(s.target - s.offset) < 0.5f) s.offset = s.target;
			boolean hot = s.dragging || hovered(barX - 2, y, 7, h);
			float hv = anim(id + "#bar", hot, 8);
			Draw.roundRect(g, barX, thumbY, 3, thumbH, 1, c(BloomTheme.lerp(0x40FFFFFF, BloomTheme.ACCENT, hv * 0.8f)));
		} else {
			s.target = s.offset = 0;
		}
	}

	public void scrollTo(String id, float offset) {
		ScrollState s = scrolls.computeIfAbsent(id, k -> new ScrollState());
		s.target = Math.max(0, offset);
	}

	public void resetScroll(String id) {
		ScrollState s = scrolls.get(id);
		if (s != null) s.offset = s.target = 0;
	}

	// ---------------------------------------------------------------- text

	/**
	 * Bloom's UI fonts: Inter (TrueType, rasterized at 4× so it stays smooth
	 * at any menu scale), loaded from Bloom's built-in resource pack; glyphs
	 * Inter lacks fall back to Minecraft's font. Off in Settings, the UI uses
	 * Minecraft's pixel font as before.
	 */
	private static final Style UI_FONT = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("bloom", "ui")));
	private static final Style UI_FONT_BOLD = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath("bloom", "ui_bold")));

	private static boolean smooth() {
		return BloomClient.settings().smoothFont.on();
	}

	private static Component styled(String s, boolean bold) {
		return Component.literal(s).withStyle(bold ? UI_FONT_BOLD : UI_FONT);
	}

	public void text(String s, int x, int y, int color) {
		if (smooth()) g.text(font, styled(s, false), x, y, c(color), false);
		else g.text(font, s, x, y, c(color), false);
	}

	/** Same metrics as {@link #text}, heavier weight: titles and section headers. */
	public void textBold(String s, int x, int y, int color) {
		if (smooth()) g.text(font, styled(s, true), x, y, c(color), false);
		else g.text(font, s, x, y, c(color), false);
	}

	public void textShadow(String s, int x, int y, int color) {
		if (smooth()) g.text(font, styled(s, false), x, y, c(color), true);
		else g.text(font, s, x, y, c(color), true);
	}

	public void textCentered(String s, int cx, int y, int color) {
		text(s, cx - width(s) / 2, y, color);
	}

	public void textRight(String s, int right, int y, int color) {
		text(s, right - width(s), y, color);
	}

	/** Text widths are measured often (ellipsizing, layout); cached per string and font mode. */
	private final Map<String, Integer> widthCache = new HashMap<>();
	private boolean widthCacheSmooth;

	public int width(String s) {
		boolean sm = smooth();
		if (sm != widthCacheSmooth || widthCache.size() > 4096) {
			widthCache.clear();
			widthCacheSmooth = sm;
		}
		Integer w = widthCache.get(s);
		if (w == null) {
			w = sm ? font.width(styled(s, false)) : font.width(s);
			widthCache.put(s, w);
		}
		return w;
	}

	public int widthBold(String s) {
		return smooth() ? font.width(styled(s, true)) : font.width(s);
	}

	/** Longest prefix of {@code s} that fits in {@code maxWidth}, measured with the UI font. */
	public String fit(String s, int maxWidth) {
		return smooth() ? font.substrByWidth(styled(s, false), maxWidth).getString() : font.plainSubstrByWidth(s, maxWidth);
	}

	/** Text scaled around its top-left corner (bold weight with the smooth font). */
	public void textScaled(String s, int x, int y, float scale, int color) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		if (smooth()) g.text(font, styled(s, true), 0, 0, c(color), false);
		else g.text(font, s, 0, 0, c(color), false);
		g.pose().popMatrix();
	}

	public String ellipsize(String s, int maxWidth) {
		if (width(s) <= maxWidth) return s;
		int dots = width("…");
		return fit(s, Math.max(0, maxWidth - dots)) + "…";
	}

	/** Wrapped text; returns the height used. */
	public int paragraph(String s, int x, int y, int maxWidth, int color) {
		int lineY = y;
		for (String line : wrap(s, maxWidth)) {
			text(line, x, lineY, color);
			lineY += 11;
		}
		return lineY - y;
	}

	public List<String> wrap(String s, int maxWidth) {
		List<String> lines = new ArrayList<>();
		for (String para : s.split("\n", -1)) {
			StringBuilder line = new StringBuilder();
			for (String word : para.split(" ")) {
				String candidate = line.isEmpty() ? word : line + " " + word;
				if (width(candidate) > maxWidth && !line.isEmpty()) {
					lines.add(line.toString());
					line.setLength(0);
					line.append(word);
				} else {
					line.setLength(0);
					line.append(candidate);
				}
			}
			lines.add(line.toString());
		}
		return lines;
	}

	// ---------------------------------------------------------------- shapes

	public void rect(int x, int y, int w, int h, int color) {
		Draw.rect(g, x, y, w, h, c(color));
	}

	public void round(int x, int y, int w, int h, int r, int color) {
		Draw.roundRect(g, x, y, w, h, r, c(color));
	}

	public void outline(int x, int y, int w, int h, int r, int color) {
		Draw.hairline(g, x, y, w, h, r, c(color));
	}

	public void icon(Icon icon, int x, int y, int color) {
		icon.draw(g, x, y, 1, c(color));
	}

	// ---------------------------------------------------------------- widgets

	public enum Kind {
		PRIMARY, SECONDARY, GHOST, DANGER
	}

	public boolean button(String id, int x, int y, int w, int h, String label, Kind kind) {
		return button(id, x, y, w, h, label, null, kind, true);
	}

	public boolean button(String id, int x, int y, int w, int h, String label, @Nullable Icon icon, Kind kind, boolean enabled) {
		boolean hov = enabled && hovered(x, y, w, h);
		float hv = anim(id, hov, 10);
		int bg, fg;
		switch (kind) {
			case PRIMARY -> {
				bg = BloomTheme.lerp(BloomTheme.ACCENT, BloomTheme.ACCENT_HOVER, hv);
				fg = BloomTheme.ACCENT_TEXT;
			}
			case DANGER -> {
				bg = BloomTheme.lerp(BloomTheme.DANGER_SOFT, 0x50E5636B, hv);
				fg = BloomTheme.DANGER;
			}
			case GHOST -> {
				bg = BloomTheme.lerp(0x00000000, BloomTheme.SURFACE_HOVER, hv);
				fg = BloomTheme.lerp(BloomTheme.TEXT_DIM, BloomTheme.TEXT, hv);
			}
			default -> {
				bg = BloomTheme.lerp(BloomTheme.SURFACE_2, BloomTheme.SURFACE_HOVER, hv);
				fg = BloomTheme.TEXT;
			}
		}
		if (!enabled) {
			bg = BloomTheme.withAlpha(bg, 0.4f);
			fg = BloomTheme.TEXT_MUTED;
		}
		round(x, y, w, h, BloomTheme.RADIUS_SMALL, bg);
		if (kind == Kind.SECONDARY) outline(x, y, w, h, BloomTheme.RADIUS_SMALL, BloomTheme.withAlpha(BloomTheme.BORDER_STRONG, 0.7f + 0.3f * hv));
		int contentW = (icon != null ? Icon.SIZE + (label.isEmpty() ? 0 : 5) : 0) + width(label);
		int cx = x + (w - contentW) / 2;
		if (icon != null) {
			icon(icon, cx, y + (h - Icon.SIZE) / 2, fg);
			cx += Icon.SIZE + 5;
		}
		if (!label.isEmpty()) text(ellipsize(label, w - 6), cx, y + (h - 8) / 2, fg);
		return enabled && clicked(x, y, w, h);
	}

	public boolean iconButton(String id, int x, int y, int size, Icon icon, @Nullable String tip) {
		return iconButton(id, x, y, size, icon, tip, false, true);
	}

	public boolean iconButton(String id, int x, int y, int size, Icon icon, @Nullable String tip, boolean selected, boolean enabled) {
		boolean hov = enabled && hovered(x, y, size, size);
		float hv = anim(id, hov || selected, 10);
		if (selected) round(x, y, size, size, BloomTheme.RADIUS_SMALL, BloomTheme.ACCENT_SOFT);
		else round(x, y, size, size, BloomTheme.RADIUS_SMALL, BloomTheme.withAlpha(BloomTheme.SURFACE_HOVER, hv));
		int color = !enabled ? BloomTheme.TEXT_MUTED : selected ? BloomTheme.ACCENT : BloomTheme.lerp(BloomTheme.TEXT_DIM, BloomTheme.TEXT, hv);
		icon(icon, x + (size - Icon.SIZE) / 2, y + (size - Icon.SIZE) / 2, color);
		if (hov && tip != null) tooltip(tip);
		return enabled && clicked(x, y, size, size);
	}

	/** Width of a {@link #toolButton} with this label and icon. */
	public int toolButtonWidth(String label, @Nullable Icon icon) {
		return width(label) + (icon != null ? Icon.SIZE + 5 : 0) + 14;
	}

	/**
	 * Labeled toolbar button that can stay selected (used as a toggle for
	 * drawers and popovers); returns true when clicked.
	 */
	public boolean toolButton(String id, int x, int y, int h, String label, @Nullable Icon icon, boolean selected, boolean enabled, @Nullable String tip) {
		int w = toolButtonWidth(label, icon);
		boolean hov = enabled && hovered(x, y, w, h);
		float hv = anim(id, hov, 10);
		int bg = selected ? BloomTheme.ACCENT_SOFT : BloomTheme.lerp(BloomTheme.SURFACE_2, BloomTheme.SURFACE_HOVER, hv);
		round(x, y, w, h, BloomTheme.RADIUS_SMALL, bg);
		outline(x, y, w, h, BloomTheme.RADIUS_SMALL, selected ? BloomTheme.ACCENT_DIM : BloomTheme.withAlpha(BloomTheme.BORDER_STRONG, 0.7f + 0.3f * hv));
		int fg = !enabled ? BloomTheme.TEXT_MUTED : selected ? BloomTheme.ACCENT : BloomTheme.lerp(BloomTheme.TEXT_DIM, BloomTheme.TEXT, hv);
		int cx = x + 7;
		if (icon != null) {
			icon(icon, cx, y + (h - Icon.SIZE) / 2, fg);
			cx += Icon.SIZE + 5;
		}
		text(label, cx, y + (h - 8) / 2, fg);
		if (hov && tip != null) tooltip(tip);
		return enabled && clicked(x, y, w, h);
	}

	/** A keyboard key (or mouse action) drawn as a small cap; returns its width. */
	public int keycap(String label, int x, int y) {
		int w = Math.max(12, width(label) + 8);
		round(x, y, w, 12, 2, BloomTheme.SURFACE_ACTIVE);
		rect(x + 2, y + 11, w - 4, 1, 0x60000000);
		outline(x, y, w, 12, 2, BloomTheme.BORDER_STRONG);
		text(label, x + (w - width(label)) / 2, y + 2, BloomTheme.TEXT);
		return w;
	}

	/** Thin vertical divider between toolbar groups. */
	public void divider(int x, int y, int h) {
		rect(x, y, 1, h, BloomTheme.BORDER_STRONG);
	}

	/** Switch; returns true when clicked (the caller flips the value). */
	public boolean toggle(String id, int x, int y, boolean value) {
		return toggle(id, x, y, value, true);
	}

	public boolean toggle(String id, int x, int y, boolean value, boolean enabled) {
		int w = 22, h = 12;
		boolean hov = enabled && hovered(x, y, w, h);
		float on = anim(id + "#on", value, 9);
		float hv = anim(id, hov, 10);
		int track = BloomTheme.lerp(BloomTheme.lerp(BloomTheme.SURFACE_ACTIVE, 0xFF353D3A, hv), BloomTheme.ACCENT, on);
		if (!enabled) track = BloomTheme.withAlpha(track, 0.4f);
		round(x, y, w, h, 6, track);
		int knobX = x + 2 + Math.round(on * (w - 12));
		round(knobX, y + 2, 8, 8, 4, value ? 0xFF07150F : BloomTheme.TEXT_DIM);
		if (value) round(knobX, y + 2, 8, 8, 4, BloomTheme.withAlpha(0xFFFFFFFF, 0.95f));
		return enabled && clicked(x, y, w, h);
	}

	/** Horizontal slider; returns the (possibly changed) value. */
	public float slider(String id, int x, int y, int w, float value, float min, float max, float step) {
		int h = 12;
		boolean hov = hovered(x - 2, y, w + 4, h);
		if (clicked(x - 2, y, w + 4, h)) active = id;
		boolean drag = isActive(id) && mouseDown;
		if (drag) {
			float t = Math.max(0, Math.min(1, (mx - x) / (float) w));
			value = min + t * (max - min);
			if (step > 0) value = Math.round(value / step) * step;
			value = Math.max(min, Math.min(max, value));
		}
		float t = max > min ? (value - min) / (max - min) : 0;
		float hv = anim(id, hov || drag, 10);
		int trackY = y + h / 2 - 1;
		round(x, trackY, w, 3, 1, BloomTheme.SURFACE_ACTIVE);
		round(x, trackY, Math.round(w * t), 3, 1, BloomTheme.ACCENT);
		int kx = x + Math.round(w * t);
		int kr = 3 + Math.round(hv);
		Draw.circle(g, kx, trackY + 1, kr + 1, c(0x60000000));
		Draw.circle(g, kx, trackY + 1, kr, c(BloomTheme.lerp(0xFFE9EEEC, 0xFFFFFFFF, hv)));
		return value;
	}

	public void textField(String id, int x, int y, int w, int h, TextInput input, String placeholder) {
		textField(id, x, y, w, h, input, placeholder, null);
	}

	public void textField(String id, int x, int y, int w, int h, TextInput input, String placeholder, @Nullable Icon icon) {
		boolean focused = focus == input;
		boolean hov = hovered(x, y, w, h);
		float hv = anim(id, hov || focused, 10);
		round(x, y, w, h, BloomTheme.RADIUS_SMALL, BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_2, hv));
		outline(x, y, w, h, BloomTheme.RADIUS_SMALL, focused ? BloomTheme.ACCENT_DIM : BloomTheme.lerp(BloomTheme.BORDER, BloomTheme.BORDER_STRONG, hv));
		int pad = 5;
		int tx = x + pad;
		if (icon != null) {
			icon(icon, x + 5, y + (h - Icon.SIZE) / 2, focused ? BloomTheme.ACCENT : BloomTheme.TEXT_MUTED);
			tx += Icon.SIZE + 4;
		}
		int innerW = x + w - pad - tx;
		int ty = y + (h - 8) / 2;
		String value = input.value();
		// Keep the cursor visible.
		if (input.scroll > input.cursor()) input.scroll = input.cursor();
		while (input.scroll < input.cursor() && width(value.substring(input.scroll, input.cursor())) > innerW - 2) input.scroll++;
		if (input.scroll > value.length()) input.scroll = 0;
		String visible = fit(value.substring(input.scroll), innerW);
		if (clicked(x, y, w, h)) {
			focus(id, input);
			int rel = mx - tx;
			int idx = input.scroll + fit(visible, Math.max(0, rel)).length();
			input.moveCursorTo(idx, false);
			if (doubleClick) input.selectAll();
		}
		if (focus == input && frameClick && clickX >= x && clickY >= y && clickX < x + w && clickY < y + h) focusTouched = true;
		pushClip(tx - 1, y, innerW + 2, h);
		if (value.isEmpty() && !focused) {
			text(ellipsize(placeholder, innerW), tx, ty, BloomTheme.TEXT_MUTED);
		} else {
			int selStart = Math.max(input.selectionStart(), input.scroll) - input.scroll;
			int selEnd = Math.min(input.selectionEnd(), input.scroll + visible.length()) - input.scroll;
			if (focused && selEnd > selStart) {
				int sx = tx + width(visible.substring(0, selStart));
				int ex = tx + width(visible.substring(0, selEnd));
				rect(sx, ty - 1, ex - sx, 10, 0x805CE1A8);
			}
			text(visible, tx, ty, BloomTheme.TEXT);
			if (value.isEmpty()) text(ellipsize(placeholder, innerW), tx, ty, BloomTheme.TEXT_MUTED);
			if (focused && ((now - input.lastEdit) < 500 || (now / 530) % 2 == 0)) {
				int cpos = Math.max(0, Math.min(visible.length(), input.cursor() - input.scroll));
				int cx = tx + width(visible.substring(0, cpos));
				rect(cx, ty - 1, 1, 10, BloomTheme.ACCENT);
			}
		}
		popClip();
		if (focused) {
			mc.textInputManager().setTextInputArea(x, y, x + w, y + h);
		}
	}

	/** Pill-shaped selector; returns the selected index. */
	public int segmented(String id, int x, int y, int w, int h, String[] options, int selected) {
		round(x, y, w, h, BloomTheme.RADIUS_SMALL, BloomTheme.SURFACE);
		int n = options.length;
		for (int i = 0; i < n; i++) {
			int x0 = x + w * i / n, x1 = x + w * (i + 1) / n;
			boolean sel = i == selected;
			boolean hov = hovered(x0, y, x1 - x0, h);
			float hv = anim(id + "#" + i, hov, 10);
			if (sel) round(x0 + 1, y + 1, x1 - x0 - 2, h - 2, BloomTheme.RADIUS_SMALL - 1, BloomTheme.SURFACE_ACTIVE);
			int color = sel ? BloomTheme.ACCENT : BloomTheme.lerp(BloomTheme.TEXT_MUTED, BloomTheme.TEXT, hv);
			String label = ellipsize(options[i], x1 - x0 - 4);
			textCentered(label, (x0 + x1) / 2, y + (h - 8) / 2, color);
			if (clicked(x0, y, x1 - x0, h)) selected = i;
		}
		return selected;
	}

	/** Dropdown; returns the selected index (a choice made in the popup is returned on the next frame). */
	public int dropdown(String id, int x, int y, int w, int h, String[] options, int selected) {
		Integer picked = (Integer) state.remove(id + "#picked");
		if (picked != null) selected = picked;
		boolean open = id.equals(openPopup);
		boolean hov = hovered(x, y, w, h);
		float hv = anim(id, hov || open, 10);
		round(x, y, w, h, BloomTheme.RADIUS_SMALL, BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_2, hv));
		outline(x, y, w, h, BloomTheme.RADIUS_SMALL, open ? BloomTheme.ACCENT_DIM : BloomTheme.BORDER);
		String label = selected >= 0 && selected < options.length ? options[selected] : "—";
		text(ellipsize(label, w - 20), x + 6, y + (h - 8) / 2, BloomTheme.TEXT);
		icon(open ? Icon.UP : Icon.DOWN, x + w - 13, y + (h - Icon.SIZE) / 2, BloomTheme.TEXT_DIM);
		if (clicked(x, y, w, h)) {
			openPopup = open ? null : id;
			open = !open;
		}
		if (open) {
			int itemH = 14;
			int visible = Math.min(options.length, 10);
			int ph = visible * itemH + 4;
			int py = y + h + 2;
			if (py + ph > height - 4) py = Math.max(4, y - ph - 2);
			int fpy = py;
			int fsel = selected;
			nextPopupRect = new int[] {x, py, w, ph};
			popupAnchor = new int[] {x, y, w, h};
			defer(() -> {
				Draw.shadow(g, x, fpy, w, ph, BloomTheme.RADIUS, 3);
				round(x, fpy, w, ph, BloomTheme.RADIUS, BloomTheme.POPUP);
				outline(x, fpy, w, ph, BloomTheme.RADIUS, BloomTheme.BORDER_STRONG);
				int off = beginScroll(id + "#list", x, fpy + 2, w, ph - 4);
				for (int i = 0; i < options.length; i++) {
					int iy = fpy + 2 + i * itemH - off;
					boolean ih = hovered(x + 2, iy, w - 4, itemH);
					if (ih || i == fsel) round(x + 2, iy, w - 4, itemH, 2, i == fsel ? BloomTheme.ACCENT_SOFT : BloomTheme.SURFACE_HOVER);
					text(ellipsize(options[i], w - 12), x + 6, iy + 3, i == fsel ? BloomTheme.ACCENT : BloomTheme.TEXT);
					if (clicked(x + 2, iy, w - 4, itemH)) {
						state.put(id + "#picked", i);
						openPopup = null;
					}
				}
				endScroll(id + "#list", x, fpy + 2, w, ph - 4, options.length * itemH);
			});
		}
		return selected;
	}

	/** Color swatch with a popup picker (palette, hue, saturation/value, alpha, hex). Returns the color. */
	public int colorField(String id, int x, int y, int w, int h, int color, boolean withAlpha) {
		Integer picked = (Integer) state.remove(id + "#picked");
		if (picked != null) color = picked;
		boolean open = id.equals(openPopup);
		boolean hov = hovered(x, y, w, h);
		round(x, y, w, h, BloomTheme.RADIUS_SMALL, BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_2, hov || open ? 1 : 0));
		outline(x, y, w, h, BloomTheme.RADIUS_SMALL, open ? BloomTheme.ACCENT_DIM : BloomTheme.BORDER);
		int sw = h - 6;
		Draw.checker(g, x + 3, y + 3, sw, sw, 2);
		rect(x + 3, y + 3, sw, sw, color);
		text(hex(color, withAlpha), x + sw + 8, y + (h - 8) / 2, BloomTheme.TEXT_DIM);
		if (clicked(x, y, w, h)) {
			openPopup = open ? null : id;
			open = !open;
			if (open) {
				float[] hsv = toHsv(color);
				state.put(id + "#hsv", new float[] {hsv[0], hsv[1], hsv[2], ((color >>> 24) & 0xFF) / 255f});
				state.put(id + "#hex", new TextInput(hex(color, withAlpha)).maxLength(9));
			}
		}
		if (open) {
			int pw = 150, ph = withAlpha ? 168 : 154;
			int px = Math.min(x, width - pw - 4), py = y + h + 2;
			if (py + ph > height - 4) py = Math.max(4, y - ph - 2);
			int fpx = px, fpy = py;
			nextPopupRect = new int[] {px, py, pw, ph};
			popupAnchor = new int[] {x, y, w, h};
			int base = color;
			defer(() -> colorPopup(id, fpx, fpy, pw, ph, base, withAlpha));
		}
		return color;
	}

	private void colorPopup(String id, int px, int py, int pw, int ph, int color, boolean withAlpha) {
		float[] hsva = (float[]) state.computeIfAbsent(id + "#hsv", k -> {
			float[] hsv = toHsv(color);
			return new float[] {hsv[0], hsv[1], hsv[2], ((color >>> 24) & 0xFF) / 255f};
		});
		TextInput hexInput = (TextInput) state.computeIfAbsent(id + "#hex", k -> new TextInput(hex(color, withAlpha)).maxLength(9));
		Draw.shadow(g, px, py, pw, ph, BloomTheme.RADIUS, 3);
		round(px, py, pw, ph, BloomTheme.RADIUS, BloomTheme.POPUP);
		outline(px, py, pw, ph, BloomTheme.RADIUS, BloomTheme.BORDER_STRONG);
		boolean changed = false;
		// Palette
		int sx = px + 6, sy = py + 6, cell = 13;
		for (int i = 0; i < BloomTheme.PALETTE.length; i++) {
			int cx = sx + (i % 9) * (cell + 3), cy = sy + (i / 9) * (cell + 3);
			int pc = BloomTheme.PALETTE[i];
			round(cx, cy, cell, cell, 2, pc);
			if (hovered(cx, cy, cell, cell)) outline(cx - 1, cy - 1, cell + 2, cell + 2, 3, BloomTheme.TEXT);
			if (clicked(cx, cy, cell, cell)) {
				float[] hsv = toHsv(pc);
				hsva[0] = hsv[0];
				hsva[1] = hsv[1];
				hsva[2] = hsv[2];
				changed = true;
			}
		}
		// Saturation/value square
		int qx = px + 6, qy = py + 40, qw = pw - 12, qh = 56;
		int strips = 46;
		for (int s = 0; s < strips; s++) {
			int x0 = qx + qw * s / strips, x1 = qx + qw * (s + 1) / strips;
			int top = fromHsv(hsva[0], (float) s / (strips - 1), 1f, 1f);
			g.fillGradient(x0, qy, x1, qy + qh, c(top), c(0xFF000000));
		}
		String sqId = id + "#sq";
		if (clicked(qx, qy, qw, qh)) active = sqId;
		if (isActive(sqId) && mouseDown) {
			hsva[1] = Math.max(0, Math.min(1, (mx - qx) / (float) qw));
			hsva[2] = 1 - Math.max(0, Math.min(1, (my - qy) / (float) qh));
			changed = true;
		}
		int mxk = qx + Math.round(hsva[1] * qw), myk = qy + Math.round((1 - hsva[2]) * qh);
		Draw.roundRectOutline(g, mxk - 3, myk - 3, 6, 6, 3, 1, c(0xFFFFFFFF));
		// Hue
		int hy = qy + qh + 6;
		for (int s = 0; s < 48; s++) {
			int x0 = qx + qw * s / 48, x1 = qx + qw * (s + 1) / 48;
			rect(x0, hy, x1 - x0, 6, fromHsv(s / 47f, 1, 1, 1));
		}
		String hueId = id + "#hue";
		if (clicked(qx, hy - 2, qw, 10)) active = hueId;
		if (isActive(hueId) && mouseDown) {
			hsva[0] = Math.max(0, Math.min(0.9999f, (mx - qx) / (float) qw));
			changed = true;
		}
		rect(qx + Math.round(hsva[0] * qw) - 1, hy - 1, 2, 8, 0xFFFFFFFF);
		int ay = hy + 10;
		if (withAlpha) {
			Draw.checker(g, qx, ay, qw, 6, 3);
			int opaque = fromHsv(hsva[0], hsva[1], hsva[2], 1);
			Draw.hGradient(g, qx, ay, qw, 6, c(opaque & 0x00FFFFFF), c(opaque), 32);
			String aId = id + "#alpha";
			if (clicked(qx, ay - 2, qw, 10)) active = aId;
			if (isActive(aId) && mouseDown) {
				hsva[3] = Math.max(0, Math.min(1, (mx - qx) / (float) qw));
				changed = true;
			}
			rect(qx + Math.round(hsva[3] * qw) - 1, ay - 1, 2, 8, 0xFFFFFFFF);
			ay += 10;
		}
		// Hex
		int result = fromHsv(hsva[0], hsva[1], hsva[2], withAlpha ? hsva[3] : 1);
		if (changed) hexInput.set(hex(result, withAlpha));
		textField(id + "#hexf", qx, ay + 2, qw, 14, hexInput, "#RRGGBB");
		if (hexInput.consumeChanged() && isFocused(hexInput)) {
			Integer parsed = parseHex(hexInput.value(), withAlpha);
			if (parsed != null) {
				float[] hsv = toHsv(parsed);
				hsva[0] = hsv[0];
				hsva[1] = hsv[1];
				hsva[2] = hsv[2];
				if (withAlpha) hsva[3] = ((parsed >>> 24) & 0xFF) / 255f;
				result = parsed;
				changed = true;
			}
		}
		if (changed) state.put(id + "#picked", result);
	}

	private void drawTooltip() {
		String text = tooltip;
		if (text == null) return;
		g.nextStratum();
		List<String> lines = wrap(text, 180);
		int w = 0;
		for (String l : lines) w = Math.max(w, width(l));
		int h = lines.size() * 10 + 6;
		int x = tooltipX + 10, y = tooltipY + 4;
		if (x + w + 10 > width) x = tooltipX - w - 14;
		if (y + h > height) y = height - h - 2;
		round(x, y, w + 10, h, BloomTheme.RADIUS_SMALL, BloomTheme.POPUP);
		outline(x, y, w + 10, h, BloomTheme.RADIUS_SMALL, BloomTheme.BORDER_STRONG);
		for (int i = 0; i < lines.size(); i++) text(lines.get(i), x + 5, y + 4 + i * 10, BloomTheme.TEXT);
	}

	// ---------------------------------------------------------------- color helpers

	public static String hex(int color, boolean withAlpha) {
		return withAlpha ? String.format("#%08X", color) : String.format("#%06X", color & 0xFFFFFF);
	}

	public static @Nullable Integer parseHex(String s, boolean withAlpha) {
		s = s.trim();
		if (s.startsWith("#")) s = s.substring(1);
		try {
			if (s.length() == 6) return 0xFF000000 | Integer.parseUnsignedInt(s, 16);
			if (s.length() == 8 && withAlpha) return (int) Long.parseLong(s, 16);
		} catch (NumberFormatException ignored) {
			// Not a color yet.
		}
		return null;
	}

	public static float[] toHsv(int color) {
		float r = ((color >> 16) & 0xFF) / 255f, gr = ((color >> 8) & 0xFF) / 255f, b = (color & 0xFF) / 255f;
		float max = Math.max(r, Math.max(gr, b)), min = Math.min(r, Math.min(gr, b)), d = max - min;
		float h = 0;
		if (d > 0) {
			if (max == r) h = ((gr - b) / d) % 6;
			else if (max == gr) h = (b - r) / d + 2;
			else h = (r - gr) / d + 4;
			h /= 6;
			if (h < 0) h += 1;
		}
		return new float[] {h, max == 0 ? 0 : d / max, max};
	}

	public static int fromHsv(float h, float s, float v, float a) {
		float c = v * s, x = c * (1 - Math.abs((h * 6) % 2 - 1)), m = v - c;
		float r, g, b;
		int sector = (int) (h * 6) % 6;
		switch (sector) {
			case 0 -> { r = c; g = x; b = 0; }
			case 1 -> { r = x; g = c; b = 0; }
			case 2 -> { r = 0; g = c; b = x; }
			case 3 -> { r = 0; g = x; b = c; }
			case 4 -> { r = x; g = 0; b = c; }
			default -> { r = c; g = 0; b = x; }
		}
		return (Math.round(a * 255) << 24) | (Math.round((r + m) * 255) << 16) | (Math.round((g + m) * 255) << 8) | Math.round((b + m) * 255);
	}
}
