package com.bloomclient.client.ui.screen;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.BloomSettings;
import com.bloomclient.client.api.hud.HudAnchor;
import com.bloomclient.client.api.hud.HudElement;
import com.bloomclient.client.api.hud.HudStyle;
import com.bloomclient.client.api.hud.WidgetOption;
import com.bloomclient.client.core.ModContainer;
import com.bloomclient.client.hud.HudManager;
import com.bloomclient.client.hud.HudRenderer;
import com.bloomclient.client.ui.BloomScreen;
import com.bloomclient.client.ui.BloomTheme;
import com.bloomclient.client.ui.Draw;
import com.bloomclient.client.ui.Icon;
import com.bloomclient.client.ui.TextInput;
import com.bloomclient.client.ui.Tr;
import com.bloomclient.client.ui.Ui;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * HUD Studio: the HUD editor. The whole screen is the real HUD, updated live
 * with real values. Around it:
 * <ul>
 * <li>a toolbar, docked at the top or, when HUD elements sit there, above the hotbar;</li>
 * <li>two drawers opened from the toolbar: the widget library ("Add widget") and the layers;</li>
 * <li>the inspector of the selected element.</li>
 * </ul>
 * Drawers and inspector dock on the side of the screen away from the selected
 * element (or, with nothing selected, on the side with fewer elements), so
 * the panels never cover what is being edited.
 *
 * <p>Drag to move (snapping to the grid, the screen center and other
 * elements; hold Alt to move freely), drag the corner handle to resize,
 * arrows to nudge (Shift: 10 px), Delete, Ctrl+D duplicate, Ctrl+Z/Ctrl+Y
 * undo/redo, H isolates the selection, Tab hides the panels, the "?" button
 * lists every shortcut.
 */
public final class HudStudioScreen extends BloomScreen {
	private static final int SNAP_DIST = 3;
	private static final int DRAWER_W = 164, INSPECTOR_W = 180, BAR_H = 26, MARGIN = 8, GAP = 6, SEP = 9;
	/** Vanilla hotbar height in GUI pixels: the toolbar docks above it, never on it. */
	private static final int HOTBAR_H = 24;
	private static @Nullable HudStyle copiedStyle;

	private enum Drawer {
		NONE, LIBRARY, LAYERS
	}

	private final HudManager hud = BloomClient.hud();
	private @Nullable String selectedId;
	private @Nullable String hoveredId;
	private boolean panels = true;
	private boolean isolate;
	private Drawer drawer = Drawer.NONE;
	private boolean importOpen, helpOpen;
	private final TextInput widgetSearch = new TextInput().maxLength(32);

	// Docking (recomputed only while nothing is being dragged)
	private boolean barBottom, panelsRight;

	// Drag state (GUI coordinates)
	private boolean dragging, resizing;
	private int grabDx, grabDy;
	private int dragX, dragY;
	private float resizeStartScale;
	private int resizeStartW, resizeStartMouseX;
	private int guideX = Integer.MIN_VALUE, guideY = Integer.MIN_VALUE;

	// Undo
	private final Deque<String> undo = new ArrayDeque<>();
	private final Deque<String> redo = new ArrayDeque<>();
	private long lastSnapshot;

	// Inspector inputs
	private final TextInput xInput = new TextInput().maxLength(6).filter(s -> s.matches("-?\\d*"));
	private final TextInput yInput = new TextInput().maxLength(6).filter(s -> s.matches("-?\\d*"));

	public HudStudioScreen(@Nullable Screen parent) {
		super(Component.translatable("bloom.hud.studio"), parent);
	}

	/** Development preview: selects the first element. */
	public HudStudioScreen selectFirst() {
		if (!hud.elements().isEmpty()) select(hud.elements().getFirst());
		return this;
	}

	/** Opens with the widget library visible, optionally pre-filtered (e.g. by a mod's name). */
	public HudStudioScreen openLibrary(@Nullable String query) {
		drawer = Drawer.LIBRARY;
		widgetSearch.set(query == null ? "" : query);
		return this;
	}

	@Override
	protected void init() {
		super.init();
		hud.setEditing(true);
	}

	@Override
	protected void onClosed() {
		hud.setEditing(false);
		hud.saveIfDirty();
	}

	@Override
	public void removed() {
		super.removed();
		// Also when replaced by another screen without onClose (e.g. disconnect).
		hud.setEditing(false);
		hud.saveIfDirty();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		g.fill(0, 0, width, height, BloomTheme.withAlpha(0x60000000, openProgress()));
	}

	// ---------------------------------------------------------------- model helpers

	private @Nullable HudElement selected() {
		if (selectedId == null) return null;
		for (HudElement e : hud.elements()) if (e.id.equals(selectedId)) return e;
		selectedId = null;
		return null;
	}

	private void select(@Nullable HudElement e) {
		selectedId = e == null ? null : e.id;
		if (e == null) isolate = false;
		if (e != null) {
			xInput.set(Integer.toString(e.x));
			yInput.set(Integer.toString(e.y));
		}
	}

	private String displayName(HudElement e) {
		HudManager.Registered r = hud.widget(e.widget);
		return r == null ? Tr.t("bloom.hud.missing_widget", e.widget) : r.widget().displayName();
	}

	/** Records an undo point (throttled for continuous edits) and marks the layout dirty. */
	private void edit() {
		long now = System.currentTimeMillis();
		if (now - lastSnapshot > 450) {
			pushUndo();
		}
		lastSnapshot = now;
		hud.markDirty();
	}

	private void editNow() {
		pushUndo();
		lastSnapshot = System.currentTimeMillis();
		hud.markDirty();
	}

	private void pushUndo() {
		String state = hud.exportString();
		if (undo.isEmpty() || !undo.peek().equals(state)) {
			undo.push(state);
			while (undo.size() > 60) undo.removeLast();
			redo.clear();
		}
	}

	private void restore(Deque<String> from, Deque<String> to) {
		if (from.isEmpty()) return;
		to.push(hud.exportString());
		String state = from.pop();
		hud.importFrom(state);
		lastSnapshot = 0;
		HudElement sel = selected();
		if (sel != null) select(sel);
	}

	private void restyle(HudElement e) {
		e.layoutDirty = true;
		e.lines.markChanged();
	}

	private int[] size(HudElement e) {
		if (e.width > 0) return new int[] {e.width, e.height};
		HudManager.Registered r = hud.widget(e.widget);
		String name = r == null ? e.widget : r.widget().displayName();
		return new int[] {font.width(name) + 10, 17};
	}

	private int elX(HudElement e, int w) {
		if (dragging && e.id.equals(selectedId)) return dragX;
		int x = e.anchor.screenX(width) + e.x - e.anchor.elementX(w);
		return Math.max(0, Math.min(width - w, x));
	}

	private int elY(HudElement e, int h) {
		if (dragging && e.id.equals(selectedId)) return dragY;
		int y = e.anchor.screenY(height) + e.y - e.anchor.elementY(h);
		return Math.max(0, Math.min(height - h, y));
	}

	/** The element's rectangle in canvas units (the panels' coordinate space). */
	private int[] canvasRect(HudElement e) {
		int[] s = size(e);
		int x = elX(e, s[0]), y = elY(e, s[1]);
		int x0 = (int) Math.floor(x / k), y0 = (int) Math.floor(y / k);
		int x1 = (int) Math.ceil((x + s[0]) / k), y1 = (int) Math.ceil((y + s[1]) / k);
		return new int[] {x0, y0, x1 - x0, y1 - y0};
	}

