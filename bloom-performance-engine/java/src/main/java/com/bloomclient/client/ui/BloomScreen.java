package com.bloomclient.client.ui;

import com.bloomclient.client.BloomClient;
import com.bloomclient.client.BloomKeys;
import com.bloomclient.client.BloomSettings;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Base of Bloom's in-game screens.
 *
 * <p>Bloom draws on its own canvas whose pixel size is an integer number of
 * physical pixels chosen from the window height (and the Menu scale
 * setting), independent of Minecraft's GUI scale: the menu stays crisp and
 * equally readable at every GUI scale. Input is forwarded to the {@link Ui}
 * toolkit in canvas coordinates.
 *
 * <p>Closing returns to the screen that was open before (title or pause
 * screen) or to the game; the menu key and Escape close it, unless a text
 * field has focus.
 */
public abstract class BloomScreen extends Screen {
	private static final long OPEN_MS = 170, CLOSE_MS = 110;

	protected final Ui ui = new Ui();
	protected final @Nullable Screen parent;
	/** Canvas units → GUI units. */
	protected float k = 1f;
	/** Physical pixels per canvas unit (anti-aliased shapes are drawn at this resolution). */
	protected int px = 1;
	protected int cw, ch;
	private long openedAt;
	private long closingAt;
	private @Nullable String toast;
	private int toastColor;
	private long toastUntil;
	private @Nullable String confirmMessage;
	private @Nullable Runnable confirmAction;

	protected BloomScreen(Component title, @Nullable Screen parent) {
		super(title);
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (openedAt == 0) openedAt = System.currentTimeMillis();
		computeScale();
	}

	private void computeScale() {
		var window = minecraft.getWindow();
		int guiScale = Math.max(1, window.getGuiScale());
		int target = Math.max(1, Math.round(window.getHeight() / 500f));
		BloomSettings.MenuScale pref = BloomClient.settings().menuScale.get();
		if (pref == BloomSettings.MenuScale.SMALL) target = Math.max(1, target - 1);
		if (pref == BloomSettings.MenuScale.LARGE) target++;
		// Never larger than what fits a 400×260 canvas.
		while (target > 1 && (window.getWidth() / target < 400 || window.getHeight() / target < 260)) target--;
		k = target / (float) guiScale;
		px = target;
		cw = Math.round(width / k);
		ch = Math.round(height / k);
	}

	/** 0..1 open progress (1 = fully open), reversed while closing. */
	protected float openProgress() {
		if (!BloomClient.settings().animations.on()) return closingAt != 0 ? 0 : 1;
		long now = System.currentTimeMillis();
		float t;
		if (closingAt != 0) {
			t = 1f - Math.min(1f, (now - closingAt) / (float) CLOSE_MS);
		} else {
			t = Math.min(1f, (now - openedAt) / (float) OPEN_MS);
		}
		float inv = 1 - t;
		return 1 - inv * inv * inv;
	}

	@Override
	public final void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		computeScale();
		float p = openProgress();
		if (closingAt != 0 && (p <= 0.001f || !BloomClient.settings().animations.on())) {
			finishClose();
			return;
		}
		BloomTheme.apply(BloomClient.settings().glass.on());
		beforeCanvas(g, mouseX, mouseY, p);
		g.pose().pushMatrix();
		g.pose().scale(k, k);
		boolean input = closingAt == 0;
		ui.begin(g, input ? Math.round(mouseX / k) : -10000, input ? Math.round(mouseY / k) : -10000, cw, ch);
		ui.alpha = p;
		ui.modal(confirmMessage != null);
		try {
			Draw.setFine(px);
			renderCanvas(ui, p);
			drawToast();
			if (confirmMessage != null) drawConfirm();
		} finally {
			ui.modal(false);
			ui.alpha = 1f;
			ui.end();
			Draw.setFine(1);
			g.pose().popMatrix();
		}
	}

	/** Drawing in GUI coordinates before the canvas (e.g. the HUD preview). */
	protected void beforeCanvas(GuiGraphicsExtractor g, int mouseX, int mouseY, float openProgress) {
	}

	/** Draws the screen on the canvas ({@link #cw}×{@link #ch}). */
	protected abstract void renderCanvas(Ui ui, float openProgress);

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		float p = openProgress();
		if (minecraft.level == null) {
			extractPanorama(g, a);
		}
		if (BloomClient.settings().blur.on() && minecraft.options.getMenuBackgroundBlurriness() >= 1 && p > 0.5f) {
			g.blurBeforeThisStratum();
		}
		g.fill(0, 0, width, height, BloomTheme.withAlpha(BloomTheme.BACKDROP, p * backdropStrength()));
	}

	protected float backdropStrength() {
		return 1f;
	}

	// ---------------------------------------------------------------- feedback

	public void toast(String message, int color) {
		toast = message;
		toastColor = color;
		toastUntil = System.currentTimeMillis() + 2600;
	}

	public void toast(String message) {
		toast(message, BloomTheme.ACCENT);
	}

	/** Asks for confirmation before running a destructive action. */
	public void confirm(String message, Runnable action) {
		confirmMessage = message;
		confirmAction = action;
	}

	private void drawToast() {
		if (toast == null) return;
		long left = toastUntil - System.currentTimeMillis();
		if (left <= 0) {
			toast = null;
			return;
		}
		float a = Math.min(1f, left / 300f);
		float prev = ui.alpha;
		ui.alpha = prev * a;
		ui.g.nextStratum();
		int w = ui.width(toast) + 24;
		int x = (cw - w) / 2, y = ch - 34;
		Draw.shadow(ui.g, x, y, w, 20, 4, 3);
		ui.round(x, y, w, 20, 4, BloomTheme.POPUP);
		ui.outline(x, y, w, 20, 4, BloomTheme.BORDER_STRONG);
		ui.round(x + 8, y + 8, 4, 4, 2, toastColor);
		ui.text(toast, x + 16, y + 6, BloomTheme.TEXT);
		ui.alpha = prev;
	}

	private void drawConfirm() {
		ui.g.nextStratum();
		ui.drawingModal(true);
		try {
			ui.rect(0, 0, cw, ch, 0x90000000);
			int w = 260;
			var lines = ui.wrap(confirmMessage, w - 28);
			int h = 58 + lines.size() * 11;
			int x = (cw - w) / 2, y = (ch - h) / 2;
			Draw.shadow(ui.g, x, y, w, h, 6, 4);
			ui.round(x, y, w, h, 6, BloomTheme.POPUP);
			ui.outline(x, y, w, h, 6, BloomTheme.BORDER_STRONG);
			ui.icon(Icon.WARNING, x + 14, y + 14, BloomTheme.WARNING);
			for (int i = 0; i < lines.size(); i++) ui.text(lines.get(i), x + 30, y + 15 + i * 11, BloomTheme.TEXT);
			int by = y + h - 28;
			if (ui.button("confirm.cancel", x + w - 170, by, 74, 18, Tr.t("bloom.common.cancel"), Ui.Kind.SECONDARY)) {
				confirmMessage = null;
				confirmAction = null;
			}
			if (ui.button("confirm.ok", x + w - 88, by, 74, 18, Tr.t("bloom.common.confirm"), Ui.Kind.DANGER)) {
				Runnable action = confirmAction;
				confirmMessage = null;
				confirmAction = null;
				if (action != null) action.run();
			}
		} finally {
			ui.drawingModal(false);
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (closingAt != 0) return true;
		ui.onMouseDown(event.x() / k, event.y() / k, event.button(), doubleClick);
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		ui.onMouseUp(event.button());
		onMouseReleased(event.button());
		return true;
	}

	protected void onMouseReleased(int button) {
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		ui.onScroll(scrollY);
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (closingAt != 0) return true;
		if (confirmMessage != null) {
			if (event.isEscape()) {
				confirmMessage = null;
				confirmAction = null;
			}
			return true;
		}
		if (ui.onKey(event)) return true;
		if (ui.popupOpen() && event.isEscape()) {
			ui.closePopups();
			return true;
		}
		if (onKey(event)) return true;
		if (BloomKeys.isMenuKey(event) && closesWithMenuKey()) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	/** Screen-specific keys, after text fields. */
	protected boolean onKey(KeyEvent event) {
		return false;
	}

	protected boolean closesWithMenuKey() {
		return true;
	}

	@Override
	public boolean charTyped(CharacterEvent event) {
		return ui.onChar(event);
	}

	@Override
	public void onClose() {
		if (closingAt != 0) return;
		ui.clearFocus();
		if (BloomClient.settings().animations.on()) {
			closingAt = System.currentTimeMillis();
		} else {
			finishClose();
		}
	}

	private void finishClose() {
		closingAt = 0;
		onClosed();
		minecraft.gui.setScreen(parent);
	}

	/** Called once just before the screen is replaced. */
	protected void onClosed() {
	}

	@Override
	public void removed() {
		ui.clearFocus();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