	private static int overlap(int[] a, int[] b) {
		int w = Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]);
		int h = Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]);
		return w > 0 && h > 0 ? w * h : 0;
	}

	/** Area of HUD elements a panel at this rectangle would cover. */
	private int coveredArea(int[] r) {
		int sum = 0;
		for (HudElement e : hud.elements()) sum += overlap(canvasRect(e), r);
		return sum;
	}

	/** Re-anchors an element at an absolute top-left position. */
	private void place(HudElement e, int left, int top, @Nullable HudAnchor forced) {
		int[] s = size(e);
		left = Math.max(0, Math.min(width - s[0], left));
		top = Math.max(0, Math.min(height - s[1], top));
		HudAnchor a = forced != null ? forced : HudAnchor.nearest(left + s[0] / 2, top + s[1] / 2, width, height);
		e.anchor = a;
		e.x = left + a.elementX(s[0]) - a.screenX(width);
		e.y = top + a.elementY(s[1]) - a.screenY(height);
		xInput.set(Integer.toString(e.x));
		yInput.set(Integer.toString(e.y));
	}

	private HudElement add(String widgetId) {
		editNow();
		HudElement e = hud.create(widgetId);
		hud.editableElements().add(e);
		hud.update(BloomClient.game(), true);
		if (e.anchor == HudAnchor.TOP_LEFT && e.x == 0 && e.y == 0) {
			int[] s = size(e);
			place(e, (width - s[0]) / 2, (height - s[1]) / 2, null);
		}
		select(e);
		toast(Tr.t("bloom.studio.added", displayName(e)));
		return e;
	}

	private void duplicate(HudElement e) {
		editNow();
		HudElement copy = e.duplicate();
		copy.initTransient();
		int idx = hud.editableElements().indexOf(e);
		hud.editableElements().add(idx + 1, copy);
		hud.update(BloomClient.game(), true);
		int[] s = size(e);
		place(copy, elX(e, s[0]) + 8, elY(e, s[1]) + 8, null);
		select(copy);
	}

	private void delete(HudElement e) {
		editNow();
		hud.editableElements().remove(e);
		select(null);
	}

	private void move(HudElement e, int delta) {
		List<HudElement> list = hud.editableElements();
		int i = list.indexOf(e), j = Math.max(0, Math.min(list.size() - 1, i + delta));
		if (i < 0 || i == j) return;
		editNow();
		list.remove(i);
		list.add(j, e);
	}

	private int placedCount(String widgetId) {
		int n = 0;
		for (HudElement e : hud.elements()) if (e.widget.equals(widgetId)) n++;
		return n;
	}

	// ---------------------------------------------------------------- HUD preview (GUI coordinates)

	@Override
	protected void beforeCanvas(GuiGraphicsExtractor g, int mouseX, int mouseY, float p) {
		hud.update(BloomClient.game(), true);
		BloomSettings s = BloomClient.settings();
		if (s.snap.on() && (dragging || resizing)) {
			int step = s.gridSize.get() * 4;
			for (int x = 0; x < width; x += step) g.fill(x, 0, x + 1, height, 0x14FFFFFF);
			for (int y = 0; y < height; y += step) g.fill(0, y, width, y + 1, 0x14FFFFFF);
		}
		if (guideX != Integer.MIN_VALUE) g.fill(guideX, 0, guideX + 1, height, 0xC05CE1A8);
		if (guideY != Integer.MIN_VALUE) g.fill(0, guideY, width, guideY + 1, 0xC05CE1A8);
		HudElement sel = selected();
		for (HudElement e : hud.elements()) {
			int[] sz = size(e);
			int x = elX(e, sz[0]), y = elY(e, sz[1]);
			boolean isSel = e == sel;
			if (isolate && !isSel) {
				Draw.roundRectOutline(g, x, y, sz[0], sz[1], 2, 1, 0x18FFFFFF);
				continue;
			}
			HudManager.Registered r = hud.widget(e.widget);
			boolean usable = r != null && r.enabled();
			float op = (e.visible && usable ? 1f : 0.35f) * p;
			if (e.width > 0) {
				HudRenderer.draw(g, e, x, y, op);
			} else {
				Draw.roundRect(g, x, y, sz[0], sz[1], 3, BloomTheme.withAlpha(0x900B0D0C, op));
				String name = r == null ? Tr.t("bloom.hud.missing_widget", e.widget) : r.widget().displayName();
				g.text(font, name, x + 5, y + 5, BloomTheme.withAlpha(BloomTheme.TEXT_MUTED, op), false);
			}
			if (!e.visible) {
				Icon.EYE_OFF.draw(g, x + sz[0] - 10, y + 1, 1, 0xC0FFFFFF);
			}
			if (isSel) {
				Draw.roundRectOutline(g, x - 1, y - 1, sz[0] + 2, sz[1] + 2, 3, 1, 0xFF5CE1A8);
				g.fill(x + sz[0] - 3, y + sz[1] - 3, x + sz[0] + 2, y + sz[1] + 2, 0xFF5CE1A8);
				// Anchor point and link to it
				int ax = e.anchor.screenX(width), ay = e.anchor.screenY(height);
				ax = Math.max(1, Math.min(width - 2, ax));
				ay = Math.max(1, Math.min(height - 2, ay));
				g.fill(ax - 2, ay - 2, ax + 2, ay + 2, 0xFF5CE1A8);
				int ex = x + e.anchor.elementX(sz[0]), ey = y + e.anchor.elementY(sz[1]);
				g.fill(Math.min(ax, ex), ay, Math.max(ax, ex) + 1, ay + 1, 0x605CE1A8);
				g.fill(ex, Math.min(ay, ey), ex + 1, Math.max(ay, ey) + 1, 0x605CE1A8);
			} else if (e.id.equals(hoveredId)) {
				Draw.roundRectOutline(g, x - 1, y - 1, sz[0] + 2, sz[1] + 2, 3, 1, 0x80FFFFFF);
			} else if (!dragging && !resizing) {
				// Every element shows a faint frame, so it is clear what can be grabbed.
				Draw.roundRectOutline(g, x - 1, y - 1, sz[0] + 2, sz[1] + 2, 3, 1, BloomTheme.withAlpha(0x30FFFFFF, p));
			}
		}
	}

	// ---------------------------------------------------------------- canvas: labels, panels, then element interaction

	@Override
	protected void renderCanvas(Ui ui, float p) {
		ui.g.nextStratum();
		elementLabels(ui);
		ui.g.nextStratum();
		List<int[]> panelRects = new ArrayList<>();
		boolean busy = dragging || resizing;
		int barW = barWidth(ui, compactBar(ui));
		if (!busy) dock(barW);
		int barY = barBottom ? barBottomY() : MARGIN;
		int[] bar = topBar(ui, (cw - barW) / 2, barY, barW);
		panelRects.add(bar);
		if (panels && !busy) {
			int areaTop = barBottom ? MARGIN : barY + BAR_H + GAP;
			int areaBottom = barBottom ? barY - GAP : ch - MARGIN;
			int areaH = areaBottom - areaTop;
			HudElement sel = selected();
			int edge = panelsRight ? cw - MARGIN : MARGIN;
			if (sel != null) {
				int ix = panelsRight ? edge - INSPECTOR_W : edge;
				panelRects.add(inspector(ui, sel, ix, areaTop, INSPECTOR_W, areaH));
				edge = panelsRight ? ix - GAP : ix + INSPECTOR_W + GAP;
			}
			if (drawer != Drawer.NONE) {
				int dx = panelsRight ? edge - DRAWER_W : edge;
				panelRects.add(drawer == Drawer.LIBRARY ? library(ui, dx, areaTop, DRAWER_W, areaH) : layers(ui, dx, areaTop, DRAWER_W, areaH));
			}
			if (sel == null && drawer == Drawer.NONE && !importOpen && !helpOpen && BloomClient.settings().tips.on()) {
				int[] tip = hintCard(ui, barY);
				if (tip != null) panelRects.add(tip);
			}
		}
		if (importOpen) panelRects.add(importPopup(ui, bar));
		if (helpOpen) panelRects.add(helpPopup(ui, bar));
		interact(ui, panelRects);
	}

	/** Toolbar Y when docked low: just above the vanilla hotbar. */
	private int barBottomY() {
		return ch - Math.round(HOTBAR_H / k) - BAR_H - GAP;
	}

	/**
	 * Picks where the toolbar and the side panels go. The toolbar moves above
	 * the hotbar when elements sit under it at the top; side panels go away
	 * from the selected element (with some hysteresis around the center), or
	 * with nothing selected to the side where they cover fewer elements.
	 */
	private void dock(int barW) {
		int bx = (cw - barW) / 2;
		int topCover = coveredArea(new int[] {bx, MARGIN, barW, BAR_H});
		int bottomCover = coveredArea(new int[] {bx, barBottomY(), barW, BAR_H});
		barBottom = topCover > 0 && bottomCover < topCover;
		HudElement sel = selected();
		if (sel != null) {
			int[] r = canvasRect(sel);
			int c = r[0] + r[2] / 2;
			if (c < cw / 2 - 16) panelsRight = true;
			else if (c > cw / 2 + 16) panelsRight = false;
		} else if (drawer != Drawer.NONE) {
			int top = barBottom ? MARGIN : MARGIN + BAR_H + GAP, h = ch - top - MARGIN;
			int left = coveredArea(new int[] {MARGIN, top, DRAWER_W, h});
			int right = coveredArea(new int[] {cw - MARGIN - DRAWER_W, top, DRAWER_W, h});
			if (left > right) panelsRight = true;
			else if (right > left) panelsRight = false;
		}
	}

	/** Name tags over the hovered and the selected element, so it is clear what is being edited. */
	private void elementLabels(Ui ui) {
		HudElement sel = selected();
		for (HudElement e : hud.elements()) {
			boolean isSel = e == sel;
			if (!isSel && !e.id.equals(hoveredId)) continue;
			int[] r = canvasRect(e);
			String name = displayName(e);
			int w = ui.width(name) + (isSel ? 19 : 10), h = 11;
			int x = Math.max(2, Math.min(cw - w - 2, r[0] - 1));
			int y = r[1] - h - 3;
			if (y < 2) y = r[1] + r[3] + 3;
			ui.round(x, y, w, h, 2, isSel ? BloomTheme.ACCENT : 0xE6101312);
			if (isSel) {
				ui.icon(Icon.MOVE, x + 4, y + 1, BloomTheme.ACCENT_TEXT);
				ui.text(name, x + 15, y + 2, BloomTheme.ACCENT_TEXT);
			} else {
				ui.text(name, x + 5, y + 2, BloomTheme.TEXT);
			}
		}
	}

	private boolean insideAny(List<int[]> rects, int x, int y) {
		for (int[] r : rects) if (x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3]) return true;
		return false;
	}

	private void interact(Ui ui, List<int[]> panelRects) {
		int gx = Math.round(ui.mx * k), gy = Math.round(ui.my * k);
		boolean overPanel = insideAny(panelRects, ui.mx, ui.my) || ui.popupOpen();
		hoveredId = null;
		HudElement sel = selected();
		// Continue drag / resize
		if (dragging || resizing) {
			if (!ui.isMouseDown() || sel == null) {
				if (dragging && sel != null) place(sel, dragX, dragY, null);
				dragging = resizing = false;
				guideX = guideY = Integer.MIN_VALUE;
			} else if (dragging) {
				dragTo(sel, gx - grabDx, gy - grabDy);
			} else {
				float scale = resizeStartScale * (resizeStartW + (gx - resizeStartMouseX)) / (float) Math.max(1, resizeStartW);
				scale = Math.round(Math.max(0.5f, Math.min(3f, scale)) * 20) / 20f;
				if (scale != sel.style.textScale) {
					sel.style.textScale = scale;
					restyle(sel);
				}
			}
			return;
		}
		if (overPanel) return;
		// Resize handle hint
		if (sel != null) {
			int[] s = size(sel);
			int x = elX(sel, s[0]), y = elY(sel, s[1]);
			if (gx >= x + s[0] - 4 && gx <= x + s[0] + 3 && gy >= y + s[1] - 4 && gy <= y + s[1] + 3) ui.tooltip(Tr.t("bloom.studio.resize_hint"));
		}
		// Hover (topmost first)
		List<HudElement> list = hud.elements();
		HudElement hit = null;
		for (int i = list.size() - 1; i >= 0; i--) {
			HudElement e = list.get(i);
			if (isolate && e != sel) continue;
			int[] s = size(e);
			int x = elX(e, s[0]), y = elY(e, s[1]);
			if (gx >= x - 1 && gy >= y - 1 && gx <= x + s[0] + 1 && gy <= y + s[1] + 1) {
				hit = e;
				break;
			}
		}
		if (hit != null) hoveredId = hit.id;
		if (!ui.pendingClick()) return;
		int cx = Math.round(ui.clickX() * k), cy = Math.round(ui.clickY() * k);
		if (insideAny(panelRects, ui.clickX(), ui.clickY())) return;
		ui.consumeClick();
		if (importOpen || helpOpen) {
			importOpen = helpOpen = false;
			return;
		}
		// Resize handle of the selection
		if (sel != null) {
			int[] s = size(sel);
			int x = elX(sel, s[0]), y = elY(sel, s[1]);
			if (cx >= x + s[0] - 4 && cx <= x + s[0] + 3 && cy >= y + s[1] - 4 && cy <= y + s[1] + 3) {
				editNow();
				resizing = true;
				resizeStartScale = sel.style.textScale;
				resizeStartW = Math.max(8, s[0]);
				resizeStartMouseX = cx;
				return;
			}
		}
		if (hit == null) {
			select(null);
			return;
		}
		// Working on the canvas: the "Add widget" picker has done its job.
		if (drawer == Drawer.LIBRARY) drawer = Drawer.NONE;
		select(hit);
		editNow();
		int[] s = size(hit);
		int x = elX(hit, s[0]), y = elY(hit, s[1]);
		grabDx = cx - x;
		grabDy = cy - y;
		dragX = x;
		dragY = y;
		dragging = true;
	}

	private void dragTo(HudElement e, int left, int top) {
		int[] s = size(e);
		int w = s[0], h = s[1];
		BloomSettings st = BloomClient.settings();
		boolean free = InputConstants.isKeyDown(InputConstants.KEY_LALT);
		guideX = guideY = Integer.MIN_VALUE;
		if (!free && st.snap.on()) {
			int grid = st.gridSize.get();
			left = Math.round(left / (float) grid) * grid;
			top = Math.round(top / (float) grid) * grid;
		}
		if (!free && st.guides.on()) {
			// Candidates: screen center, screen edges with margin, other elements' edges and centers.
			List<int[]> xs = new ArrayList<>(), ys = new ArrayList<>();
			xs.add(new int[] {width / 2, 1});
			ys.add(new int[] {height / 2, 1});
			for (HudElement o : hud.elements()) {
				if (o == e || (isolate)) continue;
				int[] os = size(o);
				int ox = elX(o, os[0]), oy = elY(o, os[1]);
				xs.add(new int[] {ox, 0});
				xs.add(new int[] {ox + os[0], 0});
				xs.add(new int[] {ox + os[0] / 2, 1});
				ys.add(new int[] {oy, 0});
				ys.add(new int[] {oy + os[1], 0});
				ys.add(new int[] {oy + os[1] / 2, 1});
			}
			int bestDx = SNAP_DIST + 1;
			for (int[] c : xs) {
				int[] mine = c[1] == 1 ? new int[] {left + w / 2} : new int[] {left, left + w};
				for (int m : mine) {
					int d = c[0] - m;
					if (Math.abs(d) < Math.abs(bestDx)) {
						bestDx = d;
						guideX = c[0];
					}
				}
			}
			if (Math.abs(bestDx) <= SNAP_DIST) left += bestDx;
			else guideX = Integer.MIN_VALUE;
			int bestDy = SNAP_DIST + 1;
			for (int[] c : ys) {
				int[] mine = c[1] == 1 ? new int[] {top + h / 2} : new int[] {top, top + h};
				for (int m : mine) {
					int d = c[0] - m;
					if (Math.abs(d) < Math.abs(bestDy)) {
						bestDy = d;
						guideY = c[0];
					}
				}
			}
			if (Math.abs(bestDy) <= SNAP_DIST) top += bestDy;
			else guideY = Integer.MIN_VALUE;
		}
		dragX = Math.max(0, Math.min(width - w, left));
		dragY = Math.max(0, Math.min(height - h, top));
		hud.markDirty();
	}

	// ---------------------------------------------------------------- toolbar

	private static final int ICON_GROUPS_W = (20 + 2 + 20) + SEP + (20 + 2 + 20) + SEP + (20 + 2 + 20 + 2 + 20) + SEP + 20;

	private boolean compactBar(Ui ui) {
		return barWidth(ui, false) > cw - 2 * MARGIN;
	}

	private int barWidth(Ui ui, boolean compact) {
		int w = 6;
		if (!compact) w += Icon.SIZE + 5 + ui.width(Tr.t("bloom.hud.studio")) + SEP;
		w += compact ? 20 : ui.toolButtonWidth(Tr.t("bloom.studio.add_widget"), Icon.PLUS);
		w += 4;
		w += compact ? 20 : ui.toolButtonWidth(Tr.t("bloom.studio.layers"), Icon.LAYERS);
		w += SEP + ICON_GROUPS_W + 6;
		w += ui.width(Tr.t("bloom.common.done")) + 20 + 4;
		return w;
	}

	private int[] topBar(Ui ui, int x, int y, int w) {
		boolean compact = compactBar(ui);
		Draw.shadow(ui.g, x, y, w, BAR_H, 6, 4);
		ui.round(x, y, w, BAR_H, 6, BloomTheme.STUDIO);
		ui.outline(x, y, w, BAR_H, 6, BloomTheme.BORDER);
		int bx = x + 6, by = y + 3, dh = BAR_H - 12;
		if (!compact) {
			String title = Tr.t("bloom.hud.studio");
			Icon.HUD.draw(ui.g, bx, y + 9, 1, ui.c(BloomTheme.ACCENT));
			ui.text(title, bx + Icon.SIZE + 5, y + 9, BloomTheme.TEXT);
			bx += Icon.SIZE + 5 + ui.width(title);
			ui.divider(bx + 4, y + 6, dh);
			bx += SEP;
		}
		// Panels
		String addL = Tr.t("bloom.studio.add_widget"), layL = Tr.t("bloom.studio.layers");
		boolean libOn = drawer == Drawer.LIBRARY, layOn = drawer == Drawer.LAYERS;
		boolean addClick = compact ? ui.iconButton("st.add", bx, by, 20, Icon.PLUS, addL, libOn, true)
				: ui.toolButton("st.add", bx, by, 20, addL, Icon.PLUS, libOn, true, Tr.t("bloom.studio.add_widget.tip"));
		if (addClick) toggleDrawer(Drawer.LIBRARY);
		bx += (compact ? 20 : ui.toolButtonWidth(addL, Icon.PLUS)) + 4;
		boolean layClick = compact ? ui.iconButton("st.layers", bx, by, 20, Icon.LAYERS, layL, layOn, true)
				: ui.toolButton("st.layers", bx, by, 20, layL, Icon.LAYERS, layOn, true, Tr.t("bloom.studio.layers.tip"));
		if (layClick) toggleDrawer(Drawer.LAYERS);
		bx += compact ? 20 : ui.toolButtonWidth(layL, Icon.LAYERS);
		ui.divider(bx + 4, y + 6, dh);
		bx += SEP;
		// History
		if (ui.iconButton("st.undo", bx, by, 20, Icon.UNDO, Tr.t("bloom.studio.undo"), false, !undo.isEmpty())) restore(undo, redo);
		bx += 22;
		if (ui.iconButton("st.redo", bx, by, 20, Icon.REDO, Tr.t("bloom.studio.redo"), false, !redo.isEmpty())) restore(redo, undo);
		bx += 20;
		ui.divider(bx + 4, y + 6, dh);
		bx += SEP;
		// Snapping
		BloomSettings s = BloomClient.settings();
		if (ui.iconButton("st.snap", bx, by, 20, Icon.GRID, Tr.t(s.snap.on() ? "bloom.studio.snap.on" : "bloom.studio.snap.off"), s.snap.on(), true)) {
			s.snap.toggle();
		}
		bx += 22;
		if (ui.iconButton("st.guides", bx, by, 20, Icon.MAGNET, Tr.t(s.guides.on() ? "bloom.studio.guides.on" : "bloom.studio.guides.off"), s.guides.on(),
				true)) {
			s.guides.toggle();
		}
		bx += 20;
		ui.divider(bx + 4, y + 6, dh);
		bx += SEP;
		// Layout files
		if (ui.iconButton("st.import", bx, by, 20, Icon.IMPORT, Tr.t("bloom.studio.import"), importOpen, true)) {
			importOpen = !importOpen;
			helpOpen = false;
		}
		bx += 22;
		if (ui.iconButton("st.export", bx, by, 20, Icon.EXPORT, Tr.t("bloom.studio.export"))) exportLayout();
		bx += 22;
		if (ui.iconButton("st.reset", bx, by, 20, Icon.TRASH, Tr.t("bloom.studio.reset_layout"))) {
			confirm(Tr.t("bloom.settings.reset_hud_confirm"), () -> {
				editNow();
				hud.resetToDefault();
				select(null);
			});
		}
		bx += 20;
		ui.divider(bx + 4, y + 6, dh);
		bx += SEP;
		if (ui.iconButton("st.help", bx, by, 20, Icon.HELP, Tr.t("bloom.studio.help"), helpOpen, true)) {
			helpOpen = !helpOpen;
			importOpen = false;
		}
		bx += 20 + 6;
		String done = Tr.t("bloom.common.done");
		if (ui.button("st.done", bx, by, ui.width(done) + 20, 20, done, Ui.Kind.PRIMARY)) onClose();
		return new int[] {x, y, w, BAR_H};
	}

	private void toggleDrawer(Drawer d) {
		drawer = drawer == d ? Drawer.NONE : d;
		importOpen = helpOpen = false;
		if (drawer == Drawer.LIBRARY) ui.resetScroll("st.library");
	}

	/** Y of a popover attached to the toolbar: under it, or above it when the toolbar is docked low. */
	private int popoverY(int[] bar, int h) {
		return barBottom ? bar[1] - GAP - h : bar[1] + bar[3] + GAP;
	}

	private void exportLayout() {
		try {
			Path file = hud.exportToFile();
			ui.mc.keyboardHandler.setClipboard(hud.exportString());
			toast(Tr.t("bloom.studio.exported", file.getFileName().toString()));
		} catch (IOException e) {
			toast(Tr.t("bloom.studio.export_failed"), BloomTheme.DANGER);
		}
	}

	private int[] importPopup(Ui ui, int[] bar) {
		int w = 220;
		List<Path> files = hud.listExports();
		int shown = Math.min(6, files.size());
		int h = 54 + Math.max(1, shown) * 16;
		int x = (cw - w) / 2, y = popoverY(bar, h);
		Draw.shadow(ui.g, x, y, w, h, 6, 4);
		ui.round(x, y, w, h, 6, BloomTheme.POPUP);
		ui.outline(x, y, w, h, 6, BloomTheme.BORDER_STRONG);
		ui.icon(Icon.IMPORT, x + 10, y + 8, BloomTheme.ACCENT);
		ui.text(Tr.t("bloom.studio.import"), x + 24, y + 9, BloomTheme.TEXT);
		if (ui.button("imp.clip", x + 10, y + 22, w - 20, 18, Tr.t("bloom.studio.import_clipboard"), Icon.COPY, Ui.Kind.SECONDARY, true)) {
			String clip = ui.mc.keyboardHandler.getClipboard();
			editNow();
			applyImport(hud.importFrom(clip == null ? "" : clip));
		}
		int ly = y + 46;
		if (files.isEmpty()) {
			ui.text(Tr.t("bloom.studio.no_exports"), x + 10, ly + 3, BloomTheme.TEXT_MUTED);
		}
		for (int i = 0; i < shown; i++) {
			Path f = files.get(i);
			boolean hov = ui.hovered(x + 6, ly, w - 12, 15);
			if (hov) ui.round(x + 6, ly, w - 12, 15, 3, BloomTheme.SURFACE_HOVER);
			ui.icon(Icon.FOLDER, x + 10, ly + 3, BloomTheme.TEXT_MUTED);
			ui.text(ui.ellipsize(f.getFileName().toString(), w - 40), x + 24, ly + 4, BloomTheme.TEXT);
			if (ui.clicked(x + 6, ly, w - 12, 15)) {
				editNow();
				applyImport(hud.importFrom(f));
			}
			ly += 16;
		}
		return new int[] {x, y, w, h};
	}

	private void applyImport(HudManager.ImportResult r) {
		if (r.ok()) {
			importOpen = false;
			select(null);
			toast(Tr.t(r.message(), r.elements()));
		} else {
			undo.poll();
			toast(Tr.t(r.message()), BloomTheme.DANGER);
		}
	}

	/** Every shortcut, with the keys drawn as keycaps. Tokens starting with "bloom." are translated. */
	private static final String[][] HELP = {
			{"bloom.studio.key.click", "", "bloom.studio.help.select"},
			{"bloom.studio.key.drag", "", "bloom.studio.help.move"},
			{"Alt", "bloom.studio.key.drag", "bloom.studio.help.free"},
			{"bloom.studio.key.corner", "", "bloom.studio.help.resize"},
			{"bloom.studio.key.arrows", "", "bloom.studio.help.nudge"},
			{"Shift", "bloom.studio.key.arrows", "bloom.studio.help.nudge10"},
			{"bloom.studio.key.delete", "", "bloom.studio.help.delete"},
			{"Ctrl", "D", "bloom.studio.help.duplicate"},
			{"Ctrl", "Z", "bloom.studio.help.undo"},
			{"Ctrl", "Y", "bloom.studio.help.redo"},
			{"H", "", "bloom.studio.help.isolate"},
			{"Tab", "", "bloom.studio.help.panels"},
			{"Esc", "", "bloom.studio.help.deselect"},
	};

	private static String key(String token) {
		return token.startsWith("bloom.") ? Tr.t(token) : token;
	}

	private int[] helpPopup(Ui ui, int[] bar) {
		int keysW = 0;
		for (String[] row : HELP) {
			int kw = Math.max(12, ui.width(key(row[0])) + 8);
			if (!row[1].isEmpty()) kw += 10 + Math.max(12, ui.width(key(row[1])) + 8);
			keysW = Math.max(keysW, kw);
		}
		int descW = 0;
		for (String[] row : HELP) descW = Math.max(descW, ui.width(Tr.t(row[2])));
		int w = Math.min(cw - 2 * MARGIN, 20 + keysW + 10 + descW), rowH = 15;
		int h = 30 + HELP.length * rowH + 4;
		int x = (cw - w) / 2, y = popoverY(bar, h);
		Draw.shadow(ui.g, x, y, w, h, 6, 4);
		ui.round(x, y, w, h, 6, BloomTheme.POPUP);
		ui.outline(x, y, w, h, 6, BloomTheme.BORDER_STRONG);
		ui.icon(Icon.HELP, x + 10, y + 8, BloomTheme.ACCENT);
		ui.text(Tr.t("bloom.studio.help.title"), x + 24, y + 9, BloomTheme.TEXT);
		if (ui.iconButton("help.close", x + w - 22, y + 4, 16, Icon.CLOSE, Tr.t("bloom.common.close"))) helpOpen = false;
		int ry = y + 26;
		for (String[] row : HELP) {
			int kx = x + 10;
			kx += ui.keycap(key(row[0]), kx, ry);
			if (!row[1].isEmpty()) {
				ui.text("+", kx + 2, ry + 2, BloomTheme.TEXT_MUTED);
				kx += 10;
				ui.keycap(key(row[1]), kx, ry);
			}
			ui.text(ui.ellipsize(Tr.t(row[2]), w - keysW - 30), x + 20 + keysW, ry + 2, BloomTheme.TEXT_DIM);
			ry += rowH;
		}
		return new int[] {x, y, w, h};
	}

	/** Small "how it works" strip shown with nothing selected; returns its rectangle or null if it does not fit. */
	private int @Nullable [] hintCard(Ui ui, int barY) {
		Icon[] icons = {Icon.TARGET, Icon.MOVE, Icon.RESIZE, Icon.PLUS};
		String[] texts = {Tr.t("bloom.studio.hint.select"), Tr.t("bloom.studio.hint.move"), Tr.t("bloom.studio.hint.resize"), Tr.t("bloom.studio.hint.add")};
		int n = icons.length, w;
		while (true) {
			w = 10 + 16;
			for (int i = 0; i < n; i++) w += Icon.SIZE + 4 + ui.width(texts[i]) + 12;
			if (w <= cw - 2 * MARGIN || n == 1) break;
			n--;
		}
		if (w > cw - 2 * MARGIN) return null;
		int h = 20;
		int x = (cw - w) / 2;
		int y = barBottom ? MARGIN : ch - Math.round(HOTBAR_H / k) - h - GAP;
		// Fades while the mouse is near, so it never hides an element for long.
		boolean near = ui.mx >= x - 12 && ui.mx < x + w + 12 && ui.my >= y - 12 && ui.my < y + h + 12;
		float prev = ui.alpha;
		ui.alpha = prev * (0.35f + 0.65f * (1 - ui.anim("st.hint.near", near, 6)));
		ui.round(x, y, w, h, 5, 0xE6101312);
		ui.outline(x, y, w, h, 5, BloomTheme.BORDER);
		int tx = x + 10;
		for (int i = 0; i < n; i++) {
			ui.icon(icons[i], tx, y + 6, BloomTheme.ACCENT);
			tx += Icon.SIZE + 4;
			ui.text(texts[i], tx, y + 6, BloomTheme.TEXT_DIM);
			tx += ui.width(texts[i]) + 12;
		}
		ui.alpha = prev;
		int cx = x + w - 18;
		if (ui.iconButton("st.hint.close", cx, y + 3, 14, Icon.CLOSE, Tr.t("bloom.common.hide_tips"))) BloomClient.settings().tips.toggle();
		// Only the close button takes clicks: the rest of the strip lets them through to the HUD.
		return new int[] {cx, y + 3, 14, 14};
	}

	// ---------------------------------------------------------------- drawers

	private void drawerFrame(Ui ui, int x, int y, int w, int h, Icon icon, String title) {
		Draw.shadow(ui.g, x, y, w, h, 6, 4);
		ui.round(x, y, w, h, 6, BloomTheme.STUDIO);
		ui.outline(x, y, w, h, 6, BloomTheme.BORDER);
		ui.icon(icon, x + 9, y + 9, BloomTheme.ACCENT);
		ui.textBold(ui.ellipsize(title, w - 44), x + 22, y + 10, BloomTheme.TEXT);
		if (ui.iconButton("st.drawer.close", x + w - 22, y + 5, 16, Icon.CLOSE, Tr.t("bloom.common.close"))) drawer = Drawer.NONE;
	}

	/** "Add widget": every registered widget grouped by the mod that provides it, searchable. */
	private int[] library(Ui ui, int x, int y, int w, int h) {
		drawerFrame(ui, x, y, w, h, Icon.PLUS, Tr.t("bloom.studio.add_widget"));
		ui.textField("st.lib.search", x + 6, y + 25, w - 12, 16, widgetSearch, Tr.t("bloom.studio.search_widgets"), Icon.SEARCH);
		String q = widgetSearch.value().trim().toLowerCase(Locale.ROOT);
		Map<String, List<HudManager.Registered>> groups = new LinkedHashMap<>();
		for (HudManager.Registered r : hud.widgets()) {
			if (!q.isEmpty()) {
				String hay = (r.widget().displayName() + " " + r.widget().displayDescription() + " " + r.ownerName()).toLowerCase(Locale.ROOT);
				if (!hay.contains(q)) continue;
			}
			groups.computeIfAbsent(r.ownerName(), n -> new ArrayList<>()).add(r);
		}
		int ly = y + 47, lh = h - 51;
		int off = ui.beginScroll("st.library", x + 4, ly, w - 8, lh);
		int cy = ly - off;
		int rw = w - 16, rx = x + 6;
		if (groups.isEmpty()) {
			ui.textCentered(Tr.t("bloom.studio.no_widgets"), x + w / 2, cy + 12, BloomTheme.TEXT_MUTED);
			cy += 30;
		}
		for (Map.Entry<String, List<HudManager.Registered>> g : groups.entrySet()) {
			HudManager.Registered first = g.getValue().getFirst();
			ModContainer owner = first.owner();
			boolean enabled = first.enabled();
			// Group header: the providing mod, with a shortcut to turn it on.
			ui.icon(owner == null ? Icon.BLOOM : owner.metadata().icon(), rx, cy + 3, BloomTheme.TEXT_MUTED);
			ui.text(ui.ellipsize(g.getKey().toUpperCase(Locale.ROOT), rw - 60), rx + 13, cy + 4, BloomTheme.TEXT_MUTED);
			if (!enabled && owner != null) {
				String on = Tr.t("bloom.mods.enable");
				int bw = ui.width(on) + 12;
				if (ui.button("st.lib.enable." + owner.metadata().id(), rx + rw - bw, cy, bw, 14, on, Ui.Kind.SECONDARY)) {
					if (!BloomClient.mods().setEnabled(owner, true)) toast(Tr.t("bloom.mods.enable_failed", owner.metadata().displayName()), BloomTheme.DANGER);
				}
			}
			cy += 17;
			for (HudManager.Registered r : g.getValue()) {
				String id = "st.w." + r.widget().id();
				int rh = 30;
				boolean hov = ui.hovered(rx, cy, rw, rh);
				float hv = ui.anim(id, hov && enabled, 10);
				ui.round(rx, cy, rw, rh, 4, BloomTheme.lerp(BloomTheme.SURFACE, BloomTheme.SURFACE_HOVER, hv));
				ui.round(rx + 4, cy + 6, 18, 18, 4, enabled ? BloomTheme.ACCENT_SOFT : 0x14FFFFFF);
				ui.icon(r.widget().icon(), rx + 9, cy + 11, enabled ? BloomTheme.ACCENT : BloomTheme.TEXT_MUTED);
				int count = placedCount(r.widget().id());
				int nameW = rw - 46 - (count > 0 ? 20 : 0);
				ui.text(ui.ellipsize(r.widget().displayName(), nameW), rx + 27, cy + 6, enabled ? BloomTheme.TEXT : BloomTheme.TEXT_MUTED);
				ui.text(ui.ellipsize(r.widget().displayDescription(), rw - 46), rx + 27, cy + 17, BloomTheme.TEXT_MUTED);
				if (count > 0) {
					// Already on the HUD: small check with the number of copies.
					String c = "×" + count;
					int cxp = rx + rw - 20 - ui.width(c) - 13;
					ui.icon(Icon.CHECK, cxp, cy + 5, BloomTheme.ACCENT_DIM);
					ui.text(c, cxp + 11, cy + 6, BloomTheme.TEXT_MUTED);
				}
				ui.round(rx + rw - 18, cy + 8, 14, 14, 3, enabled ? BloomTheme.lerp(BloomTheme.SURFACE_2, BloomTheme.ACCENT, hv) : 0x10FFFFFF);
				ui.icon(Icon.PLUS, rx + rw - 16, cy + 10, enabled ? BloomTheme.lerp(BloomTheme.TEXT_DIM, BloomTheme.ACCENT_TEXT, hv) : BloomTheme.TEXT_MUTED);
				if (hov) {
					String tip = r.widget().displayDescription();
					if (!enabled) tip = Tr.t("bloom.studio.enable_mod_first", r.ownerName());
					else if (count > 0) tip += "\n" + Tr.t("bloom.studio.already_placed", count);
					ui.tooltip(tip);
				}
				if (ui.clicked(rx, cy, rw, rh) && enabled) add(r.widget().id());
				cy += rh + 3;
			}
			cy += 6;
		}
		ui.endScroll("st.library", x + 4, ly, w - 8, lh, cy + off - ly);
		return new int[] {x, y, w, h};
	}

	private int[] layers(Ui ui, int x, int y, int w, int h) {
		drawerFrame(ui, x, y, w, h, Icon.LAYERS, Tr.t("bloom.studio.layers"));
		ui.text(ui.ellipsize(Tr.t("bloom.studio.layers.order"), w - 16), x + 9, y + 25, BloomTheme.TEXT_MUTED);
		List<HudElement> list = hud.elements();
		int ly = y + 38, lh = h - 42;
		int off = ui.beginScroll("st.layers", x + 4, ly, w - 8, lh);
		int cy = ly - off;
		HudElement sel = selected();
		HudElement toSelect = null;
		int moveDelta = 0;
		HudElement toMove = null;
		if (list.isEmpty()) {
			ui.paragraph(Tr.t("bloom.studio.layers.empty"), x + 10, cy + 4, w - 20, BloomTheme.TEXT_MUTED);
		}
		for (int i = list.size() - 1; i >= 0; i--) {
			HudElement e = list.get(i);
			HudManager.Registered r = hud.widget(e.widget);
			boolean isSel = e == sel;
			boolean hov = ui.hovered(x + 6, cy, w - 16, 20);
			ui.round(x + 6, cy, w - 16, 20, 4, isSel ? BloomTheme.ACCENT_SOFT : hov ? BloomTheme.SURFACE_2 : BloomTheme.SURFACE);
			ui.icon(r == null ? Icon.WARNING : r.widget().icon(), x + 11, cy + 6, isSel ? BloomTheme.ACCENT : BloomTheme.TEXT_MUTED);
			ui.text(ui.ellipsize(displayName(e), w - 96), x + 24, cy + 6, e.visible ? BloomTheme.TEXT : BloomTheme.TEXT_MUTED);
			if (hov && r != null && !r.enabled()) ui.tooltip(Tr.t("bloom.hud.owner_disabled", r.ownerName()));
			int bx = x + w - 26;
			if (ui.iconButton("st.l.vis." + e.id, bx, cy + 2, 16, e.visible ? Icon.EYE : Icon.EYE_OFF, Tr.t(e.visible ? "bloom.hud.hide" : "bloom.hud.show"))) {
				editNow();
				e.visible = !e.visible;
			}
			bx -= 16;
			if (ui.iconButton("st.l.dn." + e.id, bx, cy + 2, 16, Icon.DOWN, Tr.t("bloom.studio.backward"), false, i > 0)) {
				toMove = e;
				moveDelta = -1;
			}
			bx -= 16;
			if (ui.iconButton("st.l.up." + e.id, bx, cy + 2, 16, Icon.UP, Tr.t("bloom.studio.forward"), false, i < list.size() - 1)) {
				toMove = e;
				moveDelta = 1;
			}
			if (ui.clicked(x + 6, cy, w - 16, 20)) toSelect = e;
			cy += 23;
		}
		ui.endScroll("st.layers", x + 4, ly, w - 8, lh, Math.max(30, list.size() * 23));
		if (toMove != null) move(toMove, moveDelta);
		if (toSelect != null) select(toSelect);
		return new int[] {x, y, w, h};
	}

	// ---------------------------------------------------------------- inspector

	private int[] inspector(Ui ui, HudElement e, int x, int y, int w, int h) {
		Draw.shadow(ui.g, x, y, w, h, 6, 4);
		ui.round(x, y, w, h, 6, BloomTheme.STUDIO);
		ui.outline(x, y, w, h, 6, BloomTheme.BORDER);
		HudManager.Registered r = hud.widget(e.widget);
		HudStyle st = e.style;
		int ix = x + 8, iw = w - 16;
		// Header: what is selected and which mod provides it
		ui.round(ix, y + 7, 22, 22, 5, BloomTheme.ACCENT_SOFT);
		ui.icon(r == null ? Icon.WARNING : r.widget().icon(), ix + 7, y + 14, BloomTheme.ACCENT);
		ui.textBold(ui.ellipsize(displayName(e), iw - 50), ix + 28, y + 9, BloomTheme.TEXT);
		ui.text(ui.ellipsize(r == null ? "" : r.ownerName(), iw - 50), ix + 28, y + 20, BloomTheme.TEXT_MUTED);
		if (ui.iconButton("in.close", x + w - 24, y + 6, 16, Icon.CLOSE, Tr.t("bloom.studio.deselect"))) {
			select(null);
			return new int[] {x, y, w, h};
		}
		// Actions
		int ay = y + 34, bx = ix;
		if (ui.iconButton("in.vis", bx, ay, 18, e.visible ? Icon.EYE : Icon.EYE_OFF, Tr.t(e.visible ? "bloom.hud.hide" : "bloom.hud.show"))) {
			editNow();
			e.visible = !e.visible;
		}
		bx += 20;
		if (ui.iconButton("in.isolate", bx, ay, 18, Icon.FOCUS, Tr.t("bloom.studio.isolate"), isolate, true)) isolate = !isolate;
		bx += 20;
		if (ui.iconButton("in.reset", bx, ay, 18, Icon.RESET, Tr.t("bloom.studio.reset_position"))) {
			editNow();
			HudElement fresh = hud.create(e.widget);
			e.anchor = fresh.anchor;
			e.x = fresh.x;
			e.y = fresh.y;
			if (e.anchor == HudAnchor.TOP_LEFT && e.x == 0 && e.y == 0) {
				int[] s = size(e);
				place(e, (width - s[0]) / 2, (height - s[1]) / 2, null);
			}
			select(e);
		}
		bx += 20;
		if (ui.iconButton("in.dup", bx, ay, 18, Icon.COPY, Tr.t("bloom.studio.duplicate"))) {
			duplicate(e);
			return new int[] {x, y, w, h};
		}
		String del = Tr.t("bloom.studio.delete_short");
		int dw = ui.width(del) + 24;
		if (ui.button("in.del", ix + iw - dw, ay, dw, 18, del, Icon.TRASH, Ui.Kind.DANGER, true)) {
			delete(e);
			return new int[] {x, y, w, h};
		}
		if (ui.hovered(ix + iw - dw, ay, dw, 18)) ui.tooltip(Tr.t("bloom.studio.delete"));
		int top = ay + 24;
		ui.rect(x + 1, top - 2, w - 2, 1, BloomTheme.BORDER);
		int off = ui.beginScroll("st.inspector", x + 2, top, w - 4, y + h - top - 4);
		int cy = top + 4 - off;

		// Position
		cy = section(ui, Icon.MOVE, Tr.t("bloom.studio.position"), ix, cy, iw, false);
		int gs = 10;
		for (HudAnchor a : HudAnchor.values()) {
			int gx = ix + a.hx * (gs + 2), gy = cy + a.vy * (gs + 2);
			boolean selA = e.anchor == a;
			boolean hov = ui.hovered(gx, gy, gs, gs);
			ui.round(gx, gy, gs, gs, 2, selA ? BloomTheme.ACCENT : hov ? BloomTheme.SURFACE_ACTIVE : BloomTheme.SURFACE_2);
			if (hov) ui.tooltip(Tr.t("bloom.studio.anchor." + a.name().toLowerCase(Locale.ROOT)) + "\n" + Tr.t("bloom.studio.anchor.desc"));
			if (ui.clicked(gx, gy, gs, gs) && !selA) {
				editNow();
				int[] s = size(e);
				place(e, elX(e, s[0]), elY(e, s[1]), a);
			}
		}
		ui.text(Tr.t("bloom.studio.anchor"), ix + 42, cy + 2, BloomTheme.TEXT_MUTED);
		ui.text(ui.ellipsize(Tr.t("bloom.studio.anchor." + e.anchor.name().toLowerCase(Locale.ROOT)), iw - 44), ix + 42, cy + 13, BloomTheme.TEXT_DIM);
		if (ui.hovered(ix + 40, cy, iw - 40, 22)) ui.tooltip(Tr.t("bloom.studio.anchor.desc"));
		cy += 3 * (gs + 2) + 6;
		ui.text("X", ix, cy + 4, BloomTheme.TEXT_MUTED);
		ui.textField("in.x", ix + 10, cy, iw / 2 - 16, 16, xInput, "0");
		ui.text("Y", ix + iw / 2, cy + 4, BloomTheme.TEXT_MUTED);
		ui.textField("in.y", ix + iw / 2 + 10, cy, iw / 2 - 10, 16, yInput, "0");
		if (xInput.consumeChanged() && ui.isFocused(xInput)) {
			try {
				int v = Integer.parseInt(xInput.value());
				if (v != e.x) {
					edit();
					e.x = v;
				}
			} catch (NumberFormatException ignored) {
				// Partial input such as "-".
			}
		}
		if (yInput.consumeChanged() && ui.isFocused(yInput)) {
			try {
				int v = Integer.parseInt(yInput.value());
				if (v != e.y) {
					edit();
					e.y = v;
				}
			} catch (NumberFormatException ignored) {
				// Partial input.
			}
		}
		if (!ui.isFocused(xInput) && !xInput.value().equals(Integer.toString(e.x))) xInput.set(Integer.toString(e.x));
		if (!ui.isFocused(yInput) && !yInput.value().equals(Integer.toString(e.y))) yInput.set(Integer.toString(e.y));
		cy += 22;
		// Align to screen
		ui.text(Tr.t("bloom.studio.align_screen"), ix, cy, BloomTheme.TEXT_DIM);
		cy += 11;
		Icon[] aligns = {Icon.ALIGN_LEFT, Icon.ALIGN_CENTER, Icon.ALIGN_RIGHT, Icon.ALIGN_TOP, Icon.ALIGN_MIDDLE, Icon.ALIGN_BOTTOM};
		String[] alignKeys = {"left", "center", "right", "top", "middle", "bottom"};
		for (int i = 0; i < 6; i++) {
			int abx = ix + i * 21 + (i >= 3 ? 6 : 0);
			if (ui.iconButton("in.al." + i, abx, cy, 19, aligns[i], Tr.t("bloom.studio.align_screen." + alignKeys[i]))) {
				editNow();
				int[] s = size(e);
				int left = elX(e, s[0]), topY = elY(e, s[1]);
				int m = 4;
				switch (i) {
					case 0 -> left = m;
					case 1 -> left = (width - s[0]) / 2;
					case 2 -> left = width - s[0] - m;
					case 3 -> topY = m;
					case 4 -> topY = (height - s[1]) / 2;
					default -> topY = height - s[1] - m;
				}
				place(e, left, topY, null);
			}
		}
		cy += 26;

		// Size and text
		cy = section(ui, Icon.TEXT, Tr.t("bloom.studio.text"), ix, cy, iw, true);
		cy = sliderRow(ui, "in.scale", Tr.t("bloom.studio.scale"), ix, cy, iw, st.textScale, 0.5f, 3f, 0.05f, String.format(Locale.ROOT, "%.2f×", st.textScale),
				v -> {
					st.textScale = v;
					restyle(e);
				});
		ui.text(Tr.t("bloom.studio.font"), ix, cy + 4, BloomTheme.TEXT_DIM);
		HudStyle.FontChoice[] fonts = HudStyle.FontChoice.values();
		String[] fontNames = new String[fonts.length];
		for (int i = 0; i < fonts.length; i++) fontNames[i] = Tr.t(fonts[i].translationKey);
		int fsel = ui.segmented("in.font", ix + 60, cy, iw - 60, 16, fontNames, st.font.ordinal());
		if (fsel != st.font.ordinal()) {
			editNow();
			st.font = fonts[fsel];
			restyle(e);
		}
		cy += 22;
		ui.text(Tr.t("bloom.studio.align"), ix, cy + 4, BloomTheme.TEXT_DIM);
		Icon[] textAligns = {Icon.ALIGN_LEFT, Icon.ALIGN_CENTER, Icon.ALIGN_RIGHT};
		String[] textAlignKeys = {"left", "center", "right"};
		for (int i = 0; i < 3; i++) {
			int abx = ix + 60 + i * 21;
			boolean on = st.align.ordinal() == i;
			if (ui.iconButton("in.talign." + i, abx, cy - 1, 18, textAligns[i], Tr.t("bloom.studio.align." + textAlignKeys[i]), on, true) && !on) {
				editNow();
				st.align = HudStyle.Align.values()[i];
			}
		}
		cy += 22;
		cy = colorRow(ui, "in.tcol", Tr.t("bloom.studio.text_color"), ix, cy, iw, st.textColor, false, v -> st.textColor = v);
		cy = colorRow(ui, "in.lcol", Tr.t("bloom.studio.label_color"), ix, cy, iw, st.labelColor, false, v -> st.labelColor = v);
		cy = toggleRow(ui, "in.shadow", Tr.t("bloom.studio.shadow"), ix, cy, iw, st.textShadow, v -> st.textShadow = v);
		cy = sliderRow(ui, "in.spacing", Tr.t("bloom.studio.spacing"), ix, cy, iw, st.lineSpacing, 0, 8, 1, st.lineSpacing + " px", v -> {
			st.lineSpacing = Math.round(v);
			restyle(e);
		});

		// Box
		cy = section(ui, Icon.BOX, Tr.t("bloom.studio.box"), ix, cy + 2, iw, true);
		cy = sliderRow(ui, "in.pad", Tr.t("bloom.studio.padding"), ix, cy, iw, st.padding, 0, 12, 1, st.padding + " px", v -> {
			st.padding = Math.round(v);
			restyle(e);
		});
		cy = toggleRow(ui, "in.bg", Tr.t("bloom.studio.background"), ix, cy, iw, st.background, v -> st.background = v);
		if (st.background) {
			cy = colorRow(ui, "in.bgcol", Tr.t("bloom.studio.background_color"), ix, cy, iw, st.backgroundColor | 0xFF000000, false, v -> st.backgroundColor = v);
			cy = sliderRow(ui, "in.bgop", Tr.t("bloom.studio.background_opacity"), ix, cy, iw, st.backgroundOpacity, 0, 1, 0.05f,
					Math.round(st.backgroundOpacity * 100) + "%", v -> st.backgroundOpacity = v);
		}
		cy = sliderRow(ui, "in.radius", Tr.t("bloom.studio.corners"), ix, cy, iw, st.cornerRadius, 0, 8, 1, st.cornerRadius + " px",
				v -> st.cornerRadius = Math.round(v));
		cy = toggleRow(ui, "in.border", Tr.t("bloom.studio.border"), ix, cy, iw, st.border, v -> st.border = v);
		if (st.border) {
			cy = colorRow(ui, "in.bcol", Tr.t("bloom.studio.border_color"), ix, cy, iw, st.borderColor, false, v -> st.borderColor = v);
			cy = sliderRow(ui, "in.bw", Tr.t("bloom.studio.border_width"), ix, cy, iw, st.borderWidth, 1, 3, 1, st.borderWidth + " px",
					v -> st.borderWidth = Math.round(v));
		}
		cy = sliderRow(ui, "in.op", Tr.t("bloom.studio.opacity"), ix, cy, iw, st.opacity, 0.1f, 1f, 0.05f, Math.round(st.opacity * 100) + "%",
				v -> st.opacity = v);
		if (ui.button("in.copystyle", ix, cy + 2, iw / 2 - 2, 16, Tr.t("bloom.studio.copy_style"), Icon.COPY, Ui.Kind.SECONDARY, true)) {
			copiedStyle = st.copy();
			toast(Tr.t("bloom.studio.style_copied"));
		}
		if (ui.button("in.pastestyle", ix + iw / 2 + 2, cy + 2, iw / 2 - 2, 16, Tr.t("bloom.studio.paste_style"), Icon.EDIT, Ui.Kind.SECONDARY,
				copiedStyle != null)) {
			editNow();
			e.style = copiedStyle.copy();
			restyle(e);
		}
		cy += 24;

		// Widget options
		if (r != null && !r.widget().options().isEmpty()) {
			cy = section(ui, Icon.SLIDERS, Tr.t("bloom.studio.options"), ix, cy, iw, true);
			for (WidgetOption o : r.widget().options()) {
				if (o.type() == WidgetOption.Type.BOOLEAN) {
					boolean v = e.option(o.key(), Boolean.parseBoolean(o.defaultValue()));
					cy = toggleRow(ui, "in.opt." + o.key(), o.displayLabel(), ix, cy, iw, v, nv -> e.setOption(o.key(), Boolean.toString(nv)));
				} else {
					String v = e.option(o.key(), o.defaultValue());
					int sel = Math.max(0, o.choices().indexOf(v));
					String[] names = new String[o.choices().size()];
					for (int i = 0; i < names.length; i++) names[i] = o.choiceLabel(i);
					ui.text(ui.ellipsize(o.displayLabel(), iw), ix, cy, BloomTheme.TEXT_DIM);
					int ns = names.length <= 3 ? ui.segmented("in.opt." + o.key(), ix, cy + 11, iw, 16, names, sel)
							: ui.dropdown("in.opt." + o.key(), ix, cy + 11, iw, 16, names, sel);
					if (ns != sel) {
						editNow();
						e.setOption(o.key(), o.choices().get(ns));
					}
					cy += 32;
				}
			}
		}
		ui.endScroll("st.inspector", x + 2, top, w - 4, y + h - top - 4, cy + off - top + 6);
		return new int[] {x, y, w, h};
	}

	/** Section header: divider, icon and title. */
	private static int section(Ui ui, Icon icon, String title, int x, int y, int w, boolean divider) {
		if (divider) {
			ui.rect(x, y, w, 1, BloomTheme.BORDER);
			y += 6;
		}
		ui.icon(icon, x, y, BloomTheme.ACCENT);
		ui.textBold(title.toUpperCase(Locale.ROOT), x + 13, y + 1, BloomTheme.TEXT);
		return y + 15;
	}

	private interface FloatSink {
		void accept(float v);
	}

	private interface IntSink {
		void accept(int v);
	}

	private interface BoolSink {
		void accept(boolean v);
	}

	private int sliderRow(Ui ui, String id, String label, int x, int y, int w, float value, float min, float max, float step, String shown, FloatSink sink) {
		ui.text(ui.ellipsize(label, w - 40), x, y, BloomTheme.TEXT_DIM);
		ui.textRight(shown, x + w, y, BloomTheme.TEXT_MUTED);
		float nv = ui.slider(id, x + 2, y + 10, w - 4, value, min, max, step);
		if (nv != value) {
			edit();
			sink.accept(nv);
		}
		return y + 26;
	}

	private int colorRow(Ui ui, String id, String label, int x, int y, int w, int value, boolean alpha, IntSink sink) {
		ui.text(ui.ellipsize(label, w - 84), x, y + 4, BloomTheme.TEXT_DIM);
		int nv = ui.colorField(id, x + w - 80, y, 80, 16, value, alpha);
		if (nv != value) {
			edit();
			sink.accept(nv);
		}
		return y + 20;
	}

	private int toggleRow(Ui ui, String id, String label, int x, int y, int w, boolean value, BoolSink sink) {
		ui.text(ui.ellipsize(label, w - 30), x, y + 2, BloomTheme.TEXT_DIM);
		if (ui.toggle(id, x + w - 22, y, value)) {
			editNow();
			sink.accept(!value);
		}
		return y + 18;
	}

	// ---------------------------------------------------------------- keyboard

	@Override
	protected boolean onKey(KeyEvent event) {
		HudElement e = selected();
		boolean ctrl = event.hasControlDownWithQuirk();
		int key = event.key();
		if (ctrl && key == InputConstants.KEY_Z) {
			restore(undo, redo);
			return true;
		}
		if (ctrl && key == 28 /* Y */) {
			restore(redo, undo);
			return true;
		}
		if (key == InputConstants.KEY_TAB) {
			panels = !panels;
			return true;
		}
		if (event.isEscape() && (helpOpen || importOpen)) {
			helpOpen = importOpen = false;
			return true;
		}
		if (e == null) {
			if (event.isEscape() && drawer != Drawer.NONE) {
				drawer = Drawer.NONE;
				return true;
			}
			return false;
		}
		if (event.isEscape()) {
			select(null);
			return true;
		}
		if (ctrl && key == InputConstants.KEY_D) {
			duplicate(e);
			return true;
		}
		if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
			delete(e);
			return true;
		}
		if (key == 11 /* H */) {
			isolate = !isolate;
			return true;
		}
		int step = event.hasShiftDown() ? 10 : 1;
		int dx = 0, dy = 0;
		if (event.isLeft()) dx = -step;
		else if (event.isRight()) dx = step;
		else if (event.isUp()) dy = -step;
		else if (event.isDown()) dy = step;
		if (dx != 0 || dy != 0) {
			edit();
			int[] s = size(e);
			place(e, elX(e, s[0]) + dx, elY(e, s[1]) + dy, e.anchor);
			return true;
		}
		return false;
	}
}
